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

import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.testhelpers.ApiDetailGenerators

class ConfigControllerSpec
  extends AnyFreeSpec
    with Matchers
    with MockitoSugar
    with OptionValues
    with ApiDetailGenerators {

  "listEnvironments" - {
    "must return Ok and the hip environments" in {
      val fixture = buildFixture()

      val hipEnvironments = fixture.application.injector.instanceOf[HipEnvironments]
      
      running(fixture.application) {
        val request = FakeRequest(routes.ConfigController.listEnvironments())
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(hipEnvironments.asShareableEnvironments())
      }
    }
  }

  private case class Fixture(application: Application)

  private def buildFixture(): Fixture = {
    val application = new GuiceApplicationBuilder()
      .overrides(
        bind[ControllerComponents].toInstance(stubControllerComponents()),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction])
      )
      .build()

    Fixture(application)
  }

}
