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

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.Configuration
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, APIMConnectorImpl}
import uk.gov.hmrc.apihubapplications.models.application.{Primary, Secondary}
import uk.gov.hmrc.apihubapplications.models.exception.ApimException
import uk.gov.hmrc.apihubapplications.models.apim._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.time.LocalDateTime
import java.util.Base64

class APIMConnectorSpec
  extends AsyncFreeSpec
  with Matchers
  with WireMockSupport
  with HttpClientV2Support
  with EitherValues {

  private val oas = "test-oas-document"
  private val primaryClientId = "test-client-id-primary"
  private val secondaryClientId = "test-client-id-secondary"
  private val primarySecret = "test-secret-primary"
  private val secondarySecret = "test-secret-secondary"
  private val primaryPath = "test-path-primary"
  private val secondaryPath = "test-path-secondary"

  private val authorizationTokenPrimary = {
    val encoded = Base64.getEncoder.encodeToString(s"$primaryClientId:$primarySecret".getBytes("UTF-8"))
    s"Basic $encoded"
  }

  private val authorizationTokenSecondary = {
    val encoded = Base64.getEncoder.encodeToString(s"$secondaryClientId:$secondarySecret".getBytes("UTF-8"))
    s"Basic $encoded"
  }

  private val deploymentsRequest = DeploymentsRequest(
    lineOfBusiness = "test-line-of-business",
    name = "test-name",
    description = "test-description",
    egress = "test-egress",
    oas = oas
  )

  "APIMConnector.validatePrimary" - {
    "must place the correct request to the Simple API Deployment service" in {
      stubFor(
        post(urlEqualTo(s"/$primaryPath/v1/simple-api-deployment/validate"))
          .withHeader("Content-Type", equalTo("application/yaml"))
          .withHeader("Authorization", equalTo(authorizationTokenPrimary))
          .withRequestBody(equalTo(oas))
          .willReturn(
            aResponse()
          )
      )

      buildConnector().validateInPrimary(oas)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(SuccessfulValidateResponse)
      }
    }

    "must return OAS validation failures when returned from the Simple API Deployment service" in {
      val failures = Seq(
        ValidationFailure("test-type-1", "test-message-1"),
        ValidationFailure("test-type-2", "test-message-2")
      )

      stubFor(
        post(urlEqualTo(s"/$primaryPath/v1/simple-api-deployment/validate"))
          .willReturn(
            aResponse()
              .withStatus(400)
              .withBody(Json.toJson(failures).toString())
          )
      )

      buildConnector().validateInPrimary(oas)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(InvalidOasResponse(failures))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns Bad Request but no errors" in {
      stubFor(
        post(urlEqualTo(s"/$primaryPath/v1/simple-api-deployment/validate"))
          .willReturn(
            aResponse()
              .withStatus(400)
          )
      )

      buildConnector().validateInPrimary(oas)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(ApimException.unexpectedResponse(400))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns one" in {
      stubFor(
        post(urlEqualTo(s"/$primaryPath/v1/simple-api-deployment/validate"))
          .willReturn(
            aResponse()
              .withStatus(500)
          )
      )

      buildConnector().validateInPrimary(oas)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(ApimException.unexpectedResponse(500))
      }
    }
  }

  "APIMConnector.deploymentsSecondary" - {
    "must place the correct request to the Simple API Deployment service in the secondary environment and return the response" in {
      val response = SuccessfulDeploymentsResponse(
        id = "test-id",
        version = "v1.2.3",
        mergeRequestIid = 201,
        uri = "test-uri"
      )

      stubFor(
        post(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments"))
          .withHeader("Content-Type", containing("multipart/form-data"))
          .withHeader("Authorization", equalTo(authorizationTokenSecondary))
          .withHeader("Accept", equalTo("application/json"))
          .withMultipartRequestBody(
            aMultipart()
              .withName("metadata")
              .withBody(equalToJson(Json.toJson(DeploymentsMetadata(deploymentsRequest)).toString()))
          )
          .withMultipartRequestBody(
            aMultipart()
              .withName("openapi")
              .withBody(equalTo(oas))
          )
          .willReturn(
            aResponse()
              .withBody(Json.toJson(response).toString())
          )
      )

      buildConnector().deployToSecondary(deploymentsRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(response)
      }
    }

    "must return invalid response when the Simple API Deployment service's response cannot be parsed'" in {
      stubFor(
        post(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments"))
          .willReturn(
            aResponse()
              .withBody("{}")
          )
      )

      buildConnector().deployToSecondary(deploymentsRequest)(HeaderCarrier()).map {
        actual =>
          actual.left.value.issue mustBe ApimException.InvalidResponse
      }
    }

    "must return OAS validation failures when returned from the Simple API Deployment service" in {
      val failures = Seq(
        ValidationFailure("test-type-1", "test-message-1"),
        ValidationFailure("test-type-2", "test-message-2")
      )

      stubFor(
        post(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments"))
          .willReturn(
            aResponse()
              .withStatus(400)
              .withBody(Json.toJson(failures).toString())
          )
      )

      buildConnector().deployToSecondary(deploymentsRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(InvalidOasResponse(failures))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns Bad Request but no errors" in {
      stubFor(
        post(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments"))
          .willReturn(
            aResponse()
              .withStatus(400)
          )
      )

      buildConnector().deployToSecondary(deploymentsRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(ApimException.unexpectedResponse(400))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns one" in {
      stubFor(
        post(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments"))
          .willReturn(
            aResponse()
              .withStatus(500)
          )
      )

      buildConnector().deployToSecondary(deploymentsRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(ApimException.unexpectedResponse(500))
      }
    }

    "APIMConnector.getDeployment" - {
      "must place the correct request to the APIM in primary" in {
        val response = SuccessfulDeploymentResponse("publisher_ref", LocalDateTime.now)
        stubFor(
          get(urlEqualTo(s"/$primaryPath/v1/oas-deployments/publisher_ref"))
            .withHeader("Authorization", equalTo(authorizationTokenPrimary))
            .willReturn(
              aResponse()
                .withBody(Json.toJson(response).toString())
                .withStatus(200)
            )
        )

        buildConnector().getDeployment("publisher_ref", Primary)(HeaderCarrier()).map {
          actual =>
            actual mustBe Right(Some(response))
        }
      }

      "must place the correct request to the APIM in secondary" in {
        val response = SuccessfulDeploymentResponse("publisher_ref", LocalDateTime.now)
        stubFor(
          get(urlEqualTo(s"/$secondaryPath/v1/oas-deployments/publisher_ref"))
            .withHeader("Authorization", equalTo(authorizationTokenSecondary))
            .willReturn(
              aResponse()
                .withBody(Json.toJson(response).toString())
                .withStatus(200)
            )
        )

        buildConnector().getDeployment("publisher_ref", Secondary)(HeaderCarrier()).map {
          actual =>
            actual mustBe Right(Some(response))
        }
      }

      "must handle 404 in primary" in {
        stubFor(
          get(urlEqualTo(s"/$primaryPath/v1/oas-deployments/publisher_ref"))
            .withHeader("Authorization", equalTo(authorizationTokenPrimary))
            .willReturn(
              aResponse()
                .withStatus(404)
            )
        )

        buildConnector().getDeployment("publisher_ref", Primary)(HeaderCarrier()).map {
          actual =>
            actual mustBe Right(None)
        }
      }

      "must handle 404 in secondary" in {
        stubFor(
          get(urlEqualTo(s"/$secondaryPath/v1/oas-deployments/publisher_ref"))
            .withHeader("Authorization", equalTo(authorizationTokenSecondary))
            .willReturn(
              aResponse()
                .withStatus(404)
            )
        )

        buildConnector().getDeployment("publisher_ref", Secondary)(HeaderCarrier()).map {
          actual =>
            actual mustBe Right(None)
        }
      }

      "must return unexpected response when APIM returns one" in {
        stubFor(
          get(urlEqualTo(s"/$primaryPath/v1/oas-deployments/publisher_ref"))
            .willReturn(
              aResponse()
                .withStatus(500)
            )
        )

        buildConnector().getDeployment("publisher_ref", Primary)(HeaderCarrier()).map {
          actual =>
            actual mustBe Left(ApimException.unexpectedResponse(500))
        }
      }

    }
  }

  private def buildConnector(): APIMConnector = {
    val servicesConfig = new ServicesConfig(
      Configuration.from(Map(
        "microservice.services.apim-primary.host" -> wireMockHost,
        "microservice.services.apim-primary.port" -> wireMockPort,
        "microservice.services.apim-primary.path" -> primaryPath,
        "microservice.services.apim-primary.clientId" -> primaryClientId,
        "microservice.services.apim-primary.secret" -> primarySecret,
        "microservice.services.apim-secondary.host" -> wireMockHost,
        "microservice.services.apim-secondary.port" -> wireMockPort,
        "microservice.services.apim-secondary.path" -> secondaryPath,
        "microservice.services.apim-secondary.clientId" -> secondaryClientId,
        "microservice.services.apim-secondary.secret" -> secondarySecret
      ))
    )

    new APIMConnectorImpl(servicesConfig, httpClientV2)
  }

}
