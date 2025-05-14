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

package uk.gov.hmrc.apihubapplications

import com.github.tomakehurst.wiremock.client.WireMock.*
import org.mockito.Mockito.when
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import play.api.http.Status.*
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.config.AppConfig
import uk.gov.hmrc.apihubapplications.connectors.{IntegrationCatalogueConnector, IntegrationCatalogueConnectorImpl}
import uk.gov.hmrc.apihubapplications.models.api.ApiTeam
import uk.gov.hmrc.apihubapplications.models.exception.{ApiNotFoundException, IntegrationCatalogueException}
import uk.gov.hmrc.apihubapplications.testhelpers.ApiDetailGenerators
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

class IntegrationCatalogueConnectorSpec
  extends AsyncFreeSpec
    with Matchers
    with WireMockSupport
    with HttpClientV2Support
    with MockitoSugar
    with ApiDetailGenerators
    with EitherValues {

  import IntegrationCatalogueConnectorSpec.*

  "IntegrationCatalogueConnector.linkApiToTeam" - {
    "must place the correct request to Integration Catalogue and return success" in {
      stubFor(
        post(urlEqualTo("/integration-catalogue/apis/team"))
          .withHeader("Content-Type", equalTo("application/json"))
          .withHeader("Authorization", equalTo(appAuthToken))
          .withRequestBody(equalToJson(Json.toJson(apiTeam).toString()))
          .willReturn(
            aResponse()
              .withStatus(NO_CONTENT)
          )
      )

      buildConnector().linkApiToTeam(apiTeam)(HeaderCarrier()).map {
        result =>
          result mustBe Right(())
      }
    }

    "must fail when Integration Catalogue returns a non-success result" in {
      stubFor(
        post(urlEqualTo("/integration-catalogue/apis/team"))
          .willReturn(
            aResponse()
              .withStatus(BAD_REQUEST)
          )
      )

      buildConnector().linkApiToTeam(apiTeam)(HeaderCarrier()).map {
        result =>
          result mustBe Left(IntegrationCatalogueException.unexpectedResponse(BAD_REQUEST))
      }
    }
  }

  "IntegrationCatalogueConnector.findById" - {
    "must place the correct request to Integration Catalogue and return the API when found" in {
      val apiDetail = sampleApiDetail()

      stubFor(
        get(urlEqualTo(s"/integration-catalogue/integrations/${apiDetail.id}"))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("Authorization", equalTo(appAuthToken))
          .willReturn(
            aResponse()
              .withBody(Json.toJson(apiDetail).toString())
          )
      )

      buildConnector().findById(apiDetail.id)(HeaderCarrier()).map {
        result =>
          result.value mustBe apiDetail
      }
    }

    "must return ApiNotFoundException when the API cannot be found" in {
      val apiId = "test-api-id"

      stubFor(
        get(urlEqualTo(s"/integration-catalogue/integrations/$apiId"))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )

      buildConnector().findById(apiId)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApiNotFoundException.forId(apiId))
      }
    }
  }

  "findByPublisherRef" - {
    "must place the correct request to Integration Catalogue and return the API when found" in {
      val apiDetail = sampleApiDetail()

      stubFor(
        get(urlEqualTo(s"/integration-catalogue/integrations/publisher-reference/${apiDetail.publisherReference}"))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("Authorization", equalTo(appAuthToken))
          .willReturn(
            aResponse()
              .withBody(Json.toJson(apiDetail).toString())
          )
      )

      buildConnector().findByPublisherRef(apiDetail.publisherReference)(HeaderCarrier()).map {
        result =>
          result.value mustBe apiDetail
      }
    }

    "must return ApiNotFoundException when the API cannot be found" in {
      val publisherRef = "test-publisher-ref"

      stubFor(
        get(urlEqualTo(s"/integration-catalogue/integrations/publisher-reference/$publisherRef"))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )

      buildConnector().findByPublisherRef(publisherRef)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApiNotFoundException.forPublisherRef(publisherRef))
      }
    }
  }

  "updateApiTeam" - {
    val teamId = "team1"
    val apiId = "apiId"
    val apiDetails = sampleApiDetail().copy(id = apiId, teamId = Some(teamId))

    "must place the correct request and return an ApiDetail" in {
      stubFor(
        put(urlEqualTo(s"/integration-catalogue/apis/$apiId/teams/$teamId"))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("Authorization", equalTo(appAuthToken))
          .willReturn(
            aResponse()
              .withBody(Json.toJson(apiDetails).toString())
          )
      )

      buildConnector().updateApiTeam(apiId, teamId)(HeaderCarrier()) map {
        actual =>
          actual mustBe Right(())
      }
    }

    "must fail with an exception when integration catalogue returns a failure response" in {
      stubFor(
        put(urlEqualTo(s"/integration-catalogue/apis/${apiDetails.id}/teams/$teamId"))
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
          )
      )

      buildConnector().updateApiTeam(apiDetails.id, teamId)(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(IntegrationCatalogueException.unexpectedResponse(500))
      }
    }

    "must handle and return 404 not found when 404 returned from integrations catalogue call" in {
      stubFor(
        put(urlEqualTo(s"/integration-catalogue/apis/${apiDetails.id}/teams/$teamId"))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )

      buildConnector().updateApiTeam(apiDetails.id, teamId)(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApiNotFoundException.forId(s"${apiDetails.id}"))
      }
    }
  }

  "removeApiTeam" - {
    val teamId = "team1"
    val apiId = "apiId"
    val apiDetails = sampleApiDetail().copy(id = apiId, teamId = Some(teamId))

    "must place the correct request and return an ApiDetail" in {
      stubFor(
        delete(urlEqualTo(s"/integration-catalogue/apis/$apiId/teams"))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("Authorization", equalTo(appAuthToken))
          .willReturn(
            aResponse()
              .withStatus(OK)
          )
      )

      buildConnector().removeApiTeam(apiId)(HeaderCarrier()) map {
        actual =>
          actual mustBe Right(())
      }
    }

    "must fail with an exception when integration catalogue returns a failure response" in {
      stubFor(
        delete(urlEqualTo(s"/integration-catalogue/apis/${apiDetails.id}/teams"))
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
          )
      )

      buildConnector().removeApiTeam(apiDetails.id)(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(IntegrationCatalogueException.unexpectedResponse(500))
      }
    }

    "must handle and return 404 not found when 404 returned from integrations catalogue call" in {
      stubFor(
        delete(urlEqualTo(s"/integration-catalogue/apis/${apiDetails.id}/teams"))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )

      buildConnector().removeApiTeam(apiDetails.id)(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApiNotFoundException.forId(s"${apiDetails.id}"))
      }
    }
  }

  "findApis" - {
    "must place the correct request and return the list of ApiDetailSummary" in {
      val apis = (1 to 3).map(_ => sampleApiDetail().toSummary)

      stubFor(
        get(urlEqualTo("/integration-catalogue/integrations/summaries?platformFilter=HIP"))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("Authorization", equalTo(appAuthToken))
          .willReturn(
            aResponse()
              .withBody(Json.toJson(apis).toString())
          )
      )

      buildConnector().findHipApis()(HeaderCarrier()).map {
        result =>
          result.value mustBe apis
      }
    }

    "must fail with an exception when integration catalogue returns a failure response" in {
      stubFor(
        get(urlEqualTo("/integration-catalogue/integrations/summaries?platformFilter=HIP"))
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
          )
      )

      buildConnector().findHipApis()(HeaderCarrier()).map {
        result =>
          result.left.value mustBe IntegrationCatalogueException.unexpectedResponse(INTERNAL_SERVER_ERROR)
      }
    }
  }

  private def buildConnector(): IntegrationCatalogueConnector = {
    val servicesConfig = new ServicesConfig(
      Configuration.from(
        Map(
          "microservice.services.integration-catalogue.host" -> wireMockHost,
          "microservice.services.integration-catalogue.port" -> wireMockPort,
        )
      )
    )

    val appConfig = mock[AppConfig]
    when(appConfig.appAuthToken).thenReturn(appAuthToken)

    new IntegrationCatalogueConnectorImpl(servicesConfig, httpClientV2, appConfig)
  }

}

object IntegrationCatalogueConnectorSpec {

  val appAuthToken = "test-auth-token"
  val apiTeam: ApiTeam = ApiTeam("test-publisher-reference", "test-team-id")

}
