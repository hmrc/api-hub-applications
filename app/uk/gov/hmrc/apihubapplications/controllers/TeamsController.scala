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
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.apihubapplications.controllers.actions.IdentifierAction
import uk.gov.hmrc.apihubapplications.models.exception.{
  TeamMemberDoesNotExistException,
  TeamMemberExistsException,
  TeamNameNotUniqueException,
  TeamNotFoundException
}
import uk.gov.hmrc.apihubapplications.models.requests.TeamMemberRequest
import uk.gov.hmrc.apihubapplications.models.team.{NewTeam, RenameTeamRequest}
import uk.gov.hmrc.apihubapplications.services.TeamsService
import uk.gov.hmrc.crypto.{ApplicationCrypto, Crypted}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.apihubapplications.models.exception.LastTeamMemberException

@Singleton
class TeamsController @Inject()(
  cc: ControllerComponents,
  identify: IdentifierAction,
  teamsService: TeamsService,
  crypto: ApplicationCrypto
)(implicit ec: ExecutionContext) extends BackendController(cc) with Logging {

  def create(): Action[JsValue] = identify(parse.json).async {
    implicit request =>
      request.body.validate[NewTeam] match {
        case JsSuccess(newTeam, _) =>
          teamsService.create(newTeam).map {
            case Right(team) => Created(Json.toJson(team))
            case Left(_: TeamNameNotUniqueException) => Conflict
            case Left(e) => throw e
          }
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

  def findAll(teamMember: Option[String]): Action[AnyContent] = identify.async {
    teamsService.findAll(teamMember.map(decrypt)).map(
      teams => Ok(Json.toJson(teams))
    )
  }

  def findById(id: String): Action[AnyContent] = identify.async {
    teamsService.findById(id) map {
      case Right(team) => Ok(Json.toJson(team))
      case Left(_: TeamNotFoundException) => NotFound
      case Left(e) => throw e
    }
  }

  def findByName(name: String): Action[AnyContent] = identify.async {
    teamsService.findByName(name) map {
      case Some(team) => Ok(Json.toJson(team))
      case None => NotFound
    }
  }

  def addTeamMember(id: String): Action[JsValue] = identify(parse.json).async {
    implicit request =>
      request.body.validate[TeamMemberRequest] match {
        case JsSuccess(validRequest, _) =>
          teamsService.addTeamMember(id, validRequest).map {
            case Right(_) => NoContent
            case Left(_: TeamNotFoundException) => NotFound
            case Left(_: TeamMemberExistsException) => BadRequest
            case Left(e) => throw e
          }
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

  def removeTeamMember(id: String, encryptedEmail: String): Action[AnyContent] = identify.async {
    implicit request =>
      teamsService.removeTeamMember(id, decrypt(encryptedEmail)).map {
        case Right(_) => NoContent
        case Left(_: LastTeamMemberException) => Conflict
        case Left(_: TeamNotFoundException) => NotFound
        case Left(_: TeamMemberDoesNotExistException) => NotFound
        case Left(e) => throw e
      }
  }

  def renameTeam(id: String): Action[JsValue] = identify(parse.json).async {
    implicit request =>
      request.body.validate[RenameTeamRequest] match {
        case JsSuccess(validRequest, _) =>
          teamsService.renameTeam(id, validRequest).map {
            case Right(_) => NoContent
            case Left(_: TeamNotFoundException) => NotFound
            case Left(_: TeamNameNotUniqueException) => Conflict
            case Left(e) => throw e
          }
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

  private def decrypt(encrypted: String): String = {
    crypto.QueryParameterCrypto.decrypt(Crypted(encrypted)).value
  }

}
