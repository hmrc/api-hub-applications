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
import uk.gov.hmrc.apihubapplications.models.application.EnvironmentName
import uk.gov.hmrc.apihubapplications.models.exception.ApimException
import uk.gov.hmrc.apihubapplications.models.exception.ApimException.{CallError, UnexpectedResponse}
import uk.gov.hmrc.apihubapplications.services.MetricsService
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import CircuitBreaker.CircuitBreakerState
import CircuitBreaker.CircuitBreakerState.*

import scala.concurrent.ExecutionContext

class APIMCircuitBreaker[Env <: EnvironmentName](
                          val servicesConfig: ServicesConfig,
                          metricsService: MetricsService,
                          val environmentName: Env,
                        )(implicit override val ec: ExecutionContext,
                          override val actorSystem: ActorSystem
                        ) extends CircuitBreaker[Env, ApimException] with Logging {

  override protected val serviceName: String = s"apim-$environmentName".toLowerCase
  private val failureExceptions = Seq(UnexpectedResponse, CallError)

  override def defineFailure(failure: ApimException): Boolean =
    failureExceptions.contains(failure.issue)

  override protected def notifyOnStateChange(newState: CircuitBreakerState): Unit = {
    super.notifyOnStateChange(newState)
    if (newState == Open)
      metricsService.apimCircuitBreakerOpen()
  }

}
