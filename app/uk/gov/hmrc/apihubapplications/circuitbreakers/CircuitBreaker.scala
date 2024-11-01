/*
 * Copyright 2024 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.apihubapplications.circuitbreakers

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.CircuitBreaker as PekkoCircuitBreaker
import play.api.Logging
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.util.concurrent.TimeUnit.MILLISECONDS
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success
import CircuitBreaker.CircuitBreakerState
import uk.gov.hmrc.apihubapplications.circuitbreakers
import uk.gov.hmrc.apihubapplications.models.application.EnvironmentName

trait CircuitBreaker[Env <: EnvironmentName, -E] { this: Logging =>
  protected val actorSystem: ActorSystem
  implicit val ec: ExecutionContext

  protected val servicesConfig: ServicesConfig

  protected def serviceName: String

  protected lazy val numberOfCallsToTriggerStateChange: Int = servicesConfig.getInt("circuitBreaker.numberOfCallsToTriggerStateChange")
  protected lazy val unstablePeriodDurationInMillis: Int = servicesConfig.getInt("circuitBreaker.unstablePeriodDurationInMillis")
  protected lazy val unavailablePeriodDurationInMillis: Int = servicesConfig.getInt("circuitBreaker.unavailablePeriodDurationInMillis")

  def defineFailure(result: E): Boolean

  def withCircuitBreaker[T, Exception <: E](body: => Future[Either[Exception, T]]): Future[Either[Exception, T]] =
    breaker.withCircuitBreaker(body, {
      case Success(Right(_)) => false
      case Success(Left(e)) => defineFailure(e)
      case _ => true
    })

  def circuitBreakerState: CircuitBreakerState =
    if (breaker.isClosed) then CircuitBreakerState.Closed
    else if (breaker.isOpen) then CircuitBreakerState.Open
    else CircuitBreakerState.HalfOpen

  private lazy val breaker = new PekkoCircuitBreaker(
      scheduler = actorSystem.scheduler,
      maxFailures = numberOfCallsToTriggerStateChange,
      callTimeout = Duration(unstablePeriodDurationInMillis, MILLISECONDS),
      resetTimeout = Duration(unavailablePeriodDurationInMillis, MILLISECONDS),
    )
    .onOpen(notifyOnStateChange(CircuitBreakerState.Open))
    .onClose(notifyOnStateChange(CircuitBreakerState.Closed))
    .onHalfOpen(notifyOnStateChange(CircuitBreakerState.HalfOpen))

  protected def notifyOnStateChange(newState: CircuitBreakerState): Unit =
    logger.warn(s"Circuit breaker ${getClass.getSimpleName} is now $newState")

}

object CircuitBreaker {
  enum CircuitBreakerState:
    case Open, HalfOpen, Closed
}