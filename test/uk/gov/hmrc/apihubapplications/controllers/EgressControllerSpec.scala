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

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
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
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.api.EgressGateway
import uk.gov.hmrc.apihubapplications.models.apim.ValidateResponse.formatValidateResponse
import uk.gov.hmrc.apihubapplications.models.apim.{FailuresResponse, InvalidOasResponse, SuccessfulValidateResponse}
import uk.gov.hmrc.apihubapplications.models.exception.ApimException
import uk.gov.hmrc.apihubapplications.services.{EgressService, OASService}
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments

import scala.concurrent.Future

class EgressControllerSpec
  extends AnyFreeSpec
    with Matchers
    with MockitoSugar
    with OptionValues {

  "listEgressGateways" - {
    "must return Ok and the egress gateways" in {
      val fixture = buildFixture()

      val expectedResponse = {
        1 until 10 map (i => EgressGateway(s"fake-egress-id-$i", s"Egress Friendly Name $i"))
      }

      when(fixture.egressService.listEgressGateways(eqTo(FakeHipEnvironments.productionEnvironment))(any)).thenReturn(Future.successful(Right(expectedResponse)))

      running(fixture.application) {
        val request = FakeRequest(routes.EgressController.listEgressGateways(FakeHipEnvironments.productionEnvironment.id))

        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(expectedResponse)
      }
    }

    "must return an error when the APIM call returns an error" in {
      val fixture = buildFixture()

      when(fixture.egressService.listEgressGateways(eqTo(FakeHipEnvironments.productionEnvironment))(any))
        .thenReturn(Future.successful(Left(ApimException.unexpectedResponse(500))))

      running(fixture.application) {
        val request = FakeRequest(routes.EgressController.listEgressGateways(FakeHipEnvironments.productionEnvironment.id))

        val result = route(fixture.application, request).value

        status(result) mustBe INTERNAL_SERVER_ERROR
      }
    }
  }

  private case class Fixture(application: Application, egressService: EgressService)

  private def buildFixture(): Fixture = {
    val egressService = mock[EgressService]

    val application = new GuiceApplicationBuilder()
      .overrides(
        bind[ControllerComponents].toInstance(stubControllerComponents()),
        bind[EgressService].toInstance(egressService),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction]),
        bind[HipEnvironments].toInstance(FakeHipEnvironments)
      )
      .build()

    Fixture(application, egressService)
  }

}
