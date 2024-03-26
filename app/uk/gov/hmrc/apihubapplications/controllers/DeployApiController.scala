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
import play.api.mvc.{Action, ControllerComponents}
import uk.gov.hmrc.apihubapplications.connectors.APIMConnectorImpl
import uk.gov.hmrc.apihubapplications.controllers.actions.IdentifierAction
import uk.gov.hmrc.apihubapplications.models.exception.ApimException
import uk.gov.hmrc.apihubapplications.models.exception.ApimException.InvalidResponse
import uk.gov.hmrc.apihubapplications.models.simpleapideployment.{GenerateRequest, InvalidOasResponse, SuccessfulGenerateResponse}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeployApiController @Inject()(identify: IdentifierAction,
                                    cc: ControllerComponents,
                                    simpleApiDeploymentConnector: APIMConnectorImpl)(implicit ec: ExecutionContext)
  extends BackendController(cc) with Logging {

  def generate: Action[JsValue] = identify.compose(Action(parse.json)).async {
    implicit request =>
      val jsReq = request.body
      jsReq.validate[GenerateRequest] match {
        case JsSuccess(generateRequest, _) => simpleApiDeploymentConnector.generateSecondary(generateRequest) map {
          case Right(response: InvalidOasResponse) => BadRequest(Json.toJson(response))
          case Right(response: SuccessfulGenerateResponse) => Ok(Json.toJson(response))
          case Left(e: ApimException) if e.issue equals InvalidResponse => BadRequest
          case Left(_) => InternalServerError
        }
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }
}
