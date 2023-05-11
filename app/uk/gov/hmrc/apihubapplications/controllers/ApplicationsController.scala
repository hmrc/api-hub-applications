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
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationBadException, ApplicationNotFoundException, ApplicationsException, IdmsException}
import uk.gov.hmrc.apihubapplications.models.requests.UpdateScopeStatus
import uk.gov.hmrc.apihubapplications.services.ApplicationsService
import uk.gov.hmrc.crypto.{ApplicationCrypto, Crypted}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationsController @Inject()(identify: IdentifierAction,
                                       cc: ControllerComponents,
                                       applicationsService: ApplicationsService,
                                       crypto: ApplicationCrypto
                                      )(implicit ec: ExecutionContext)
  extends BackendController(cc) with Logging {

  def registerApplication(): Action[JsValue] = identify.compose(Action(parse.json)).async {
    implicit request: Request[JsValue] =>
      request.body.validate[NewApplication] match {
        case JsSuccess(newApp, _) =>
          logger.info(s"Registering new application: ${newApp.name}")
          applicationsService.registerApplication(newApp).map {
            case Right(application) => Created(Json.toJson(application))
            case Left(_) => BadGateway
          }
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

  def getApplications(teamMember: Option[String]): Action[AnyContent] = identify.compose(Action).async {
    val result = teamMember match {
      case None => applicationsService.findAll()
      case Some(encryptedEmail) =>
        applicationsService.filter(decrypt(encryptedEmail))
    }

    result.map(apps => Json.toJson(apps)).map(Ok(_))
  }

  def getApplication(id: String): Action[AnyContent] = identify.compose(Action).async {
    implicit request =>
      applicationsService.findById(id)
        .map {
          case Right(application) => Ok(Json.toJson(application))
          case Left(_: IdmsException) => BadGateway
          case Left(_: ApplicationNotFoundException) => NotFound
          case Left(_) => InternalServerError
        }
  }

  def addScopes(id: String): Action[JsValue] = identify.compose(Action(parse.json)).async {
    implicit request: Request[JsValue] => {
      val jsReq = request.body
      jsReq.validate[Seq[NewScope]] match {
        case JsSuccess(scopes, _) =>
          logger.info(s"Adding scopes ($scopes) to application ID: $id")
          scopes match {
            case s if s.size > 1 => Future.successful(NotImplemented)
            case s if s.isEmpty => Future.successful(BadRequest)
            case _ => applicationsService.addScope(id, scopes.head).map {
              case Right(true) => NoContent
              case Left(_: ApplicationNotFoundException) => NotFound
              case Left(_: IdmsException) => BadGateway
              case _ => InternalServerError
            }
          }

        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
    }
  }

  def pendingPrimaryScopes: Action[AnyContent] = identify.compose(Action).async {
    applicationsService.getApplicationsWithPendingPrimaryScope.map(Json.toJson(_)).map(Ok(_))
  }

  def updatePrimaryScopeStatus(id: String, scopeName: String): Action[JsValue] =
    identify.compose(Action(parse.json)).async {
      implicit request: Request[JsValue] => {
        val jsReq = request.body
        jsReq.validate[UpdateScopeStatus] match {
          case JsSuccess(UpdateScopeStatus(Approved), _) =>
            logger.info(s"Setting scope $scopeName to ${Approved.toString} on application ID: $id")
            applicationsService.approvePrimaryScope(id, scopeName).flatMap {
              case Left(_: ApplicationNotFoundException) => Future.successful(NotFound)
              case Left(_: ApplicationBadException) => Future.successful(NotFound)
              case Left(_: IdmsException) => Future.successful(BadGateway)
              case _ => Future.successful(NoContent)
            }
          case JsSuccess(updateStatus, _) =>
            logger.warn(s"Unsupported status: ${updateStatus.status.toString}")
            Future.successful(BadRequest)
          case e: JsError =>
            logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
            Future.successful(BadRequest)
        }
      }

    }

  private def decrypt(encrypted: String): String = {
    crypto.QueryParameterCrypto.decrypt(Crypted(encrypted)).value
  }

  def createPrimarySecret(id: String): Action[AnyContent] = identify.compose(Action).async {
    implicit request =>
      logger.info(s"Creating primary secret for application ID: $id")
      val eventualExceptionOrSecret = applicationsService.createPrimarySecret(id)
      eventualExceptionOrSecret.map {
        case Right(secret) => Ok(Json.toJson(secret))
        case Left(_: IdmsException) => BadGateway
        case Left(_: ApplicationNotFoundException) => NotFound
        case Left(_: ApplicationsException) => BadRequest
      }
  }
}
