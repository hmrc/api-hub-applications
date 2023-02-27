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

package uk.gov.hmrc.apihubapplications.testonly

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.errors.{ErrorResponse, InvalidJson, TeamMemberAlreadyExists}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

@Singleton
class BadRequestDemoController @Inject()
  (cc: ControllerComponents)
  extends BackendController(cc)
  with Logging {

  def success: Action[AnyContent] = Action { _ =>
    val hardCodedApp = Application(NewApplication("BadRequestDemoApp", Creator("bad-request-demo-creator@hmrc.gov.uk")))
    val hardCodedAppWithId = hardCodedApp.copy(id = Some("111111111111111111111111"))
    Ok(Json.toJson(hardCodedAppWithId))
  }

  def userError: Action[AnyContent] = Action { _ =>
    BadRequest(Json.toJson(ErrorResponse(TeamMemberAlreadyExists, "The email address of the user already exists in the team members for this application")))
  }

  def serviceError: Action[AnyContent] = Action { _ =>
    BadRequest(Json.toJson(ErrorResponse(InvalidJson, "Required fields missing from request to create an application")))
  }


}
