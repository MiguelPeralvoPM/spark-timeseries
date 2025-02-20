/**
 * Copyright (c) 2015, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.sparkts

import breeze.linalg._

import com.cloudera.sparkts.UnivariateTimeSeries.{differencesOfOrderD, inverseDifferencesOfOrderD}

import org.apache.commons.math3.analysis.{MultivariateVectorFunction, MultivariateFunction}
import org.apache.commons.math3.analysis.solvers.LaguerreSolver
import org.apache.commons.math3.optim.{InitialGuess, MaxEval, MaxIter,
  SimpleBounds, SimpleValueChecker}
import org.apache.commons.math3.optim.nonlinear.scalar.gradient.NonLinearConjugateGradientOptimizer
import org.apache.commons.math3.optim.nonlinear.scalar.{GoalType, ObjectiveFunction,
  ObjectiveFunctionGradient}
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.BOBYQAOptimizer
import org.apache.commons.math3.random.RandomGenerator
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression

/**
 * ARIMA models allow modeling timeseries as a function of prior values of the series
 * (i.e. autoregressive terms) and a moving average of prior error terms. ARIMA models
 * are traditionally specified as ARIMA(p, d, q), where p is the autoregressive order,
 * d is the differencing order, and q is the moving average order. Using the backshift (aka
 * lag operator) B, which when applied to a Y returns the prior value, the
 * ARIMA model can be specified as
 * Y_t = c + \sum_{i=1}^p \phi_i*B^i*Y_t + \sum_{i=1}^q \theta_i*B^i*\epsilon_t + \epsilon_t
 * where Y_i has been differenced as appropriate according to order `d`
 * See [[https://en.wikipedia.org/wiki/Autoregressive_integrated_moving_average]] for more
 * information on ARIMA.
 * See [[https://en.wikipedia.org/wiki/Order_of_integration]] for more information on differencing
 * integrated time series.
 */
object ARIMA {
  /**
   * Given a time series, fit a non-seasonal ARIMA model of order (p, d, q), where p represents
   * the autoregression terms, d represents the order of differencing, and q moving average error
   * terms. If includeIntercept is true, the model is fitted with an intercept. In order to select
   * the appropriate order of the model, users are advised to inspect ACF and PACF plots, or compare
   * the values of the objective function. Finally, while the current implementation of
   * `fitModel` verifies that parameters fit stationarity and invertibility requirements,
   * there is currently no function to transform them if they do not. It is up to the user
   * to make these changes as appropriate (or select a different model specification)
   * @param p autoregressive order
   * @param d differencing order
   * @param q moving average order
   * @param ts time series to which to fit an ARIMA(p, d, q) model
   * @param includeIntercept if true the model is fit with an intercept term. Default is true
   * @param method objective function and optimization method, current options are 'css-bobyqa',
   *               and 'css-cgd'. Both optimize the loglikelihood in terms of the
   *               conditional sum of squares. The first uses BOBYQA for optimization, while
   *               the second uses conjugate gradient descent. Default is 'css-cgd'
   * @param userInitParams A set of user provided initial parameters for optimization. If null
   *                       (default), initialized using Hannan-Rissanen algorithm. If provided,
   *                       order of parameter should be: intercept term, AR parameters (in
   *                       increasing order of lag), MA parameters (in increasing order of lag)
   *
   *
   * @return
   */
  def fitModel(
      p: Int,
      d: Int,
      q: Int,
      ts: Vector[Double],
      includeIntercept: Boolean = true,
      method: String = "css-cgd",
      userInitParams: Array[Double] = null): ARIMAModel = {
    // Drop first d terms, can't use those to fit, since not at the right level of differencing
    val diffedTs = differencesOfOrderD(ts, d).toArray.drop(d)

    if (p > 0 && q == 0) {
      // AR model takes a flag for no intercept, so pass accordingly
      val arModel = Autoregression.fitModel(new DenseVector(diffedTs), p, !includeIntercept)
      val intercept = if (includeIntercept) Array(arModel.c) else Array[Double]()
      val params = intercept ++ arModel.coefficients
      return new ARIMAModel(p, d, q, params, includeIntercept)
    }

    // Initial parameter guesses if none provided by user
    val initParams = if (userInitParams == null) {
      HannanRissanenInit(p, q, diffedTs, includeIntercept)
    } else {
      userInitParams
    }

    val params = method match {
      case "css-bobyqa" => {
        fitWithCSSBOBYQA(p, d, q, diffedTs, includeIntercept, initParams)
      }
      case "css-cgd" => {
        fitWithCSSCGD( p, d, q, diffedTs, includeIntercept, initParams)
      }
      case _ => throw new UnsupportedOperationException()
    }

    val model = new ARIMAModel(p, d, q, params, includeIntercept)
    // provide user with a warning if either AR or MA terms don't conform to requirements
    warnStationarityAndInvertibility(model)
    // TODO: provide transformation for stationarity and invertibility
    model
  }

