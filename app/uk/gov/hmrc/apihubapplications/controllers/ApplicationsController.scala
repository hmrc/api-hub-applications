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
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.requests.UpdateScopeStatus
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.ApplicationsService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationsController @Inject()
  (cc: ControllerComponents, applicationsRepository: ApplicationsRepository, applicationsService: ApplicationsService)
  (implicit ec: ExecutionContext)
  extends BackendController(cc)
  with Logging {

  def registerApplication(): Action[JsValue] = Action(parse.json).async {
    request: Request[JsValue] =>
      request.body.validate[NewApplication] match {
        case JsSuccess(newApp, _) =>
//          applicationsRepository
//            .insert(
//              Application(newApp).assertTeamMember(newApp.createdBy.email)
//            )
          applicationsService.registerApplication(newApp)
            .map(saved => Created(Json.toJson(saved)))
        case e: JsError =>
          logger.info(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

  def getApplications: Action[AnyContent] = Action.async {
    applicationsRepository.findAll().map(apps => Json.toJson(apps)).map(Ok(_))
  }

  def getApplication(id: String): Action[AnyContent] = Action.async {
    applicationsRepository.findById(id)
      .map {
        case Some(application) => Ok(Json.toJson(application))
        case None => NotFound
      }
  }

  def addScopes(id: String): Action[JsValue] = Action(parse.json).async{
    request: Request[JsValue] => {
      val jsReq = request.body
      jsReq.validate[Seq[NewScope]] match {
        case JsSuccess(scopes, _) =>
          applicationsRepository
            .addScopes(id, scopes)
            .map(_ match {
              case Some(true) => NoContent
              case _ => NotFound
            })
        case e: JsError =>
          logger.info(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
    }
  }

  def pendingScopes: Action[AnyContent] = Action.async {
    applicationsRepository.findAll()
      .map(
        applications =>
          applications.filter(_.hasProdPendingScope)
      )
      .map(Json.toJson(_))
      .map(Ok(_))
  }

  def setApprovedScope(id:String,environment:String, scopename:String): Action[JsValue] = Action(parse.json).async {
    request: Request[JsValue] => {
      val jsReq = request.body
      jsReq.validate[UpdateScopeStatus] match {
        case JsSuccess(updateStatus@UpdateScopeStatus(Approved), _) =>
          applicationsRepository
            .setScope(id, environment, scopename, updateStatus).map(_ match {
            case Some(true) => NoContent
            case _ => NotFound
          })
        case JsSuccess(_,_)  => Future.successful(BadRequest)

        case e: JsError =>
          logger.info(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
    }
  }

}
