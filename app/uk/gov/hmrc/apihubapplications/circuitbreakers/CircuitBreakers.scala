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
import play.api.Logging
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.util.concurrent.TimeUnit.MILLISECONDS
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success
import CircuitBreaker.CircuitBreakerState
import uk.gov.hmrc.apihubapplications.models.application.EnvironmentName
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, ApplicationsException, IdmsException}
import uk.gov.hmrc.apihubapplications.services.MetricsService

import javax.inject.{Inject, Singleton}

@Singleton
class CircuitBreakers @Inject()(
                                 servicesConfig: ServicesConfig,
                                 metricsService: MetricsService,
                               )(implicit ec: ExecutionContext,
                                 actorSystem: ActorSystem
                               ):

  def withCircuitBreaker[T, Exception <: ApplicationsException](
                                                                 environmentName: EnvironmentName,
                                                                 body: => Future[Either[Exception, T]]
                                                               )(
                                                                 using cbs: Map[EnvironmentName, CircuitBreaker[EnvironmentName, Exception]]
                                                               ): Future[Either[Exception, T]] =
    cbs(environmentName).withCircuitBreaker(body)

  type CircuitBreakersType[Exception] = Map[EnvironmentName, CircuitBreaker[EnvironmentName, Exception]]

  private def circuitBreakers[E](f: EnvironmentName => CircuitBreaker[EnvironmentName, E]) =
    EnvironmentName.values
      .map(environmentName =>
        environmentName -> f(environmentName)
      ).toMap

  given apimCircuitBreakers: CircuitBreakersType[ApimException] =
    circuitBreakers(APIMCircuitBreaker[EnvironmentName](servicesConfig, metricsService, _))

  given idmsCircuitBreaker: CircuitBreakersType[IdmsException] =
    circuitBreakers(IdmsCircuitBreaker[EnvironmentName](servicesConfig, metricsService, _))

  def circuitBreakerState[Exception](environmentName: EnvironmentName)(using cb: CircuitBreakersType[Exception]): CircuitBreakerState =
    cb(environmentName).circuitBreakerState