  /**
   * Fit an ARIMA model using conditional sum of squares estimator, optimized using unbounded
   * BOBYQA.
   * @param p autoregressive order
   * @param d differencing order
   * @param q moving average order
   * @param diffedY differenced time series, as appropriate
   * @param includeIntercept flag to include intercept
   * @param initParams initial parameter guess for optimization
   * @return parameters optimized using CSS estimator, with method BOBYQA
   */
  private def fitWithCSSBOBYQA(
      p: Int,
      d: Int,
      q: Int,
      diffedY: Array[Double],
      includeIntercept: Boolean,
      initParams: Array[Double]): Array[Double] = {
    // We set up starting/ending trust radius using default suggested in
    // http://cran.r-project.org/web/packages/minqa/minqa.pdf
    // While # of interpolation points as mentioned common in
    // http://www.damtp.cam.ac.uk/user/na/NA_papers/NA2009_06.pdf
    val radiusStart = math.min(0.96, 0.2 * initParams.map(math.abs).max)
    val radiusEnd = radiusStart * 1e-6
    val dimension = p + q + (if (includeIntercept) 1 else 0)
    val interpPoints = dimension * 2 + 1

    val optimizer = new BOBYQAOptimizer(interpPoints, radiusStart, radiusEnd)
    val objFunction = new ObjectiveFunction(new MultivariateFunction() {
      def value(params: Array[Double]): Double = {
        new ARIMAModel(p, d, q, params, includeIntercept).logLikelihoodCSSARMA(diffedY)
      }
    })

    val initialGuess = new InitialGuess(initParams)
    val maxIter = new MaxIter(10000)
    val maxEval = new MaxEval(10000)
    val bounds = SimpleBounds.unbounded(dimension)
    val goal = GoalType.MAXIMIZE
    val optimal = optimizer.optimize(objFunction, goal, bounds, maxIter, maxEval, initialGuess)
    optimal.getPoint
  }

  /**
   * Fit an ARIMA model using conditional sum of squares estimator, optimized using conjugate
   * gradient descent
   * @param p autoregressive order
   * @param d differencing order
   * @param q moving average order
   * @param diffedY differenced time series, as appropriate
   * @param includeIntercept flag to include intercept
   * @param initParams initial parameter guess for optimization
   * @return parameters optimized using CSS estimator, with method conjugate gradient descent
   */
  private def fitWithCSSCGD(
      p: Int,
      d: Int,
      q: Int,
      diffedY: Array[Double],
      includeIntercept: Boolean,
      initParams: Array[Double]): Array[Double] = {
    val optimizer = new NonLinearConjugateGradientOptimizer(
      NonLinearConjugateGradientOptimizer.Formula.FLETCHER_REEVES,
      new SimpleValueChecker(1e-7, 1e-7))
    val objFunction = new ObjectiveFunction(new MultivariateFunction() {
      def value(params: Array[Double]): Double = {
        new ARIMAModel(p, d, q, params, includeIntercept).logLikelihoodCSSARMA(diffedY)
      }
    })
    val gradient = new ObjectiveFunctionGradient(new MultivariateVectorFunction() {
      def value(params: Array[Double]): Array[Double] = {
        new ARIMAModel(p, d, q, params, includeIntercept).gradientlogLikelihoodCSSARMA(diffedY)
      }
    })
    val initialGuess = new InitialGuess(initParams)
    val maxIter = new MaxIter(10000)
    val maxEval = new MaxEval(10000)
    val goal = GoalType.MAXIMIZE
    val optimal = optimizer.optimize(objFunction, gradient, goal, initialGuess, maxIter, maxEval)
    optimal.getPoint
  }

