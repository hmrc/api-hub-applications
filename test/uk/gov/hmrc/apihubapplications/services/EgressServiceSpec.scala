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
import uk.gov.hmrc.apihubapplications.models.api.EgressGateway
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future

class EgressServiceSpec
  extends AsyncFreeSpec
    with Matchers
    with MockitoSugar
    with EitherValues {

  "listEgressGateways" - {
    "must return a egress gateways" in {
      val fixture = buildFixture()

      val expectedResponse = {1 until 10 map(i => EgressGateway(s"fake-egress-id-$i", s"Egress Friendly Name $i"))}
      when(fixture.apimConnector.listEgressGateways(eqTo(FakeHipEnvironments.productionEnvironment))(any)).thenReturn(Future.successful(Right(expectedResponse)))

      fixture.egressService.listEgressGateways(FakeHipEnvironments.productionEnvironment)(HeaderCarrier()).map {
        result =>
          result.value mustBe expectedResponse
      }
    }
  }

  private case class Fixture(apimConnector: APIMConnector, egressService: EgressService)

  private def buildFixture(): Fixture = {
    val apimConnector = mock[APIMConnector]
    val egressService = EgressService(apimConnector)

    Fixture(apimConnector, egressService)
  }

}
