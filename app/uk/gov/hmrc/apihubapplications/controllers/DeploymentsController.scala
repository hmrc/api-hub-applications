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
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}
import uk.gov.hmrc.apihubapplications.controllers.actions.{HipEnvironmentAction, HipEnvironmentActionProvider, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.apim.*
import uk.gov.hmrc.apihubapplications.models.exception.ApimException.ServiceNotFound
import uk.gov.hmrc.apihubapplications.models.exception.AutopublishException.DeploymentNotFound
import uk.gov.hmrc.apihubapplications.models.exception.{ApiNotFoundException, ApimException, AutopublishException}
import uk.gov.hmrc.apihubapplications.models.requests.{DeploymentStatus, DeploymentStatuses}
import uk.gov.hmrc.apihubapplications.services.DeploymentsService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentsController @Inject()(
                                       identify: IdentifierAction,
                                       cc: ControllerComponents,
                                       deploymentsService: DeploymentsService,
                                       hipEnvironments: HipEnvironments,
                                       hipEnvironment: HipEnvironmentActionProvider
                                     )(implicit ec: ExecutionContext) extends BackendController(cc) with Logging with UserEmailAwareness {

  def generate: Action[JsValue] = identify.compose(Action(parse.json)).async {
    implicit request =>
      val jsReq = request.body
      jsReq.validate[DeploymentsRequest] match {
        case JsSuccess(deploymentsRequest, _) => deploymentsService.createApi(deploymentsRequest) map {
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
      withUserEmail(
        userEmail =>
          request.body.validate[RedeploymentRequest] match {
            case JsSuccess(redeploymentRequest, _) => deploymentsService.updateApi(publisherRef, redeploymentRequest, userEmail) map {
              case Right(response: InvalidOasResponse) => BadRequest(Json.toJson(response))
              case Right(response: SuccessfulDeploymentsResponse) => Ok(Json.toJson(response))
              case Left(_: ApiNotFoundException) => NotFound
              case Left(e: ApimException) if e.issue == ServiceNotFound => NotFound
              case Left(e) => throw e
            }
            case e: JsError =>
              logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
              Future.successful(BadRequest)
          }
      )
  }

  def getDeploymentStatus(publisherRef: String): Action[AnyContent] = identify.compose(Action).async {
    implicit request =>
      deploymentsService.getDeployments(publisherRef)
        .map(response =>
          Ok(Json.toJson(DeploymentStatuses(response)))
        )
  }

  def getDeploymentStatusForEnvironment(environment: String, publisherRef: String): Action[AnyContent] = (identify andThen hipEnvironment(environment)).async {
    implicit request =>
      deploymentsService.getDeployment(request.hipEnvironment, publisherRef).map(
        response =>
          Ok(Json.toJson(response))
      )
  }

  def getDeploymentDetails(publisherRef: String): Action[AnyContent] = identify.async {
    implicit request =>
      deploymentsService.getDeploymentDetails(publisherRef)
        .map(handleDeploymentDetailsResult)
  }

  def getDeploymentDetailsForEnvironment(publisherRef: String, environment: String): Action[AnyContent] = (identify andThen hipEnvironment(environment)).async {
    implicit request =>
      deploymentsService.getDeploymentDetails(publisherRef, request.hipEnvironment)
        .map(handleDeploymentDetailsResult)
  }

  private def handleDeploymentDetailsResult(result: Either[ApimException, DeploymentDetails]) =
    result match {
      case Right(deploymentDetails) => Ok(Json.toJson(deploymentDetails))
      case Left(e) if e.issue == ServiceNotFound => NotFound
      case Left(e) => throw e
    }

  def promoteAPI(publisherRef: String): Action[JsValue] = identify.compose(Action(parse.json)).async {
    implicit request =>
      request.body.validate[PromotionRequest] match {
        case JsSuccess(promotionRequest, _) =>
          val (deployFromEnvironment, deployToEnvironment) = (hipEnvironments.forId(promotionRequest.environmentFrom), hipEnvironments.forId(promotionRequest.environmentTo))
          deploymentsService.promoteAPI(publisherRef, deployFromEnvironment, deployToEnvironment, promotionRequest.egress).map {
            case Right(response: SuccessfulDeploymentsResponse) => Ok(Json.toJson(response))
            case Right(response: InvalidOasResponse) => BadRequest(Json.toJson(response))
            case Left(e) if e.issue == ServiceNotFound => NotFound
            case Left(e) => throw e
          }
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
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

  def forcePublish(publisherReference: String): Action[AnyContent] = identify.async {
    implicit request =>
      deploymentsService.forcePublish(publisherReference).map {
        case Right(_) => NoContent
        case Left(e: AutopublishException) if e.issue == DeploymentNotFound => NotFound
        case Left(e) => throw e
      }
  }

}
