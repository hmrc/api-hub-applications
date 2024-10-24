/*
 * Copyright 2023 HM Revenue & Customs
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
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.apihubapplications.controllers.actions.IdentifierAction
import uk.gov.hmrc.apihubapplications.models.apim._
import uk.gov.hmrc.apihubapplications.models.application.{Primary, Secondary}
import uk.gov.hmrc.apihubapplications.models.exception.ApimException.ServiceNotFound
import uk.gov.hmrc.apihubapplications.models.exception.{ApiNotFoundException, ApimException}
import uk.gov.hmrc.apihubapplications.models.requests.{DeploymentStatus, DeploymentStatuses}
import uk.gov.hmrc.apihubapplications.services.DeploymentsService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentsController @Inject()(
                                       identify: IdentifierAction,
                                       cc: ControllerComponents,
                                       deploymentsService: DeploymentsService,
                                     )(implicit ec: ExecutionContext) extends BackendController(cc) with Logging {

  def generate: Action[JsValue] = identify.compose(Action(parse.json)).async {
    implicit request =>
      val jsReq = request.body
      jsReq.validate[DeploymentsRequest] match {
        case JsSuccess(deploymentsRequest, _) => deploymentsService.deployToSecondary(deploymentsRequest) map {
          case Right(response: InvalidOasResponse) => BadRequest(Json.toJson(response))
          case Right(response: SuccessfulDeploymentsResponse) => Ok(Json.toJson(response))
          case Left(e) => throw e
        }
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

  def update(publisherRef: String): Action[JsValue] = identify.compose(Action(parse.json)).async {
    implicit request =>
      request.body.validate[RedeploymentRequest] match {
        case JsSuccess(redeploymentRequest, _) => deploymentsService.redeployToSecondary(publisherRef, redeploymentRequest) map {
          case Right(response: InvalidOasResponse) => BadRequest(Json.toJson(response))
          case Right(response: SuccessfulDeploymentsResponse) => Ok(Json.toJson(response))
          case Left(e: ApimException) if e.issue == ServiceNotFound => NotFound
          case Left(e) => throw e
        }
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

  def getDeploymentStatus(publisherRef: String): Action[AnyContent] = identify.compose(Action).async {
    implicit request =>
      deploymentsService.getDeployments(publisherRef)
        .map(response =>
          Ok(Json.toJson(DeploymentStatuses(response)))
        )
  }

  def getDeploymentDetails(publisherRef: String): Action[AnyContent] = identify.async {
    implicit request =>
      deploymentsService.getDeploymentDetails(publisherRef).map {
        case Right(deploymentDetails) => Ok(Json.toJson(deploymentDetails))
        case Left(e) if e.issue == ServiceNotFound => NotFound
        case Left(e) => throw e
      }
  }

  def promoteToProduction(publisherRef: String): Action[AnyContent] = identify.async {
    implicit request =>
      deploymentsService.promoteToProduction(publisherRef).map {
        case Right(response: SuccessfulDeploymentsResponse) => Ok(Json.toJson(response))
        case Right(response: InvalidOasResponse) => BadRequest(Json.toJson(response))
        case Left(e) if e.issue == ServiceNotFound => NotFound
        case Left(e) => throw e
      }
  }

  def updateApiTeam(apiId: String, teamId: String): Action[AnyContent] = identify.async {
    implicit request =>
      deploymentsService.updateApiTeam(apiId, teamId) flatMap {
        case Right(()) => Future.successful(Ok)
        case Left(_: ApiNotFoundException) => Future.successful(NotFound)
        case Left(e) => throw e
      }
  }

  def removeTeam(apiId: String): Action[AnyContent] = identify.compose(Action).async {
    implicit request =>
      deploymentsService.removeOwningTeamFromApi(apiId).map {
        case Right(()) => NoContent
        case Left(e: ApiNotFoundException) => NotFound
        case Left(_) => InternalServerError
      }
  }

}
