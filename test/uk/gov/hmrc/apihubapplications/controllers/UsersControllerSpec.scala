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

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.{Application => PlayApplication}
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.application.UserContactDetails
import uk.gov.hmrc.apihubapplications.services.UsersService
import uk.gov.hmrc.apihubapplications.utils.CryptoUtils

import scala.concurrent.Future

class UsersControllerSpec
  extends AnyFreeSpec
  with Matchers
  with MockitoSugar
  with ArgumentMatchersSugar
  with OptionValues
  with CryptoUtils {

  "findAll" - {
    "must return 200 Ok and all users returned by the service" in {
      val fixture = buildFixture()
      val users = Seq(UserContactDetails("user1@example.com"), UserContactDetails("user2@example.com"))

      when(fixture.service.findAll()).thenReturn(Future.successful(users))

      running(fixture.application) {
        val request = FakeRequest(routes.UsersController.findAll())
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(users)
      }
    }
  }

  private case class Fixture(application: PlayApplication, service: UsersService)

  private def buildFixture(): Fixture = {
    val service = mock[UsersService]

    val application = new GuiceApplicationBuilder()
      .overrides(
        bind[ControllerComponents].toInstance(stubControllerComponents()),
        bind[UsersService].toInstance(service),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction])
      )
      .build()

    Fixture(application, service)
  }

}
