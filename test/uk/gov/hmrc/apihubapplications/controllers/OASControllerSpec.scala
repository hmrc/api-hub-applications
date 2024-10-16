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

import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.when
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Application
import play.api.http.Status.{BAD_REQUEST, INTERNAL_SERVER_ERROR}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.apim.{FailuresResponse, InvalidOasResponse, SuccessfulValidateResponse}
import uk.gov.hmrc.apihubapplications.models.apim.ValidateResponse.formatValidateResponse
import uk.gov.hmrc.apihubapplications.models.exception.ApimException
import uk.gov.hmrc.apihubapplications.services.OASService

import scala.concurrent.Future

class OASControllerSpec
  extends AnyFreeSpec
    with Matchers
    with MockitoSugar
    with OptionValues {

  "validateOAS" - {
    "must return Ok when the OAS is valid" in {
      val fixture = buildFixture()
      val oas = "valid oas"
      val validResult = SuccessfulValidateResponse
      val expectedResult = Json.toJson(validResult)(formatValidateResponse)

      when(fixture.oasService.validateInPrimary(eqTo(oas))(any)).thenReturn(Future.successful(Right(validResult)))

      running(fixture.application) {
        val request = FakeRequest(routes.OASController.validateOAS())
          .withBody(oas)

        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe expectedResult
      }
    }

    "must return BadRequest when the OAS is invalid" in {
      val fixture = buildFixture()
      val oas = "invalid oas"
      val invalidResult = InvalidOasResponse(FailuresResponse("code", "reason", None))
      val expectedResult = Json.toJson(invalidResult)(formatValidateResponse)

      when(fixture.oasService.validateInPrimary(eqTo(oas))(any))
        .thenReturn(Future.successful(Right(invalidResult)))

      running(fixture.application) {
        val request = FakeRequest(routes.OASController.validateOAS())
          .withBody(oas)

        val result = route(fixture.application, request).value

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe expectedResult
      }
    }

    "must return an error when the APIM call returns an error" in {
      val fixture = buildFixture()
      val oas = "invalid oas"

      when(fixture.oasService.validateInPrimary(eqTo(oas))(any))
        .thenReturn(Future.successful(Left(ApimException.unexpectedResponse(500))))

      running(fixture.application) {
        val request = FakeRequest(routes.OASController.validateOAS())
          .withBody(oas)

        val result = route(fixture.application, request).value

        status(result) mustBe INTERNAL_SERVER_ERROR
      }
    }

    "must return BadRequest when the request is invalid" in {
      val fixture = buildFixture()

      running(fixture.application) {
        val request = FakeRequest(routes.OASController.validateOAS())
          .withBody(Json.parse("""["badrequest"]"""))

        val result = route(fixture.application, request).value

        status(result) mustBe BAD_REQUEST
      }
    }
  }

  private case class Fixture(application: Application, oasService: OASService)

  private def buildFixture(): Fixture = {
    val oasService = mock[OASService]

    val application = new GuiceApplicationBuilder()
      .overrides(
        bind[ControllerComponents].toInstance(stubControllerComponents()),
        bind[OASService].toInstance(oasService),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction])
      )
      .build()

    Fixture(application, oasService)
  }

}
