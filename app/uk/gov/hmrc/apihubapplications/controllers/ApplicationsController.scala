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
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.requests.UpdateScopeStatus
import uk.gov.hmrc.apihubapplications.services.ApplicationsService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationsController @Inject() (identify: IdentifierAction,
                                        cc: ControllerComponents,
                                        applicationsService: ApplicationsService) (implicit ec: ExecutionContext)
  extends BackendController(cc) with Logging {

  def registerApplication(): Action[JsValue] = identify.compose(Action(parse.json)).async {
    request: Request[JsValue] =>
      request.body.validate[NewApplication] match {
        case JsSuccess(newApp, _) =>
          applicationsService.registerApplication(newApp)
            .map(saved => Created(Json.toJson(saved)))
        case e: JsError =>
          logger.info(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

  def getApplications: Action[AnyContent] = identify.compose(Action).async {
    applicationsService.findAll().map(apps => Json.toJson(apps)).map(Ok(_))
  }

  def getApplication(id: String): Action[AnyContent] = identify.compose(Action).async {
    applicationsService.findById(id)
      .map {
        case Some(application) => Ok(Json.toJson(application))
        case None => NotFound
      }
  }

  def addScopes(id: String): Action[JsValue] = identify.compose(Action(parse.json)).async {
    request: Request[JsValue] => {
      val jsReq = request.body
      jsReq.validate[Seq[NewScope]] match {
        case JsSuccess(scopes, _) =>
          applicationsService.addScopes(id, scopes).map(_ match {
            case Some(true) => NoContent
            case _ => NotFound
          })
        case e: JsError =>
          logger.info(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
    }
  }

  def pendingScopes: Action[AnyContent] = identify.compose(Action).async {
    applicationsService.getApplicationsWithPendingScope().map(Json.toJson(_)).map(Ok(_))
  }

  def approveProdScopeStatus(id: String, environment: String, scopename: String): Action[JsValue] =
    identify.compose(Action(parse.json)).async {
      request: Request[JsValue] => {
        val jsReq = request.body
        if (environment != Prod.toString) {
          Future.successful(BadRequest)
        } else {
          jsReq.validate[UpdateScopeStatus] match {
            case JsSuccess(UpdateScopeStatus(Approved), _) if environment == Prod.toString =>
              applicationsService.setPendingProdScopeStatusToApproved(id, scopename).map(_ match {
                case Some(true) => NoContent
                case _ => NotFound
              })
            case JsSuccess(updateStatus, _) =>
              logger.info(s"Setting scope status to: ${updateStatus.status.toString} on environment: $environment is not allowed")
              Future.successful(BadRequest)

            case e: JsError =>
              logger.info(s"Error parsing request body: ${JsError.toJson(e)}")
              Future.successful(BadRequest)
          }
        }
      }
    }
}
