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

import com.github.tomakehurst.wiremock.client.WireMock.{stubFor, _}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.Configuration
import play.api.http.Status._
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, APIMConnectorImpl}
import uk.gov.hmrc.apihubapplications.models.apim._
import uk.gov.hmrc.apihubapplications.models.application.{Primary, Secondary}
import uk.gov.hmrc.apihubapplications.models.exception.ApimException
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.util.Base64

class APIMConnectorSpec
  extends AsyncFreeSpec
  with Matchers
  with WireMockSupport
  with HttpClientV2Support
  with EitherValues {

  import APIMConnectorSpec._

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
      stubFor(
        post(urlEqualTo(s"/$primaryPath/v1/simple-api-deployment/validate"))
          .willReturn(
            aResponse()
              .withStatus(400)
              .withBody(Json.toJson(failuresResponse).toString())
          )
      )

      buildConnector().validateInPrimary(oas)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(InvalidOasResponse(failuresResponse))
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

  "APIMConnector.deployToSecondary" - {
    "must place the correct request to the Simple API Deployment service in the secondary environment and return the response" in {
      stubFor(
        post(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments"))
          .withHeader("Content-Type", containing("multipart/form-data"))
          .withHeader("Authorization", equalTo(authorizationTokenSecondary))
          .withHeader("x-api-key", equalTo(secondaryApiKey))
          .withHeader("Accept", equalTo("application/json"))
          .withMultipartRequestBody(
            aMultipart()
              .withName("metadata")
              .withBody(equalToJson(Json.toJson(CreateMetadata(deploymentsRequest)).toString()))
          )
          .withMultipartRequestBody(
            aMultipart()
              .withName("openapi")
              .withBody(equalTo(oas))
          )
          .willReturn(
            aResponse()
              .withBody(Json.toJson(successfulDeploymentsResponse).toString())
          )
      )

      buildConnector().deployToSecondary(deploymentsRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(successfulDeploymentsResponse)
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
      stubFor(
        post(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments"))
          .willReturn(
            aResponse()
              .withStatus(400)
              .withBody(Json.toJson(failuresResponse).toString())
          )
      )

      buildConnector().deployToSecondary(deploymentsRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(InvalidOasResponse(failuresResponse))
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
      val context = Seq("metadata" -> Json.prettyPrint(Json.toJson(CreateMetadata(deploymentsRequest))))

      stubFor(
        post(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments"))
          .willReturn(
            aResponse()
              .withStatus(500)
          )
      )

      buildConnector().deployToSecondary(deploymentsRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(ApimException.unexpectedResponse(500, context))
      }
    }
  }

  "APIMConnector.redeployToSecondary" - {
    "must place the correct request to the Simple API Deployment service in the secondary environment and return the response" in {
      stubFor(
        put(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments/$publisherRef"))
          .withHeader("Content-Type", containing("multipart/form-data"))
          .withHeader("Authorization", equalTo(authorizationTokenSecondary))
          .withHeader("x-api-key", equalTo(secondaryApiKey))
          .withHeader("Accept", equalTo("application/json"))
          .withMultipartRequestBody(
            aMultipart()
              .withName("metadata")
              .withBody(equalToJson(Json.toJson(UpdateMetadata(redeploymentRequest)).toString()))
          )
          .withMultipartRequestBody(
            aMultipart()
              .withName("openapi")
              .withBody(equalTo(oas))
          )
          .willReturn(
            aResponse()
              .withBody(Json.toJson(successfulDeploymentsResponse).toString())
          )
      )

      buildConnector().redeployToSecondary(publisherRef, redeploymentRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(successfulDeploymentsResponse)
      }
    }

    "must return invalid response when the Simple API Deployment service's response cannot be parsed'" in {
      stubFor(
        put(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments/$publisherRef"))
          .willReturn(
            aResponse()
              .withBody("{}")
          )
      )

      buildConnector().redeployToSecondary(publisherRef, redeploymentRequest)(HeaderCarrier()).map {
        actual =>
          actual.left.value.issue mustBe ApimException.InvalidResponse
      }
    }

    "must return OAS validation failures when returned from the Simple API Deployment service" in {
      stubFor(
        put(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments/$publisherRef"))
          .willReturn(
            aResponse()
              .withStatus(400)
              .withBody(Json.toJson(failuresResponse).toString())
          )
      )

      buildConnector().redeployToSecondary(publisherRef, redeploymentRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(InvalidOasResponse(failuresResponse))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns Bad Request but no errors" in {
      stubFor(
        put(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments/$publisherRef"))
          .willReturn(
            aResponse()
              .withStatus(400)
          )
      )

      buildConnector().redeployToSecondary(publisherRef, redeploymentRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(ApimException.unexpectedResponse(400))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns one" in {
      val context = Seq("publisherReference" -> publisherRef, "metadata" -> Json.prettyPrint(Json.toJson(UpdateMetadata(redeploymentRequest))))

      stubFor(
        put(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments/$publisherRef"))
          .willReturn(
            aResponse()
              .withStatus(500)
          )
      )

      buildConnector().redeployToSecondary(publisherRef, redeploymentRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(ApimException.unexpectedResponse(500, context))
      }
    }

    "must return ServiceNotFound when the Simple API Deployment service returns 404 Not Found" in {
      stubFor(
        put(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments/$publisherRef"))
          .willReturn(
            aResponse()
              .withStatus(404)
          )
      )

      buildConnector().redeployToSecondary(publisherRef, redeploymentRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(ApimException.serviceNotFound(publisherRef))
      }
    }
  }

  "APIMConnector.getDeployment" - {
    "must place the correct request to the APIM in primary" in {
      stubFor(
        get(urlEqualTo(s"/$primaryPath/v1/oas-deployments/publisher_ref"))
          .withHeader("Authorization", equalTo(authorizationTokenPrimary))
          .willReturn(
            aResponse()
              .withBody(Json.toJson(successfulDeploymentResponse).toString())
              .withStatus(200)
          )
      )

      buildConnector().getDeployment("publisher_ref", Primary)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(Some(successfulDeploymentResponse))
      }
    }

    "must place the correct request to the APIM in secondary" in {
      stubFor(
        get(urlEqualTo(s"/$secondaryPath/v1/oas-deployments/publisher_ref"))
          .withHeader("Authorization", equalTo(authorizationTokenSecondary))
          .withHeader("x-api-key", equalTo(secondaryApiKey))
          .willReturn(
            aResponse()
              .withBody(Json.toJson(successfulDeploymentResponse).toString())
              .withStatus(200)
          )
      )

      buildConnector().getDeployment("publisher_ref", Secondary)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(Some(successfulDeploymentResponse))
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
      val publisherReference = "publisher_ref"
      val context = Seq("publisherReference" -> publisherReference, "environment" -> Primary)

      stubFor(
        get(urlEqualTo(s"/$primaryPath/v1/oas-deployments/publisher_ref"))
          .willReturn(
            aResponse()
              .withStatus(500)
          )
      )

      buildConnector().getDeployment(publisherReference, Primary)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(ApimException.unexpectedResponse(500, context))
      }
    }

  }

  "APIMConnector.getDeploymentDetails" - {
    "must place the correct request and return the DeploymentDetails on success" in {
      stubFor(
        get(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments/$serviceId"))
          .withHeader("Authorization", equalTo(authorizationTokenSecondary))
          .withHeader("Accept", equalTo("application/json"))
          .willReturn(
            aResponse()
              .withBody(Json.toJson(detailsResponse).toString())
          )
      )

      buildConnector().getDeploymentDetails(serviceId)(HeaderCarrier()).map(
        actual =>
          actual.value mustBe detailsResponse.toDeploymentDetails
      )
    }

    "must return ServiceNotFound when APIM returns a 404 Not Found" in {
      stubFor(
        get(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments/$serviceId"))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )

      buildConnector().getDeploymentDetails(serviceId)(HeaderCarrier()).map(
        actual =>
          actual.left.value mustBe ApimException.serviceNotFound(serviceId)
      )
    }

    "must return UnexpectedResponse when APIM returns one" in {
      val context = Seq("publisherReference" -> serviceId)

      stubFor(
        get(urlEqualTo(s"/$secondaryPath/v1/simple-api-deployment/deployments/$serviceId"))
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
          )
      )

      buildConnector().getDeploymentDetails(serviceId)(HeaderCarrier()).map(
        actual =>
          actual.left.value mustBe ApimException.unexpectedResponse(INTERNAL_SERVER_ERROR, context)
      )
    }
  }

  "APIMConnector.promoteToProduction" - {
    "must place the correct request and return the DeploymentsResponse on success" in {
      stubFor(
        put(urlEqualTo(s"/$primaryPath/v1/simple-api-deployment/deployment-from"))
          .withHeader("Content-Type", equalTo("application/json"))
          .withHeader("Authorization", equalTo(authorizationTokenPrimary))
          .withHeader("Accept", equalTo("application/json"))
          .withRequestBody(equalToJson(Json.toJson(deploymentFrom).toString()))
          .willReturn(
            aResponse()
              .withBody(Json.toJson(successfulDeploymentsResponse).toString())
          )
      )

      buildConnector().promoteToProduction(serviceId)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(successfulDeploymentsResponse)
      }
    }

    "must return InvalidOasResponse on failure" in {
      stubFor(
        put(urlEqualTo(s"/$primaryPath/v1/simple-api-deployment/deployment-from"))
          .withRequestBody(equalToJson(Json.toJson(deploymentFrom).toString()))
          .willReturn(
            aResponse()
              .withStatus(BAD_REQUEST)
              .withBody(Json.toJson(failuresResponse).toString())
          )
      )

      buildConnector().promoteToProduction(serviceId)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(InvalidOasResponse(failuresResponse))
      }
    }

    "must return InvalidResponse when the response from APIM cannot be parsed" in {
      stubFor(
        put(urlEqualTo(s"/$primaryPath/v1/simple-api-deployment/deployment-from"))
          .willReturn(
            aResponse()
              .withBody(Json.obj().toString())
          )
      )

      buildConnector().promoteToProduction(serviceId)(HeaderCarrier()).map {
        actual =>
          actual.left.value.issue mustBe ApimException.InvalidResponse
      }
    }

    "must return ServiceNotFound when APIM returns a 4040 Not Found" in {
      stubFor(
        put(urlEqualTo(s"/$primaryPath/v1/simple-api-deployment/deployment-from"))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )

      buildConnector().promoteToProduction(serviceId)(HeaderCarrier()).map {
        actual =>
          actual.left.value mustBe ApimException.serviceNotFound(serviceId)
      }
    }

    "must return UnexpectedResponse when APIM returns one" in {
      val context = Seq("publisherReference" -> serviceId)

      stubFor(
        put(urlEqualTo(s"/$primaryPath/v1/simple-api-deployment/deployment-from"))
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
          )
      )

      buildConnector().promoteToProduction(serviceId)(HeaderCarrier()).map {
        actual =>
          actual.left.value mustBe ApimException.unexpectedResponse(INTERNAL_SERVER_ERROR, context)
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
        "microservice.services.apim-secondary.secret" -> secondarySecret,
        "microservice.services.apim-secondary.apiKey" -> secondaryApiKey
      ))
    )

    new APIMConnectorImpl(servicesConfig, httpClientV2)
  }

}

object APIMConnectorSpec {

  private val oas = "test-oas-document"
  private val primaryClientId = "test-client-id-primary"
  private val secondaryClientId = "test-client-id-secondary"
  private val primarySecret = "test-secret-primary"
  private val secondarySecret = "test-secret-secondary"
  private val primaryPath = "test-path-primary"
  private val secondaryPath = "test-path-secondary"
  private val secondaryApiKey = "test-api-key-secondary"

  private val authorizationTokenPrimary = {
    val encoded = Base64.getEncoder.encodeToString(s"$primaryClientId:$primarySecret".getBytes("UTF-8"))
    s"Basic $encoded"
  }

  private val authorizationTokenSecondary = {
    val encoded = Base64.getEncoder.encodeToString(s"$secondaryClientId:$secondarySecret".getBytes("UTF-8"))
    s"Basic $encoded"
  }

  private val publisherRef = "test-publisher-ref"
  private val serviceId = publisherRef

  private val deploymentsRequest = DeploymentsRequest(
    lineOfBusiness = "test-line-of-business",
    name = "test-name",
    description = "test-description",
    egress = "test-egress",
    teamId = "test-team-id",
    oas = oas,
    passthrough = true,
    status = "a status",
    domain = "a domain",
    subDomain = "a subdomain",
    hods = Seq("a hod"),
    Seq("test-prefix-1", "test-prefix-2"),
    Some("test-egress-prefix")
  )

  private val redeploymentRequest = RedeploymentRequest(
    description = "test-description",
    oas = oas,
    status = "a status",
    domain = "a domain",
    subDomain = "a subdomain",
    hods = Seq("a hod"),
    prefixesToRemove = Seq("test-prefix-1", "test-prefix-2"),
    egressPrefix = Some("test-egress-prefix")
  )

  private val deploymentFrom = DeploymentFrom(
    env = "env/test",
    serviceId = serviceId
  )

  private val failuresResponse = FailuresResponse(
    code = "test-code",
    reason = "test-reason",
    errors = Some(Seq(
      Error("test-type-1", "test-message-1"),
      Error("test-type-2", "test-message-2")
    ))
  )

  private val successfulDeploymentResponse = SuccessfulDeploymentResponse(publisherRef, "1")

  private val successfulDeploymentsResponse =
    SuccessfulDeploymentsResponse(
      id = serviceId,
      version = "v1.2.3",
      mergeRequestIid = 201,
      uri = "test-uri"
    )

  private val detailsResponse = DetailsResponse(
    description = "test-description",
    status = "test-status",
    domain = "test-domain",
    subdomain = "test-sub-domain",
    backends = Seq("test-backend-1", "test-backend-2"),
    egressPrefix = "test-egress-prefix",
    prefixesToRemove = Seq("test-prefix-1", "test-prefix-2")
  )

}
