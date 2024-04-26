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

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, IntegrationCatalogueConnector}
import uk.gov.hmrc.apihubapplications.models.api.ApiTeam
import uk.gov.hmrc.apihubapplications.models.apim.{DeploymentsRequest, SuccessfulDeploymentResponse, SuccessfulDeploymentsResponse}
import uk.gov.hmrc.apihubapplications.models.application.Primary
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future

class DeploymentsServiceSpec
  extends AsyncFreeSpec
    with Matchers
    with MockitoSugar
    with ArgumentMatchersSugar {

  import DeploymentsServiceSpec._

  "deployToSecondary" - {
    "must pass the request to APIM and return the response" in {
      val fixture = buildFixture()

      when(fixture.apimConnector.deployToSecondary(any)(any)).thenReturn(Future.successful(Right(deploymentsResponse)))
      when(fixture.integrationCatalogueConnector.linkApiToTeam(any)(any)).thenReturn(Future.successful(()))

      fixture.deploymentsService.deployToSecondary(deploymentsRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Right(deploymentsResponse)
          verify(fixture.apimConnector).deployToSecondary(eqTo(deploymentsRequest))(any)
          succeed
      }
    }

    "must link the API and Team in Integration Catalogue" in {
      val fixture = buildFixture()
      val apiTeam = ApiTeam(deploymentsResponse.id, deploymentsRequest.teamId)

      when(fixture.apimConnector.deployToSecondary(any)(any)).thenReturn(Future.successful(Right(deploymentsResponse)))
      when(fixture.integrationCatalogueConnector.linkApiToTeam(any)(any)).thenReturn(Future.successful(()))

      fixture.deploymentsService.deployToSecondary(deploymentsRequest)(HeaderCarrier()).map {
        _ =>
          verify(fixture.integrationCatalogueConnector).linkApiToTeam(eqTo(apiTeam))(any)
          succeed
      }
    }
  }

  "getDeployment" - {
    "must pass the request to the APIM connector and return the response" in {
      val fixture = buildFixture()
      val publisherRef = "test-publisher-ref"
      val deploymentResponse = SuccessfulDeploymentResponse("test-id")

      when(fixture.apimConnector.getDeployment(any, any)(any)).thenReturn(Future.successful(Right(Some(deploymentResponse))))

      fixture.deploymentsService.getDeployment(publisherRef, Primary)(HeaderCarrier()).map {
        result =>
          result mustBe Right(Some(deploymentResponse))
          verify(fixture.apimConnector).getDeployment(eqTo(publisherRef), eqTo(Primary))(any)
          succeed
      }
    }
  }

  private case class Fixture(
    apimConnector: APIMConnector,
    integrationCatalogueConnector: IntegrationCatalogueConnector,
    deploymentsService: DeploymentsService
  )

  private def buildFixture(): Fixture = {
    val apimConnector = mock[APIMConnector]
    val integrationCatalogueConnector = mock[IntegrationCatalogueConnector]
    val deploymentsService = new DeploymentsService(apimConnector, integrationCatalogueConnector)

    Fixture(apimConnector, integrationCatalogueConnector, deploymentsService)
  }

}

object DeploymentsServiceSpec {

  val deploymentsRequest: DeploymentsRequest = DeploymentsRequest("test-line-of-business", "test-name", "test-description", "test-team-id", "test-egress", "test-oas")
  val deploymentsResponse: SuccessfulDeploymentsResponse = SuccessfulDeploymentsResponse("test-id", "test-version", 42, "test-uri")

}