  /**
   * Initializes ARMA parameter estimates using the Hannan-Rissanen algorithm. The process is:
   * fit an AR(m) model of a higher order (i.e. m > max(p, q)), use this to estimate errors,
   * then fit an OLS model of AR(p) terms and MA(q) terms. The coefficients estimated by
   * the OLS model are returned as initial parameter estimates.
   * See [[http://halweb.uc3m.es/esp/Personal/personas/amalonso/esp/TSAtema9.pdf]] for more
   * information.
   * @param p autoregressive order
   * @param q moving average order
   * @param y time series to be modeled
   * @param includeIntercept flag to include intercept
   * @return initial ARMA(p, d, q) parameter estimates
   */
  private def HannanRissanenInit(
      p: Int,
      q: Int,
      y: Array[Double],
      includeIntercept: Boolean): Array[Double] = {
    val addToLag = 1
    // m > max(p, q) for higher order requirement
    val m = math.max(p, q) + addToLag
    // higher order AR(m) model
    val arModel = Autoregression.fitModel(new DenseVector(y), m)
    val arTerms1 = Lag.lagMatTrimBoth(y, m, includeOriginal = false)
    val yTrunc = y.drop(m)
    val estimated = arTerms1.zip(
      Array.fill(yTrunc.length)(arModel.coefficients)
    ).map { case (v, b) => v.zip(b).map { case (yi, bi) => yi * bi }.sum + arModel.c }
    // errors estimated from AR(m)
    val errors = yTrunc.zip(estimated).map { case (y, yhat) => y - yhat }
    // secondary regression, regresses X_t on AR and MA terms
    val arTerms2 = Lag.lagMatTrimBoth(yTrunc, p, includeOriginal = false).drop(math.max(q - p, 0))
    val errorTerms = Lag.lagMatTrimBoth(errors, q, includeOriginal = false).drop(math.max(p - q, 0))
    val allTerms = arTerms2.zip(errorTerms).map { case (ar, ma) => ar ++ ma }
    val regression = new OLSMultipleLinearRegression()
    regression.setNoIntercept(!includeIntercept)
    regression.newSampleData(yTrunc.drop(m - addToLag), allTerms)
    val params = regression.estimateRegressionParameters()
    params
  }

  /**
   * Prints a message to stdout warning users about potential issues with lack of stationarity
   * or invertibility, given AR and MA parameters, respectively
   * @param model
   */
  def warnStationarityAndInvertibility(model: ARIMAModel): Unit = {
    if (!model.isStationary()) {
      println("Warning: AR parameters are not stationary")
    }

    if (!model.isInvertible()) {
      println("Warning: MA parameters are not invertible")
    }
  }
}


