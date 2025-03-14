/*
 * Copyright 2025 HM Revenue & Customs
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
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.controllers.actions.{HipEnvironmentActionProvider, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.apim.SuccessfulDeploymentResponse
import uk.gov.hmrc.apihubapplications.models.exception.ApimException
import uk.gov.hmrc.apihubapplications.models.exception.ApimException.ServiceNotFound
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.ClientNotFound
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.ExecutionContext

@Singleton
class ApimController @Inject()(
  cc: ControllerComponents,
  identify: IdentifierAction,
  hipEnvironment: HipEnvironmentActionProvider,
  apimConnector: APIMConnector,
  idmsConnector: IdmsConnector
)(implicit ec: ExecutionContext) extends BackendController(cc) {

  def getDeployments(environment: String): Action[AnyContent] = (identify andThen hipEnvironment(environment)).async {
    implicit request =>
      apimConnector.getDeployments(request.hipEnvironment).map {
        case Right(deployments) => Ok(Json.toJson(deployments))
        case Left(_) => BadGateway
      }
  }

  def getDeployment(environment: String, publisherRef: String): Action[AnyContent] = (identify andThen hipEnvironment(environment)).async {
    implicit request =>
      apimConnector.getDeployment(publisherRef, request.hipEnvironment).map {
        case Right(Some(response: SuccessfulDeploymentResponse)) => Ok(Json.toJson(response))
        case Right(None) => NotFound
        case Left(_) => BadGateway
      }
  }

  def getOpenApiSpecification(environment: String, publisherRef: String): Action[AnyContent] = (identify andThen hipEnvironment(environment)).async {
    implicit request =>
      apimConnector.getOpenApiSpecification(publisherRef, request.hipEnvironment).map {
        case Right(oas) => Ok(oas)
        case Left(e) if e.issue == ServiceNotFound => NotFound
        case Left(_) => BadGateway
      }
  }

  def getDeploymentDetails(environment: String, publisherRef: String): Action[AnyContent] = (identify andThen hipEnvironment(environment)).async {
    implicit request =>
      apimConnector.getDeploymentDetails(publisherRef, request.hipEnvironment).map {
        case Right(deploymentDetails) => Ok(Json.toJson(deploymentDetails))
        case Left(e) if e.issue == ServiceNotFound => NotFound
        case Left(_) => BadGateway
      }
  }

  def getDeploymentStatus(environment: String, publisherReference: String, mergeRequestIid: String, version: String): Action[AnyContent] = (identify andThen hipEnvironment(environment)).async {
    implicit request =>
      apimConnector.getDeploymentStatus(publisherReference, mergeRequestIid, version, request.hipEnvironment).map {
        case Right(statusResponse) => Ok(Json.toJson(statusResponse))
        case Left(e) if e.issue == ServiceNotFound => NotFound
        case Left(_) => BadGateway
      }
  }

  def listEgressGateways(environment: String): Action[AnyContent] = (identify andThen hipEnvironment(environment)).async {
    implicit request =>
      apimConnector.listEgressGateways(request.hipEnvironment).map {
        case Right(egressGateways) => Ok(Json.toJson(egressGateways))
        case Left(_) => BadGateway
      }
  }

  def fetchClientScopes(environment: String, clientId: String): Action[AnyContent] = (identify andThen hipEnvironment(environment)).async {
    implicit request =>
      idmsConnector.fetchClientScopes(request.hipEnvironment, clientId).map {
        case Right(scopes) => Ok(Json.toJson(scopes))
        case Left(e) if e.issue == ClientNotFound => NotFound
        case Left(_) => BadGateway
      }
  }

}
