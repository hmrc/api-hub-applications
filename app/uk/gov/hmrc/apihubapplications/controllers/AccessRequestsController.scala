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
import play.api.libs.json.{JsError, JsSuccess, JsValue}
import play.api.mvc.{Action, ControllerComponents, Request}
import uk.gov.hmrc.apihubapplications.controllers.actions.IdentifierAction
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestRequest
import uk.gov.hmrc.apihubapplications.services.AccessRequestsService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AccessRequestsController @Inject()(
  identify: IdentifierAction,
  cc: ControllerComponents,
  accessRequestsService: AccessRequestsService
)(implicit ec: ExecutionContext) extends BackendController(cc) with Logging {

  def createAccessRequest(): Action[JsValue] = identify.compose(Action(parse.json)).async {
    implicit request: Request[JsValue] =>
      request.body.validate[AccessRequestRequest] match {
        case JsSuccess(request, _) =>
          accessRequestsService.createAccessRequest(request).map(_ => Created)
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

}
