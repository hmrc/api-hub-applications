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

import com.google.inject.Inject
import org.apache.pekko.actor.ActorSystem
import play.api.Logging
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.{CallError, ClientNotFound, IdmsIssue, UnexpectedResponse}
import uk.gov.hmrc.apihubapplications.services.MetricsService
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import CircuitBreaker.CircuitBreakerState
import uk.gov.hmrc.apihubapplications.models.application.EnvironmentName

import scala.concurrent.ExecutionContext

class IdmsCircuitBreaker[Env <: EnvironmentName](
                          val servicesConfig: ServicesConfig,
                          metricsService: MetricsService,
                          val environmentName: Env,
                        )(implicit override val ec: ExecutionContext,
                          override val actorSystem: ActorSystem
                        ) extends CircuitBreaker[Env, IdmsException] with Logging {

  override protected val serviceName: String = s"idms-$environmentName".toLowerCase
  private val invalidFailureIssues = Seq(CallError, UnexpectedResponse)

  override def defineFailure(failure: IdmsException): Boolean =
    invalidFailureIssues.contains(failure.issue)

  override protected def notifyOnStateChange(newState: CircuitBreakerState): Unit = {
    super.notifyOnStateChange(newState)
    if (newState == CircuitBreakerState.Open)
      metricsService.idmsCircuitBreakerOpen()
  }
}
