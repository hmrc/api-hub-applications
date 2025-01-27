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
import play.api.Logging
import play.api.libs.json.*
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.apihubapplications.config.HipEnvironment
import uk.gov.hmrc.apihubapplications.controllers.actions.{HipEnvironmentActionProvider, IdentifierAction}
import uk.gov.hmrc.apihubapplications.services.EgressService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.ExecutionContext

@Singleton
class EgressController @Inject()(
  cc: ControllerComponents,
  identify: IdentifierAction,
  egressService: EgressService,
  hipEnvironment: HipEnvironmentActionProvider
)(implicit ec: ExecutionContext) extends BackendController(cc) with Logging {

  def listEgressGateways(environmentName: String): Action[AnyContent] = (identify andThen hipEnvironment(environmentName)).async {
    implicit request =>
        egressService.listEgressGateways(request.hipEnvironment) map {
          case Left(e) => InternalServerError(e.message)
          case Right(egressGateways) => Ok(Json.toJson(egressGateways))
        }
  }

}
