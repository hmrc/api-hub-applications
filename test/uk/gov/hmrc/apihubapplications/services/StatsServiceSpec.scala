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
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, IntegrationCatalogueConnector}
import uk.gov.hmrc.apihubapplications.models.apim.ApiDeployment
import uk.gov.hmrc.apihubapplications.models.application.Primary
import uk.gov.hmrc.apihubapplications.models.stats.ApisInProductionStatistic
import uk.gov.hmrc.apihubapplications.testhelpers.ApiDetailGenerators
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future

class StatsServiceSpec
  extends AsyncFreeSpec
    with Matchers
    with MockitoSugar
    with ApiDetailGenerators
    with EitherValues {

  "apisInProduction" - {
    "must return the correct statistics" in {
      val fixture = buildFixture()

      val deployments = (1 to 5).map(
        i =>
          ApiDeployment(s"test-id-$i", None)
      )

      val apis = (3 to 7).map(
        i =>
          sampleApiDetail().copy(publisherReference = s"test-id-$i")
      )

      when(fixture.apimConnector.getDeployments(eqTo(Primary))(any)).thenReturn(Future.successful(Right(deployments)))
      when(fixture.integrationCatalogueConnector.findHipApis()(any)).thenReturn(Future.successful(Right(apis)))

      val expected = ApisInProductionStatistic(apis.size, 3)

      fixture.statsService.apisInProduction()(HeaderCarrier()).map {
        result =>
          result.value mustBe expected
      }
    }
  }

  "listApisInProduction" - {
    "must return the list of APIs in production" in {
      val fixture = buildFixture()

      val deployments = (1 to 5).map(
        i =>
          ApiDeployment(s"test-id-$i", None)
      )

      val apis = (3 to 7).map(
        i =>
          sampleApiDetail().copy(publisherReference = s"test-id-$i")
      )

      when(fixture.apimConnector.getDeployments(eqTo(Primary))(any)).thenReturn(Future.successful(Right(deployments)))
      when(fixture.integrationCatalogueConnector.findHipApis()(any)).thenReturn(Future.successful(Right(apis)))

      val expected = apis.take(3)

      fixture.statsService.listApisInProduction()(HeaderCarrier()).map {
        result =>
          result.value must contain theSameElementsAs expected
      }
    }
  }

  private case class Fixture(
    apimConnector: APIMConnector,
    integrationCatalogueConnector: IntegrationCatalogueConnector,
    statsService: StatsService
  )

  private def buildFixture(): Fixture = {
    val apimConnector = mock[APIMConnector]
    val integrationCatalogueConnector = mock[IntegrationCatalogueConnector]
    val statsService = StatsService(apimConnector, integrationCatalogueConnector)

    Fixture(apimConnector, integrationCatalogueConnector, statsService)
  }

}