class ARIMAModel(
    val p: Int, // AR order
    val d: Int, // differencing order
    val q: Int, // MA order
    val coefficients: Array[Double], // coefficients: intercept, AR, MA, with increasing degrees
    val hasIntercept: Boolean = true) extends TimeSeriesModel {
  /**
   * loglikelihood based on conditional sum of squares
   * Source: http://www.nuffield.ox.ac.uk/economics/papers/1997/w6/ma.pdf
   * @param y time series
   * @return loglikehood
   */
  def logLikelihoodCSS(y: Vector[Double]): Double = {
    val diffedY = differencesOfOrderD(y, d).toArray.drop(d)
    logLikelihoodCSSARMA(diffedY)
  }

  /**
   * loglikelihood based on conditional sum of squares. In contrast to logLikelihoodCSS the array
   * provided should correspond to an already differenced array, so that the function below
   * corresponds to the loglikelihood for the ARMA rather than the ARIMA process
   * @param diffedY differenced array
   * @return loglikelihood of ARMA
   */
  def logLikelihoodCSSARMA(diffedY: Array[Double]): Double = {
    val n = diffedY.length
    val yHat = new DenseVector(Array.fill(n)(0.0))
    val yVec = new DenseVector(diffedY)

    iterateARMA(yVec,  yHat, _ + _, goldStandard = yVec)

    val maxLag = math.max(p, q)
    // drop first maxLag terms, since we can't estimate residuals there, since no
    // AR(n) terms available
    val css = diffedY.zip(yHat.toArray).drop(maxLag).map { case (obs, pred) =>
      math.pow(obs - pred, 2)
    }.sum
    val sigma2 = css / n
    (-n / 2) * math.log(2 * math.Pi * sigma2) - css / (2 * sigma2)
  }

  /**
   * Calculates the gradient for the loglikelihood function using CSS
   * Derivation:
   * L(y | \theta) = -\frac{n}{2}log(2\pi\sigma^2) - \frac{1}{2\pi}\sum_{i=1}^n \epsilon_t^2 \\
   * \sigma^2 = \frac{\sum_{i = 1}^n \epsilon_t^2}{n} \\
   * \frac{\partial L}{\partial \theta} = -\frac{1}{\sigma^2}
   * \sum_{i = 1}^n \epsilon_t \frac{\partial \epsilon_t}{\partial \theta} \\
   * \frac{\partial \epsilon_t}{\partial \theta} = -\frac{\partial \hat{y}}{\partial \theta} \\
   * \frac{\partial\hat{y}}{\partial c} = 1 +
   * \phi_{t-q}^{t-1}*\frac{\partial \epsilon_{t-q}^{t-1}}{\partial c} \\
   * \frac{\partial\hat{y}}{\partial \theta_{ar_i}} =  y_{t - i} +
   * \phi_{t-q}^{t-1}*\frac{\partial \epsilon_{t-q}^{t-1}}{\partial \theta_{ar_i}} \\
   * \frac{\partial\hat{y}}{\partial \theta_{ma_i}} =  \epsilon_{t - i} +
   * \phi_{t-q}^{t-1}*\frac{\partial \epsilon_{t-q}^{t-1}}{\partial \theta_{ma_i}} \\
   * @param diffedY array of differenced values
   * @return
   */
  def gradientlogLikelihoodCSSARMA(diffedY: Array[Double]): Array[Double] = {
    val n = diffedY.length
    // fitted
    val yHat = new DenseVector(Array.fill(n)(0.0))
    // reference values (i.e. gold standard)
    val yRef = new DenseVector(diffedY)
    val maxLag = math.max(p, q)
    val intercept = if (hasIntercept) 1 else 0
    val maTerms = Array.fill(q)(0.0)

    // matrix of error derivatives at time t - 0, t - 1, ... t - q
    val dEdTheta = new DenseMatrix[Double](q + 1, coefficients.length)
    val gradient = new DenseVector(Array.fill(coefficients.length)(0.0))

    // error-related
    var error = 0.0
    var sigma2 = 0.0

    // iteration-related
    var i = maxLag
    var j = 0
    var k = 0

    while (i < n) {
      j = 0
      // initialize partial error derivatives in each iteration to weighted average of
      // prior partial error derivatives, using moving average coefficients
      while (j < coefficients.length) {
        k = 0
        while (k < q) {
          dEdTheta(0, j) -= coefficients(intercept + p + k) * dEdTheta(k + 1, j)
          k += 1
        }
        j += 1
      }
      // intercept
      j = 0
      yHat(i) += intercept * coefficients(j)
      dEdTheta(0, 0) -= intercept

      // autoregressive terms
      while (j < p && i - j - 1 >= 0) {
        yHat(i) += yRef(i - j - 1) * coefficients(intercept + j)
        dEdTheta(0, intercept + j) -= yRef(i - j - 1)
        j += 1
      }

      // moving average terms
      j = 0
      while (j < q) {
        yHat(i) += maTerms(j) * coefficients(intercept + p + j)
        dEdTheta(0, intercept + p + j) -= maTerms(j)
        j += 1
      }

      error = yRef(i) - yHat(i)
      sigma2 += math.pow(error, 2) / n
      updateMAErrors(maTerms, error)
      // update gradient
      gradient :+= (dEdTheta(0, ::) * error).t
      // shift back error derivatives to make room for next period
      dEdTheta(1 to -1, ::) := dEdTheta(0 to -2, ::)
      // reset latest partial error derivatives to 0
      dEdTheta(0, ::) := 0.0
      i += 1
    }

    gradient := gradient :/ -sigma2
    gradient.toArray
  }

  /**
   * Updates the error vector in place with a new (more recent) error
   * The newest error is placed in position 0, while older errors "fall off the end"
   * @param errs array of errors of length q in ARIMA(p, d, q), holds errors for t-1 through t-q
   * @param newError the error at time t
   * @return a modified array with the latest error placed into index 0
   */
  def updateMAErrors(errs: Array[Double], newError: Double): Unit = {
    val n = errs.length
    var i = 0
    while (i < n - 1) {
      errs(i + 1) = errs(i)
      i += 1
    }
    if (n > 0) {
      errs(0) = newError
    }
  }

  /**
   * Perform operations with the AR and MA terms, based on the time series `ts` and the errors
   * based off of `goldStandard` or `errors`, combined with elements from the series `dest`.
   * Weights for terms are taken from the current model configuration.
   * So for example: iterateARMA(series1, series_of_zeros,  _ + _ , goldStandard = series1,
   * initErrors = null)
   * calculates the 1-step ahead ARMA forecasts for series1 assuming current coefficients, and
   * initial MA errors of 0.
   * @param ts Time series to use for AR terms
   * @param dest Time series holding initial values at each index
   * @param op Operation to perform between values in dest, and various combinations of ts, errors
   *           and intercept terms
   * @param goldStandard The time series to which to compare 1-step ahead forecasts to obtain
   *                     moving average errors. Default set to null. In which case, we check if
   *                     errors are directly provided. Either goldStandard or errors can be null,
   *                     but not both
   * @param errors The time series of errors to be used as error at time i, which is then used
   *               for moving average terms in future indices. Either goldStandard or errors can
   *               be null, but not both
   * @param initMATerms Initialization for first q terms of moving average. If none provided (i.e.
   *                    remains null, as per default), then initializes to all zeros
   * @return the time series resulting from the interaction of the parameters with the model's
   *         coefficients
   */
  private def iterateARMA(
      ts: Vector[Double],
      dest: Vector[Double],
      op: (Double, Double) => Double,
      goldStandard: Vector[Double] = null,
      errors: Vector[Double] = null,
      initMATerms: Array[Double] = null): Vector[Double] = {
    require(goldStandard != null || errors != null, "goldStandard or errors must be passed in")
    val maTerms = if (initMATerms == null) Array.fill(q)(0.0) else initMATerms
    val intercept = if (hasIntercept) 1 else 0
    // maximum lag
    var i = math.max(p, q)
    var j = 0
    val n = ts.length
    var error = 0.0

    while (i < n) {
      j = 0
      // intercept
      dest(i) = op(dest(i), intercept * coefficients(j))
      // autoregressive terms
      while (j < p && i - j - 1 >= 0) {
        dest(i) = op(dest(i), ts(i - j - 1) * coefficients(intercept + j))
        j += 1
      }
      // moving average terms
      j = 0
      while (j < q) {
        dest(i) = op(dest(i), maTerms(j) * coefficients(intercept + p + j))
        j += 1
      }

      error = if (goldStandard == null) errors(i) else goldStandard(i) - dest(i)
      updateMAErrors(maTerms, error)
      i += 1
    }
    dest
  }

  /**
   * Given a timeseries, assume that it is the result of an ARIMA(p, d, q) process, and apply
   * inverse operations to obtain the original series of underlying errors.
   * To do so, we assume prior MA terms are 0.0, and prior AR are equal to the model's intercept or
   * 0.0 if fit without an intercept
   * @param ts Time series of observations with this model's characteristics.
   * @param destTs
   * @return The dest series, representing remaining errors, for convenience.
   */
  def removeTimeDependentEffects(ts: Vector[Double], destTs: Vector[Double]): Vector[Double] = {
    // difference as necessary
    val diffed = differencesOfOrderD(ts, d)
    val maxLag = math.max(p, q)
    val interceptAmt = if (hasIntercept) coefficients(0) else 0.0
    // extend vector so that initial AR(maxLag) are equal to intercept value
    val diffedExtended = new DenseVector(Array.fill(maxLag)(interceptAmt) ++ diffed.toArray)
    val changes = diffedExtended.copy
    // changes(i) corresponds to the error term at index i, since when it is used we will have
    // removed AR and MA terms at index i
    iterateARMA(diffedExtended, changes, _ - _, errors = changes)
    destTs := changes(maxLag to -1)
    destTs
  }

  /**
   * Given a timeseries, apply an ARIMA(p, d, q) model to it.
   * We assume that prior MA terms are 0.0 and prior AR terms are equal to the intercept or 0.0 if
   * fit without an intercept
   * @param ts Time series of i.i.d. observations.
   * @param destTs
   * @return The dest series, representing the application of the model to provided error
   *         terms, for convenience.
   */
  def addTimeDependentEffects(ts: Vector[Double], destTs: Vector[Double]): Vector[Double] = {
    val maxLag = math.max(p, q)
    val interceptAmt = if (hasIntercept) coefficients(0) else 0.0
    // extend vector so that initial AR(maxLag) are equal to intercept value
    // Recall iterateARMA begins at index maxLag, and uses previous values as AR terms
    val changes = new DenseVector(Array.fill(maxLag)(interceptAmt) ++ ts.toArray)
    // ts corresponded to errors, and we want to use them
    val errorsProvided = changes.copy
    iterateARMA(changes, changes, _ + _, errors = errorsProvided)
    // drop assumed AR terms at start and perform any inverse differencing required
    destTs := inverseDifferencesOfOrderD(changes(maxLag to -1), d)
    destTs
  }

  /**
   * Sample a series of size n assuming an ARIMA(p, d, q) process.
   * @param n size of sample
   * @return series reflecting ARIMA(p, d, q) process
   */
  def sample(n: Int, rand: RandomGenerator): Vector[Double] = {
    val vec = new DenseVector(Array.fill[Double](n)(rand.nextGaussian))
    addTimeDependentEffects(vec, vec)
  }

  /**
   * Provided fitted values for timeseries ts as 1-step ahead forecasts, based on current
   * model parameters, and then provide `nFuture` periods of forecast. We assume AR terms
   * prior to the start of the series are equal to the model's intercept term (or 0.0, if fit
   * without and intercept term).Meanwhile, MA terms prior to the start are assumed to be 0.0. If
   * there is differencing, the first d terms come from the original series.
   * @param ts Timeseries to use as gold-standard. Each value (i) in the returning series
   *           is a 1-step ahead forecast of ts(i). We use the difference between ts(i) -
   *           estimate(i) to calculate the error at time i, which is used for the moving
   *           average terms.
   * @param nFuture Periods in the future to forecast (beyond length of ts)
   * @return a series consisting of fitted 1-step ahead forecasts for historicals and then
   *         `nFuture` periods of forecasts. Note that in the future values error terms become
   *         zero and prior predictions are used for any AR terms.
   */
  def forecast(ts: Vector[Double], nFuture: Int): Vector[Double] = {
    val maxLag = math.max(p, q)
    // difference timeseries as necessary for model
    val diffedTs = new DenseVector(differencesOfOrderD(ts, d).toArray.drop(d))

    // Assumes prior AR terms are equal to model intercept
    val interceptAmt =  if (hasIntercept) coefficients(0) else 0.0
    val diffedTsExtended = new DenseVector(Array.fill(maxLag)(interceptAmt) ++ diffedTs.toArray)
    val histLen = diffedTsExtended.length
    val hist = new DenseVector(Array.fill(histLen)(0.0))

    // fit historical values (really differences in case of d > 0)
    iterateARMA(diffedTsExtended, hist, _ + _,  goldStandard = diffedTsExtended)

    // Last set of errors, to be used in forecast if MA terms included
    val maTerms = (for (i <- histLen - maxLag until histLen) yield {
      diffedTsExtended(i) - hist(i)
    }).toArray

    // copy over last maxLag values, to use in iterateARMA for forward curve
    val forward = new DenseVector(Array.fill(nFuture + maxLag)(0.0))
    forward(0 until maxLag) := hist(-maxLag to -1)
    // use self as ts to take AR from same series, use self as goldStandard to induce future errors
    // of zero, and use prior moving average errors as initial error terms for MA
    iterateARMA(forward, forward, _ + _, goldStandard = forward, initMATerms = maTerms)

    val results = new DenseVector(Array.fill(ts.length + nFuture)(0.0))
    // copy over first d terms, since we have no corresponding differences for these
    results(0 until d) := ts(0 until d)
    // copy over historicals, drop first maxLag terms (our assumed AR terms)
    results(d until d + histLen - maxLag) := hist(maxLag to -1)
    // drop first maxLag terms from forward curve before copying, these are part of hist already
    results(ts.length to -1) :=  forward(maxLag to -1)

    // Now, if there was no differencing, things are as they should be. We're good to go
    if (d == 0) {
      results
    } else {
      // we need to create 1-step ahead forecasts for the integrated series for fitted values
      // by backing through the d-order differences
      // create and fill a matrix of the changes of order i = 0 through d
      val diffMatrix = new DenseMatrix[Double](d + 1, ts.length)
      diffMatrix(0, ::) := ts.t

      for (i <- 1 to d) {
        // create incremental differences of each order
        // recall that differencesOfOrderD skips first `order` terms, so make sure
        // to advance start as appropriate
        diffMatrix(i, i to -1) := differencesOfOrderD(diffMatrix(i - 1, i to -1).t, 1).t
      }

      // historical values are done as 1 step ahead forecasts
      for (i <- d until histLen - maxLag) {
        // Add the fitted change value to elements at time i - 1 and of difference order < d
        // e.g. if d = 2, then we modeled value at i = 3 as (y_3 - y_2) - (y_2 - y_1), so we add
        // to it y_2 - y_1 (which is at diffMatrix(1, 2)) and y_2 (which is at diffMatrix(0, 2) to
        // get an estimate for y_3
        results(i) = sum(diffMatrix(0 until d, i - 1)) + hist(maxLag + i)
      }
      // Take diagonal of last d terms, of order < d,
      // so that we can inverse differencing appropriately with future values
      val prevTermsForForwardInverse = diag(diffMatrix(0 until d, -d to -1))
      // for forecasts, drop first maxLag terms
      val forwardIntegrated = inverseDifferencesOfOrderD(
        DenseVector.vertcat(prevTermsForForwardInverse, forward(maxLag to -1)),
        d
      )
      // copy into results
      results(-(d + nFuture) to -1) := forwardIntegrated
    }
    results
  }

  /**
   * Check if the AR parameters result in a stationary model. This is done by obtaining the roots
   * to the polynomial 1 - phi_1 * x - phi_2 * x^2 - ... - phi_p * x^p = 0, where phi_i is the
   * corresponding AR parameter. Note that we check the roots, not the inverse of the roots, so
   * we check that they lie outside of the unit circle.
   * Always returns true for models with no AR terms.
   * See http://www.econ.ku.dk/metrics/Econometrics2_05_II/Slides/07_univariatetimeseries_2pp.pdf
   * for more information (specifically slides 23 - 25)
   * @return indicator of whether model's AR parameters are stationary
   */
  def isStationary(): Boolean = {
    if (p == 0) {
      true
    } else {
      val offset = if (hasIntercept) 1 else 0
      val poly = Array(1.0) ++ coefficients.slice(offset, offset + p).map(-1 * _)
      allRootsOutsideUnitCircle(poly)
    }
  }

  /**
   * Checks if MA parameters result in an invertible model. Checks this by solving the roots for
   * 1 + theta_1 * x + theta_2 * x + ... + theta_q * x^q = 0. Please see
   * [[com.cloudera.sparkts.ARIMAModel.isStationary]] for more details.
   * Always returns true for models with no MA terms.
   * @return indicator of whether model's MA parameters are invertible
   */
  def isInvertible(): Boolean = {
    if (q == 0) {
      true
    } else {
      val offset = if (hasIntercept) 1 else 0
      val poly = Array(1.0) ++ coefficients.drop(offset + p)
      allRootsOutsideUnitCircle(poly)
    }
  }

  /**
   * Checks whether all roots of polynomial passed are outside unit circle. Uses Laguerre's method
   * see [[https://en.wikipedia.org/wiki/Laguerre%27s_method]] for more information
   * @param poly the coefficients of the polynomial of order `poly.length`
   * @return boolean indicating whether all roots are outside unit circle
   */
  private def allRootsOutsideUnitCircle(poly: Array[Double]): Boolean = {
    val solver = new LaguerreSolver()
    // TODO: make smarter
    val initGuess = 0.0
    val roots = solver.solveAllComplex(poly, initGuess)
    !roots.exists(_.abs() <= 1.0)
  }
}
