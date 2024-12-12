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
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnectorParityImpl, IdmsConnectorParityImpl}
import uk.gov.hmrc.apihubapplications.controllers.actions.{HipEnvironmentActionProvider, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.ExecutionContext

@Singleton
class EnvironmentParityTestController @Inject()(
  cc: ControllerComponents,
  identify: IdentifierAction,
  idmsConnector: IdmsConnectorParityImpl,
  apimConnector: APIMConnectorParityImpl,
  hipEnvironment: HipEnvironmentActionProvider
)(implicit ec: ExecutionContext) extends BackendController(cc) {

  def fetchClientScopes(environmentName: String, clientId: String): Action[AnyContent] = (identify andThen hipEnvironment(environmentName)).async {
    implicit request =>
      idmsConnector.fetchClientScopes(request.hipEnvironment.environmentName, clientId).map {
        case Right(scopes) => Ok(Json.toJson(scopes))
        case Left(e) if e.issue == IdmsException.ClientNotFound => NotFound
        case Left(e) => throw e
      }
  }

  def fetchEgresses(environmentName: String): Action[AnyContent] = (identify andThen hipEnvironment(environmentName)).async {
    implicit request =>
      apimConnector.listEgressGateways(request.hipEnvironment.environmentName).map {
        case Right(egresses) => Ok(Json.toJson(egresses))
        case Left(e) => throw e
      }
  }

  def fetchDeployments(environmentName: String): Action[AnyContent] = (identify andThen hipEnvironment(environmentName)).async {
    implicit request =>
      apimConnector.getDeployments(request.hipEnvironment.environmentName).map {
        case Right(deployments) => Ok(Json.toJson(deployments))
        case Left(e) => throw e
      }
  }

}
