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

package uk.gov.hmrc.apihubapplications.controllers

import com.google.inject.{Inject, Singleton}
import play.api.libs.json.{Format, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.apihubapplications.circuitbreakers.{CircuitBreaker, CircuitBreakers}
import uk.gov.hmrc.apihubapplications.circuitbreakers.CircuitBreaker.CircuitBreakerState
import uk.gov.hmrc.apihubapplications.controllers.actions.IdentifierAction
import uk.gov.hmrc.apihubapplications.models.application.EnvironmentName
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, IdmsException}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.ExecutionContext

@Singleton
class StatusController @Inject()(
                                 cc: ControllerComponents,
                                 identify: IdentifierAction,
                                 circuitBreakers: CircuitBreakers,
                               )(implicit ec: ExecutionContext) extends BackendController(cc):

  import circuitBreakers.given

  def status(): Action[AnyContent] = identify {
    implicit _ =>
      val statuses = EnvironmentName.values.flatMap: environmentName =>
        val apimStatus = circuitBreakers.circuitBreakerState[ApimException](environmentName)
        val idmsStatus = circuitBreakers.circuitBreakerState[IdmsException](environmentName)
        def isDown(state: CircuitBreakerState): Boolean = state != CircuitBreakerState.Closed
        Seq(
          ServiceStatus(isDown(apimStatus), "apim", environmentName),
          ServiceStatus(isDown(idmsStatus), "idms", environmentName),
        )
      Ok(Json.toJson(ServiceStatuses(statuses)))
  }

case class ServiceStatus(isDown: Boolean, service: String, environmentName: EnvironmentName)
object ServiceStatus:
  given statusFormat: Format[ServiceStatus] = Json.format[ServiceStatus]

case class ServiceStatuses(statuses: Seq[ServiceStatus])
object ServiceStatuses:
  given statusesFormat: Format[ServiceStatuses] = Json.format[ServiceStatuses]
