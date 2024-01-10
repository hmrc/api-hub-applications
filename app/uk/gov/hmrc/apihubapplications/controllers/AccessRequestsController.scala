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
import play.api.mvc.{Action, AnyContent, ControllerComponents, Request}
import uk.gov.hmrc.apihubapplications.controllers.actions.IdentifierAction
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequestDecisionRequest, AccessRequestRequest, AccessRequestStatus}
import uk.gov.hmrc.apihubapplications.models.exception.{AccessRequestNotFoundException, AccessRequestStatusInvalidException, ApplicationNotFoundException}
import uk.gov.hmrc.apihubapplications.services.{AccessRequestsService, ApplicationsService}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AccessRequestsController @Inject()(
  identify: IdentifierAction,
  cc: ControllerComponents,
  accessRequestsService: AccessRequestsService,
  applicationsService: ApplicationsService
)(implicit ec: ExecutionContext) extends BackendController(cc) with Logging {

  def createAccessRequest(): Action[JsValue] = identify.compose(Action(parse.json)).async {
    implicit request: Request[JsValue] =>
      request.body.validate[AccessRequestRequest] match {
        case JsSuccess(accessRequestRequest, _) =>
          accessRequestsService.createAccessRequest(accessRequestRequest).map(_ => Created)
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

  def getAccessRequests(applicationId: Option[String], status: Option[AccessRequestStatus]): Action[AnyContent] = identify.async {
    accessRequestsService.getAccessRequests(applicationId, status).map {
      requests =>
        Ok(Json.toJson(requests))
    }
  }

  def getAccessRequest(id: String): Action[AnyContent] = identify.async {
    accessRequestsService.getAccessRequest(id).map {
      case Some(accessRequest) => Ok(Json.toJson(accessRequest))
      case _ => NotFound
    }
  }

  def approveAccessRequest(id: String): Action[JsValue] = identify.compose(Action(parse.json)).async {
    implicit request: Request[JsValue] =>
      request.body.validate[AccessRequestDecisionRequest] match {
        case JsSuccess(decisionRequest, _) =>
          accessRequestsService.approveAccessRequest(id, decisionRequest, applicationsService).map {
            case Right(_) => NoContent
            case Left(_: AccessRequestNotFoundException) => NotFound
            case Left(_: AccessRequestStatusInvalidException) => Conflict
            case Left(_: ApplicationNotFoundException) => BadRequest
            case Left(e) => throw e
          }
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

  def rejectAccessRequest(id: String): Action[JsValue] = identify.compose(Action(parse.json)).async {
    implicit request: Request[JsValue] =>
      request.body.validate[AccessRequestDecisionRequest] match {
        case JsSuccess(decisionRequest, _) if decisionRequest.rejectedReason.isDefined =>
          accessRequestsService.rejectAccessRequest(id, decisionRequest).map {
            case Right(_) => NoContent
            case Left(_: AccessRequestNotFoundException) => NotFound
            case Left(_: AccessRequestStatusInvalidException) => Conflict
            case Left(e) => throw e
          }
        case JsSuccess(decisionRequest, _) =>
          logger.warn("No rejected reason")
          Future.successful(BadRequest)
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

}
