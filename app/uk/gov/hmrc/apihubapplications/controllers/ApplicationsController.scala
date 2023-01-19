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
import uk.gov.hmrc.apihubapplications.models.application.{Application, NewApplication}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationsController @Inject()
  (cc: ControllerComponents, applicationsRepository: ApplicationsRepository)
  (implicit ec: ExecutionContext)
  extends BackendController(cc)
  with Logging {

  def createApplication(): Action[JsValue] = Action(parse.json).async {
    request: Request[JsValue] =>
      request.body.validate[NewApplication] match {
        case JsSuccess(newApplication, _) =>
          applicationsRepository
            .insert(Application(newApplication))
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

}
