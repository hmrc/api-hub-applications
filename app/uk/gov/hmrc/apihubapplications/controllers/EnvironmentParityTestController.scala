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
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnectorEnvironmentParityImpl
import uk.gov.hmrc.apihubapplications.controllers.actions.IdentifierAction
import uk.gov.hmrc.apihubapplications.models.application.EnvironmentName
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class EnvironmentParityTestController @Inject()(
  cc: ControllerComponents,
  identify: IdentifierAction,
  hipEnvironments: HipEnvironments,
  idmsConnector: IdmsConnectorEnvironmentParityImpl
)(implicit ec: ExecutionContext) extends BackendController(cc) {

  def fetchClientScopes(environmentName: EnvironmentName, clientId: String): Action[AnyContent] = identify.async {
    implicit request =>
      hipEnvironments
        .forEnvironmentNameOption(environmentName)
        .map(
          hipEnvironment =>
            idmsConnector.fetchClientScopes(hipEnvironment.environmentName, clientId).map {
              case Right(scopes) => Ok(Json.toJson(scopes))
              case Left(e) => throw e
            }
        )
        .getOrElse(
          Future.successful(NotFound)
        )
  }

}
