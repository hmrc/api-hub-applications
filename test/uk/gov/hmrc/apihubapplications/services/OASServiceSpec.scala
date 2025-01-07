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

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.when
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.connectors.APIMConnector
import uk.gov.hmrc.apihubapplications.models.apim.SuccessfulValidateResponse
import uk.gov.hmrc.apihubapplications.testhelpers.ApiDetailGenerators
import uk.gov.hmrc.http.HeaderCarrier
import scala.concurrent.Future

class OASServiceSpec
  extends AsyncFreeSpec
    with Matchers
    with MockitoSugar
    with ApiDetailGenerators
    with EitherValues {
  "validateInPrimary" - {
    "must return a validation result" in {
      val fixture = buildFixture()
      val oas = "valid oas content"
      val validResponse = SuccessfulValidateResponse
      when(fixture.apimConnector.validateInPrimary(eqTo(oas))(any)).thenReturn(Future.successful(Right(validResponse)))
      fixture.oasService.validateInPrimary(oas)(HeaderCarrier()).map {
        result =>
          result.value mustBe validResponse
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
