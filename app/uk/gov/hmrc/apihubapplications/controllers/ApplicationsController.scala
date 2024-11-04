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
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.exception._
import uk.gov.hmrc.apihubapplications.models.requests.{AddApiRequest, TeamMemberRequest, UserEmail}
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
            case Right(application) => Created(Json.toJson(application.makePublic()))
            case Left(_: IdmsException) => BadGateway
            case Left(_) => InternalServerError
          }
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

  def getApplications(teamMember: Option[String], includeDeleted: Boolean): Action[AnyContent] = identify.compose(Action).async {
    applicationsService
      .findAll(teamMember.map(decrypt), includeDeleted)
      .map(applications => Json.toJson(applications.map(_.makePublic())))
      .map(Ok(_))
  }

  def getApplicationsUsingApi(apiId: String, includeDeleted: Boolean): Action[AnyContent] = identify.compose(Action).async {
    applicationsService
      .findAllUsingApi(apiId, includeDeleted)
      .map(applications => Json.toJson(applications.map(_.makePublic())))
      .map(Ok(_))
  }

  def getApplication(id: String, enrich: Boolean, includeDeleted: Boolean): Action[AnyContent] = identify.compose(Action).async {
    implicit request =>
      applicationsService.findById(id, enrich, includeDeleted)
        .map {
          case Right(application) => Ok(Json.toJson(application.makePublic()))
          case Left(_: ApplicationNotFoundException) => NotFound
          case Left(_: IdmsException) => BadGateway
          case _ => InternalServerError
        }
  }

  def deleteApplication(id: String): Action[JsValue] = identify.compose(Action(parse.json)).async {
    implicit request =>

      request.body.validate[UserEmail] match {
        case JsSuccess(userEmail, _) =>
          applicationsService.delete(id, userEmail.userEmail).map {
            case Right(()) => NoContent
            case Left(_: ApplicationNotFoundException) => NotFound
            case Left(_: IdmsException) => BadGateway
            case _ => InternalServerError
          }
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

  private def decrypt(encrypted: String): String = {
    crypto.QueryParameterCrypto.decrypt(Crypted(encrypted)).value
  }

  def addApi(id: String): Action[JsValue] = {
    identify.compose(Action(parse.json)).async {
      implicit request: Request[JsValue] => {
        val jsReq = request.body
        jsReq.validate[AddApiRequest] match {
          case JsSuccess(api, _) =>
            logger.info(s"Adding api $api to application ID: $id")
            applicationsService.addApi(id, api).map {
              case Right(_) => NoContent
              case Left(_: ApplicationNotFoundException) => NotFound
              case Left(_: IdmsException) => BadGateway
              case Left(_) => InternalServerError
            }
          case e: JsError =>
            logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
            Future.successful(BadRequest)
        }
      }
    }
  }

  def removeApi(applicationId: String, apiId: String): Action[AnyContent] = identify.async {
    implicit request =>
      applicationsService.removeApi(applicationId, apiId).map {
        case Right(_) => NoContent
        case Left(_: ApplicationNotFoundException) => NotFound
        case Left(_: ApiNotFoundException) => NotFound
        case Left(_: IdmsException) => BadGateway
        case Left(e) => throw e
      }
  }

  def changeOwningTeam(applicationId: String, teamId: String): Action[AnyContent] = identify.async {
    implicit request =>
      applicationsService.changeOwningTeam(applicationId, teamId).map {
        case Right(_) => NoContent
        case Left(_: ApplicationNotFoundException) => NotFound
        case Left(_: TeamNotFoundException) => NotFound
        case Left(_: IdmsException) => BadGateway
        case Left(e) => throw e
      }
  }

  def addCredential(applicationId: String, environmentName: EnvironmentName): Action[AnyContent] = identify.compose(Action).async {
    implicit request =>
      applicationsService.addCredential(applicationId, environmentName).map {
        case Right(credential) => Created(Json.toJson(credential))
        case Left(_: ApplicationNotFoundException) => NotFound
        case Left(_: ApplicationCredentialLimitException) => Conflict
        case Left(_: IdmsException) => BadGateway
        case Left(_) => InternalServerError
      }
  }

  def deleteCredential(applicationId: String, environmentName: EnvironmentName, clientId: String): Action[AnyContent] = identify.compose(Action).async {
    implicit request =>
      applicationsService.deleteCredential(applicationId, environmentName, clientId).map {
        case Right(_) => NoContent
        case Left(_: ApplicationNotFoundException) => NotFound
        case Left(_: CredentialNotFoundException) => NotFound
        case Left(_: ApplicationCredentialLimitException) => Conflict
        case Left(_: IdmsException) => BadGateway
        case Left(_) => InternalServerError
      }
  }

  def addTeamMember(applicationId: String): Action[JsValue] = identify.compose(Action(parse.json)).async {
    implicit request: Request[JsValue] =>
      logger.info(s"Adding team member to application $applicationId")
      request.body.validate[TeamMemberRequest] match {
        case JsSuccess(teamMemberRequest, _) =>
          applicationsService.addTeamMember(applicationId, teamMemberRequest.toTeamMember).map {
            case Right(_) => NoContent
            case Left(_: ApplicationNotFoundException) => NotFound
            case Left(_: TeamMemberExistsException) => BadRequest
            case Left(_: ApplicationTeamMigratedException) => Conflict
            case Left(_) => InternalServerError
          }
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

  def removeTeam(applicationId: String): Action[AnyContent] = identify.compose(Action).async {
    implicit request =>
      applicationsService.removeOwningTeamFromApplication(applicationId).map {
        case Right(()) => NoContent
        case Left(e: ApplicationNotFoundException) => NotFound
        case Left(_) => InternalServerError
      }
  }

  def fetchAllScopes(applicationId: String): Action[AnyContent] = identify.compose(Action).async {
    implicit request =>
      applicationsService.fetchAllScopes(applicationId).map {
        case Right(environmentScopes) => Ok(Json.toJson(environmentScopes))
        case Left(e: ApplicationNotFoundException) => NotFound
        case Left(e) => throw e
      }
  }

}
