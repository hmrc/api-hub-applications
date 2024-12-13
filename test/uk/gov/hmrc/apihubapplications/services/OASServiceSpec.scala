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

package uk.gov.hmrc.apihubapplications.services

import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, post, stubFor, urlEqualTo}
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.when
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.connectors.APIMConnector
import uk.gov.hmrc.apihubapplications.models.apim.{FailuresResponse, InvalidOasResponse, SuccessfulValidateResponse, Error as ApimError}
import uk.gov.hmrc.apihubapplications.testhelpers.ApiDetailGenerators
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

class OASServiceSpec
  extends AsyncFreeSpec
    with Matchers
    with MockitoSugar
    with ApiDetailGenerators
    with EitherValues {

  implicit override val executionContext = scala.concurrent.ExecutionContext.Implicits.global

  "validateInPrimary" - {
    "must return a validation result and not validate the title" in {

      val fixture = buildFixture()
      val oas = "some test oas"

      val validResponse = SuccessfulValidateResponse
      when(fixture.apimConnector.validateInPrimary(eqTo(oas))(any)).thenReturn(Future.successful(Right(validResponse)))

      fixture.oasService.validateInPrimary(oas, false)(HeaderCarrier()).map {
        result =>
          result.value mustBe validResponse
      }
    }

    "must return a validation result for a valid oas but with title validation error when title too long" in {
      val fixture = buildFixture()

      val oas = "title: valid oas content but with a title that is much longer than the maximum forty six characters"
      val validResponse = SuccessfulValidateResponse
      val invalidResponse = InvalidOasResponse(FailuresResponse("BAD_REQUEST","oas title is longer than 46 characters", Some(Seq(ApimError("APIM", "Oas title is too long.")))))

      when(fixture.apimConnector.validateInPrimary(eqTo(oas))(any)).thenReturn(Future.successful(Right(validResponse)))

      fixture.oasService.validateInPrimary(oas, true)(HeaderCarrier()).map {
        result =>
          result.value mustBe invalidResponse
      }
    }

    "must return a validation fail result for an invalid oas but with title validation error appended when title too long" in {
      val fixture = buildFixture()

      val oas = "title: valid oas content but with a title that is much longer than the maximum forty six characters"
      val apimErrors = Seq(ApimError("jam", "scones"))
      val invalidOasResponse = InvalidOasResponse(FailuresResponse("cheese", "crackers", Some(apimErrors)))
      val invalidOasAndTitleResponse = InvalidOasResponse(invalidOasResponse.failure.copy(errors = Some(apimErrors ++ Seq(ApimError("APIM", "Oas title is too long.")))))

      when(fixture.apimConnector.validateInPrimary(eqTo(oas))(any)).thenReturn(Future.successful(Right(invalidOasResponse)))

      fixture.oasService.validateInPrimary(oas, true)(HeaderCarrier()).map {
        result =>
          result.value mustBe invalidOasAndTitleResponse
      }
    }

  }

  private case class Fixture(apimConnector: APIMConnector, oasService: OASService)

  private def buildFixture(): Fixture = {
    val apimConnector = mock[APIMConnector]
    val oasService = OASService(apimConnector)

    Fixture(apimConnector, oasService)
  }

}
