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
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.ExecutionContext

@Singleton
class BadRequestDemoController @Inject()
  (cc: ControllerComponents)
  (implicit ec: ExecutionContext)
  extends BackendController(cc)
  with Logging {

  def success: Action[AnyContent] = Action { _ =>
    println("here!!!!!")
      val hardCodedApp = Application(NewApplication("BadRequestDemoApp", Creator("bad-request-demo-creator@hmrc.gov.uk")))
      val hardCodedAppWithId = hardCodedApp.copy(id = Some("111111111111111111111111"))
      Ok(Json.toJson(hardCodedAppWithId))
  }

  // Future(BadRequest(Json.toJson(ErrorResponse(ApplicationNameNotUnique, "app name not unique"))))

}
