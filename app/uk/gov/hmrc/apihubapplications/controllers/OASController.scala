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
import play.api.libs.json.{Format, JsError, JsSuccess, JsValue, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents, Request}
import uk.gov.hmrc.apihubapplications.controllers.actions.IdentifierAction
import uk.gov.hmrc.apihubapplications.models.apim.{InvalidOasResponse, SuccessfulValidateResponse}
import uk.gov.hmrc.apihubapplications.models.apim.ValidateResponse
import uk.gov.hmrc.apihubapplications.services.OASService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class OASController @Inject()(
  cc: ControllerComponents,
  identify: IdentifierAction,
  oasService: OASService
)(implicit ec: ExecutionContext) extends BackendController(cc) with Logging {

  private implicit val formatValidateResponse: Format[ValidateResponse] = ValidateResponse.formatValidateResponse

  def validateOAS(): Action[AnyContent] = identify.async {
    implicit request =>
      request.body.asText.map(oasYaml =>
        oasService.validateInPrimary(oasYaml)
          .map(_.fold(
            e => InternalServerError(e.message),
            {
              case invalidResponse: InvalidOasResponse =>
                BadRequest(Json.toJson(invalidResponse)(formatValidateResponse))
              case validResponse =>
                Ok(Json.toJson(validResponse))
            }
          ))
      ).getOrElse(Future.successful(BadRequest))
  }

}
