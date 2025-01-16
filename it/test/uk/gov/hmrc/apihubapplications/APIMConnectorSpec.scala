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
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.http.Status.{BAD_REQUEST, INTERNAL_SERVER_ERROR, NOT_FOUND}
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, APIMConnectorImpl}
import uk.gov.hmrc.apihubapplications.models.api.EgressGateway
import uk.gov.hmrc.apihubapplications.models.apim.{ApiDeployment, CreateMetadata, DeploymentDetails, DeploymentFrom, DeploymentsRequest, DetailsResponse, EgressMapping, Error, FailuresResponse, InvalidOasResponse, RedeploymentRequest, SuccessfulDeploymentResponse, SuccessfulDeploymentsResponse, SuccessfulValidateResponse, UpdateMetadata}
import uk.gov.hmrc.apihubapplications.models.exception.ApimException.InvalidCredential
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, ApplicationsException}
import uk.gov.hmrc.http.{HeaderCarrier, RequestId}
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}

import java.time.Instant
import java.util.Base64
import scala.concurrent.ExecutionContext

class APIMConnectorSpec
  extends AsyncFreeSpec
    with Matchers
    with WireMockSupport
    with HttpClientV2Support
    with EitherValues {

  import APIMConnectorSpec.*

  private val correlationId = "correlation-id"
  private val requestId = Some(RequestId(correlationId))
  private val Seq(primaryEnvironment, secondaryEnvironment) = hipEnvironments(this).environments

  "APIMConnector.validatePrimary" - {
    "must place the correct request to the Simple API Deployment service" in {
      stubFor(
        post(urlEqualTo(s"/${primaryEnvironment.id}/v1/simple-api-deployment/validate"))
          .withHeader("Content-Type", equalTo("application/yaml"))
          .withHeader("Authorization", equalTo(authorizationTokenPrimary))
          .withHeader("X-Correlation-Id", equalTo(correlationId))
          .withRequestBody(equalTo(oas))
          .willReturn(
            aResponse()
          )
      )

      buildConnector(this).validateInPrimary(oas)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Right(SuccessfulValidateResponse)
      }
    }

    "must return OAS validation failures when returned from the Simple API Deployment service" in {
      stubFor(
        post(urlEqualTo(s"/${primaryEnvironment.id}/v1/simple-api-deployment/validate"))
          .willReturn(
            aResponse()
              .withStatus(400)
              .withBody(Json.toJson(failuresResponse).toString())
          )
      )

      buildConnector(this).validateInPrimary(oas)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Right(InvalidOasResponse(failuresResponse))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns Bad Request but no errors" in {
      stubFor(
        post(urlEqualTo(s"/${primaryEnvironment.id}/v1/simple-api-deployment/validate"))
          .willReturn(
            aResponse()
              .withStatus(400)
          )
      )

      buildConnector(this).validateInPrimary(oas)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Left(ApimException.unexpectedResponse(400))
      }
    }

    "must return an invalid credential response on a 401 response from APIM" in {
      stubFor(
        post(urlEqualTo(s"/${primaryEnvironment.id}/v1/simple-api-deployment/validate"))
          .willReturn(
            aResponse()
              .withStatus(401)
          )
      )

      buildConnector(this).validateInPrimary(oas)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Left(ApimException(
            ApplicationsException.addContext(s"Invalid credential response 401", Seq.empty),
            InvalidCredential
          ))
      }
    }

    "must return an invalid credential response on a 403 response from APIM" in {
      stubFor(
        post(urlEqualTo(s"/${primaryEnvironment.id}/v1/simple-api-deployment/validate"))
          .willReturn(
            aResponse()
              .withStatus(403)
          )
      )

      buildConnector(this).validateInPrimary(oas)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Left(ApimException(
            ApplicationsException.addContext(s"Invalid credential response 403", Seq.empty),
            InvalidCredential
          ))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns one" in {
      stubFor(
        post(urlEqualTo(s"/${primaryEnvironment.id}/v1/simple-api-deployment/validate"))
          .willReturn(
            aResponse()
              .withStatus(500)
          )
      )

      buildConnector(this).validateInPrimary(oas)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Left(ApimException.unexpectedResponse(500))
      }
    }
  }

  "APIMConnector.deployToSecondary" - {
    "must place the correct request to the Simple API Deployment service in the secondary environment and return the response" in {
      stubFor(
        post(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments"))
          .withHeader("Content-Type", containing("multipart/form-data"))
          .withHeader("Authorization", equalTo(authorizationTokenSecondary))
          .withHeader("x-api-key", equalTo(secondaryApiKey))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("X-Correlation-Id", equalTo(correlationId))
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

      buildConnector(this).deployToSecondary(deploymentsRequest)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Right(successfulDeploymentsResponse)
      }
    }

    "must return invalid response when the Simple API Deployment service's response cannot be parsed'" in {
      stubFor(
        post(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments"))
          .willReturn(
            aResponse()
              .withBody("{}")
          )
      )

      buildConnector(this).deployToSecondary(deploymentsRequest)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual.left.value.issue mustBe ApimException.InvalidResponse
      }
    }

    "must return OAS validation failures when returned from the Simple API Deployment service" in {
      stubFor(
        post(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments"))
          .willReturn(
            aResponse()
              .withStatus(400)
              .withBody(Json.toJson(failuresResponse).toString())
          )
      )

      buildConnector(this).deployToSecondary(deploymentsRequest)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Right(InvalidOasResponse(failuresResponse))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns Bad Request but no errors" in {
      stubFor(
        post(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments"))
          .willReturn(
            aResponse()
              .withStatus(400)
          )
      )

      buildConnector(this).deployToSecondary(deploymentsRequest)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Left(ApimException.unexpectedResponse(400))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns one" in {
      val context = Seq(
        "metadata" -> Json.prettyPrint(Json.toJson(CreateMetadata(deploymentsRequest))),
        "X-Correlation-Id" -> correlationId
      )

      stubFor(
        post(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments"))
          .willReturn(
            aResponse()
              .withStatus(500)
          )
      )

      buildConnector(this).deployToSecondary(deploymentsRequest)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Left(ApimException.unexpectedResponse(500, context))
      }
    }
  }

  "APIMConnector.redeployToSecondary" - {
    "must place the correct request to the Simple API Deployment service in the secondary environment and return the response" in {
      stubFor(
        put(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments/$publisherRef"))
          .withHeader("Content-Type", containing("multipart/form-data"))
          .withHeader("Authorization", equalTo(authorizationTokenSecondary))
          .withHeader("x-api-key", equalTo(secondaryApiKey))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("X-Correlation-Id", equalTo(correlationId))
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

      buildConnector(this).redeployToSecondary(publisherRef, redeploymentRequest)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Right(successfulDeploymentsResponse)
      }
    }

    "must return invalid response when the Simple API Deployment service's response cannot be parsed'" in {
      stubFor(
        put(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments/$publisherRef"))
          .willReturn(
            aResponse()
              .withBody("{}")
          )
      )

      buildConnector(this).redeployToSecondary(publisherRef, redeploymentRequest)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual.left.value.issue mustBe ApimException.InvalidResponse
      }
    }

    "must return OAS validation failures when returned from the Simple API Deployment service" in {
      stubFor(
        put(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments/$publisherRef"))
          .willReturn(
            aResponse()
              .withStatus(400)
              .withBody(Json.toJson(failuresResponse).toString())
          )
      )

      buildConnector(this).redeployToSecondary(publisherRef, redeploymentRequest)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Right(InvalidOasResponse(failuresResponse))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns Bad Request but no errors" in {
      stubFor(
        put(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments/$publisherRef"))
          .willReturn(
            aResponse()
              .withStatus(400)
          )
      )

      buildConnector(this).redeployToSecondary(publisherRef, redeploymentRequest)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Left(ApimException.unexpectedResponse(400))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns one" in {
      val context = Seq(
        "publisherReference" -> publisherRef,
        "metadata" -> Json.prettyPrint(Json.toJson(UpdateMetadata(redeploymentRequest))),
        "X-Correlation-Id" -> correlationId
      )

      stubFor(
        put(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments/$publisherRef"))
          .willReturn(
            aResponse()
              .withStatus(500)
          )
      )

      buildConnector(this).redeployToSecondary(publisherRef, redeploymentRequest)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Left(ApimException.unexpectedResponse(500, context))
      }
    }

    "must return ServiceNotFound when the Simple API Deployment service returns 404 Not Found" in {
      stubFor(
        put(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments/$publisherRef"))
          .willReturn(
            aResponse()
              .withStatus(404)
          )
      )

      buildConnector(this).redeployToSecondary(publisherRef, redeploymentRequest)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Left(ApimException.serviceNotFound(publisherRef))
      }
    }
  }

  "APIMConnector.getDeployment" - {
    "must place the correct request to the APIM in primary" in {
      stubFor(
        get(urlEqualTo(s"/${primaryEnvironment.id}/v1/oas-deployments/publisher_ref"))
          .withHeader("Authorization", equalTo(authorizationTokenPrimary))
          .willReturn(
            aResponse()
              .withBody(Json.toJson(successfulDeploymentResponse).toString())
              .withStatus(200)
          )
      )

      buildConnector(this).getDeployment("publisher_ref", primaryEnvironment)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Right(Some(successfulDeploymentResponse))
      }
    }

    "must place the correct request to the APIM in secondary" in {
      stubFor(
        get(urlEqualTo(s"/${secondaryEnvironment.id}/v1/oas-deployments/publisher_ref"))
          .withHeader("Authorization", equalTo(authorizationTokenSecondary))
          .withHeader("x-api-key", equalTo(secondaryApiKey))
          .withHeader("X-Correlation-Id", equalTo(correlationId))
          .willReturn(
            aResponse()
              .withBody(Json.toJson(successfulDeploymentResponse).toString())
              .withStatus(200)
          )
      )

      buildConnector(this).getDeployment("publisher_ref", secondaryEnvironment)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Right(Some(successfulDeploymentResponse))
      }
    }

    "must handle 404 in primary" in {
      stubFor(
        get(urlEqualTo(s"/${primaryEnvironment.id}/v1/oas-deployments/publisher_ref"))
          .withHeader("Authorization", equalTo(authorizationTokenPrimary))
          .withHeader("X-Correlation-Id", equalTo(correlationId))
          .willReturn(
            aResponse()
              .withStatus(404)
          )
      )

      buildConnector(this).getDeployment("publisher_ref", primaryEnvironment)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Right(None)
      }
    }

    "must handle 404 in secondary" in {
      stubFor(
        get(urlEqualTo(s"/${secondaryEnvironment.id}/v1/oas-deployments/publisher_ref"))
          .withHeader("Authorization", equalTo(authorizationTokenSecondary))
          .withHeader("X-Correlation-Id", equalTo(correlationId))
          .willReturn(
            aResponse()
              .withStatus(404)
          )
      )

      buildConnector(this).getDeployment("publisher_ref", secondaryEnvironment)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Right(None)
      }
    }

    "must return unexpected response when APIM returns one" in {
      val publisherReference = "publisher_ref"
      val context = Seq(
        "publisherReference" -> publisherReference,
        "hipEnvironment" -> primaryEnvironment.id,
        "X-Correlation-Id" -> correlationId
      )

      stubFor(
        get(urlEqualTo(s"/${primaryEnvironment.id}/v1/oas-deployments/publisher_ref"))
          .willReturn(
            aResponse()
              .withStatus(500)
          )
      )

      buildConnector(this).getDeployment(publisherReference, primaryEnvironment)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Left(ApimException.unexpectedResponse(500, context))
      }
    }

    "must default OAS version to 'unknown' when not returned by APIM" in {
      val expected = SuccessfulDeploymentResponse(
        id = successfulDeploymentResponse.id,
        oasVersion = "unknown"
      )

      stubFor(
        get(urlEqualTo(s"/${primaryEnvironment.id}/v1/oas-deployments/publisher_ref"))
          .willReturn(
            aResponse()
              .withBody(s"""{"id": "${expected.id}"}""")
              .withStatus(200)
          )
      )

      buildConnector(this).getDeployment("publisher_ref", primaryEnvironment)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Right(Some(expected))
      }
    }
  }

  "APIMConnector.getDeploymentDetails" - {
    "must place the correct request and return the DeploymentDetails on success" in {
      stubFor(
        get(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments/$serviceId"))
          .withHeader("Authorization", equalTo(authorizationTokenSecondary))
          .withHeader("x-api-key", equalTo(secondaryApiKey))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("X-Correlation-Id", equalTo(correlationId))
          .willReturn(
            aResponse()
              .withBody(Json.toJson(detailsResponse).toString())
          )
      )

      buildConnector(this).getDeploymentDetails(serviceId)(HeaderCarrier(requestId = requestId)).map(
        actual =>
          actual.value mustBe detailsResponse.toDeploymentDetails
      )
    }

    "must correctly handle null egressMappings and prefixesToRemove values" in {
      val detailsResponseWithNullValues =
        """
          |{
          |    "description": "Keying Service API",
          |    "status": "ALPHA",
          |    "domain": "8",
          |    "subdomain": "8.16",
          |    "backends": [
          |        "EMS"
          |    ],
          |    "egressMappings": null,
          |    "prefixesToRemove": null
          |}
          |""".stripMargin

      stubFor(
        get(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments/$serviceId"))
          .withHeader("Authorization", equalTo(authorizationTokenSecondary))
          .withHeader("x-api-key", equalTo(secondaryApiKey))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("X-Correlation-Id", equalTo(correlationId))
          .willReturn(
            aResponse()
              .withBody(detailsResponseWithNullValues)
          )
      )

      buildConnector(this).getDeploymentDetails(serviceId)(HeaderCarrier(requestId = requestId)).map(
        actual => {
          actual.value mustBe deploymentDetailsWithoutEgressOrPrefixes
        }
      )
    }

    "must correctly handle missing egressMappings and prefixesToRemove values" in {
      val detailsResponseWithMissingValues =
        """
          |{
          |    "description": "Keying Service API",
          |    "status": "ALPHA",
          |    "domain": "8",
          |    "subdomain": "8.16",
          |    "backends": [
          |        "EMS"
          |    ]
          |}
          |""".stripMargin

      stubFor(
        get(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments/$serviceId"))
          .withHeader("Authorization", equalTo(authorizationTokenSecondary))
          .withHeader("x-api-key", equalTo(secondaryApiKey))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("X-Correlation-Id", equalTo(correlationId))
          .willReturn(
            aResponse()
              .withBody(detailsResponseWithMissingValues)
          )
      )

      buildConnector(this).getDeploymentDetails(serviceId)(HeaderCarrier(requestId = requestId)).map(
        actual => {
          actual.value mustBe deploymentDetailsWithoutEgressOrPrefixes
        }
      )
    }

    "must process a response with no details" in {
      stubFor(
        get(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments/$serviceId"))
          .withHeader("Authorization", equalTo(authorizationTokenSecondary))
          .withHeader("x-api-key", equalTo(secondaryApiKey))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("X-Correlation-Id", equalTo(correlationId))
          .willReturn(
            aResponse()
              .withBody(Json.toJson(detailsResponseWithoutDetails).toString())
          )
      )

      buildConnector(this).getDeploymentDetails(serviceId)(HeaderCarrier(requestId = requestId)).map(
        actual =>
          actual.value mustBe detailsResponseWithoutDetails.toDeploymentDetails
      )
    }

    "must return ServiceNotFound when APIM returns a 404 Not Found" in {
      stubFor(
        get(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments/$serviceId"))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )

      buildConnector(this).getDeploymentDetails(serviceId)(HeaderCarrier(requestId = requestId)).map(
        actual =>
          actual.left.value mustBe ApimException.serviceNotFound(serviceId)
      )
    }

    "must return UnexpectedResponse when APIM returns one" in {
      val context = Seq("publisherReference" -> serviceId, "X-Correlation-Id" -> correlationId)

      stubFor(
        get(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/deployments/$serviceId"))
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
          )
      )

      buildConnector(this).getDeploymentDetails(serviceId)(HeaderCarrier(requestId = requestId)).map(
        actual =>
          actual.left.value mustBe ApimException.unexpectedResponse(INTERNAL_SERVER_ERROR, context)
      )
    }
  }

  "APIMConnector.promoteToProduction" - {
    "must place the correct request and return the DeploymentsResponse on success" in {
      stubFor(
        put(urlEqualTo(s"/${primaryEnvironment.id}/v1/simple-api-deployment/deployment-from"))
          .withHeader("Content-Type", equalTo("application/json"))
          .withHeader("Authorization", equalTo(authorizationTokenPrimary))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("X-Correlation-Id", equalTo(correlationId))
          .withRequestBody(equalToJson(Json.toJson(deploymentFrom).toString()))
          .willReturn(
            aResponse()
              .withBody(Json.toJson(successfulDeploymentsResponse).toString())
          )
      )

      buildConnector(this).promoteToProduction(serviceId)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Right(successfulDeploymentsResponse)
      }
    }

    "must return InvalidOasResponse on failure" in {
      stubFor(
        put(urlEqualTo(s"/${primaryEnvironment.id}/v1/simple-api-deployment/deployment-from"))
          .withRequestBody(equalToJson(Json.toJson(deploymentFrom).toString()))
          .willReturn(
            aResponse()
              .withStatus(BAD_REQUEST)
              .withBody(Json.toJson(failuresResponse).toString())
          )
      )

      buildConnector(this).promoteToProduction(serviceId)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual mustBe Right(InvalidOasResponse(failuresResponse))
      }
    }

    "must return InvalidResponse when the response from APIM cannot be parsed" in {
      stubFor(
        put(urlEqualTo(s"/${primaryEnvironment.id}/v1/simple-api-deployment/deployment-from"))
          .willReturn(
            aResponse()
              .withBody(Json.obj().toString())
          )
      )

      buildConnector(this).promoteToProduction(serviceId)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual.left.value.issue mustBe ApimException.InvalidResponse
      }
    }

    "must return ServiceNotFound when APIM returns a 4040 Not Found" in {
      stubFor(
        put(urlEqualTo(s"/${primaryEnvironment.id}/v1/simple-api-deployment/deployment-from"))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )

      buildConnector(this).promoteToProduction(serviceId)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual.left.value mustBe ApimException.serviceNotFound(serviceId)
      }
    }

    "must return UnexpectedResponse when APIM returns one" in {
      val context = Seq("publisherReference" -> serviceId, "X-Correlation-Id" -> correlationId)

      stubFor(
        put(urlEqualTo(s"/${primaryEnvironment.id}/v1/simple-api-deployment/deployment-from"))
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
          )
      )

      buildConnector(this).promoteToProduction(serviceId)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual.left.value mustBe ApimException.unexpectedResponse(INTERNAL_SERVER_ERROR, context)
      }
    }
  }

  "APIMConnector.getDeployments" - {
    "must place the correct request and return the ApiDeployments on success" in {
      val deployments = Seq(
        ApiDeployment("test-id-1", Some(Instant.now())),
        ApiDeployment("test-id-2", None)
      )

      stubFor(
        get(urlEqualTo(s"/${secondaryEnvironment.id}/v1/oas-deployments"))
          .withHeader("Authorization", equalTo(authorizationTokenSecondary))
          .withHeader("x-api-key", equalTo(secondaryApiKey))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("X-Correlation-Id", equalTo(correlationId))
          .willReturn(
            aResponse()
              .withBody(Json.toJson(deployments).toString)
          )
      )

      buildConnector(this).getDeployments(secondaryEnvironment)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual.value mustBe deployments
      }
    }

    "must return UnexpectedResponse when APIM returns one" in {
      val context = Seq(
        "hipEnvironment" -> secondaryEnvironment.id,
        "X-Correlation-Id" -> correlationId
      )

      stubFor(
        get(urlEqualTo(s"/${secondaryEnvironment.id}/v1/oas-deployments"))
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
          )
      )

      buildConnector(this).getDeployments(secondaryEnvironment)(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual.left.value mustBe ApimException.unexpectedResponse(INTERNAL_SERVER_ERROR, context)
      }
    }
  }

  "APIMConnector.listEgressGateways" - {
    "must place the correct request and return the egress gateways on success" in {

      val expectedResponse = {
        1 until 10 map (i => EgressGateway(s"fake-egress-id-$i", s"Egress Friendly Name $i"))
      }

      stubFor(
        get(urlEqualTo(s"/${secondaryEnvironment.id}/v1/simple-api-deployment/egress-gateways"))
          .withHeader("Authorization", equalTo(authorizationTokenSecondary))
          .withHeader("x-api-key", equalTo(secondaryApiKey))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("X-Correlation-Id", equalTo(correlationId))
          .willReturn(
            aResponse()
              .withBody(Json.toJson(expectedResponse).toString)
          )
      )

      buildConnector(this).listEgressGateways()(HeaderCarrier(requestId = requestId)).map {
        actual =>
          actual.value mustBe expectedResponse
      }
    }
  }

}

object APIMConnectorSpec extends HttpClientV2Support {

  private val oas = "test-oas-document"
  private val primaryClientId = "test-client-id-primary"
  private val secondaryClientId = "test-client-id-secondary"
  private val primarySecret = "test-secret-primary"
  private val secondarySecret = "test-secret-secondary"
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
    Some(Seq(EgressMapping("prefix", "egress-prefix")))
  )

  private val redeploymentRequest = RedeploymentRequest(
    description = "test-description",
    oas = oas,
    status = "a status",
    domain = "a domain",
    subDomain = "a subdomain",
    hods = Seq("a hod"),
    prefixesToRemove = Seq("test-prefix-1", "test-prefix-2"),
    egressMappings = Some(Seq(EgressMapping("prefix", "egress-prefix"))),
    egress = "test-egress",
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
    description = Some("test-description"),
    status = Some("test-status"),
    domain = Some("test-domain"),
    subdomain = Some("test-sub-domain"),
    backends = Some(Seq("test-backend-1", "test-backend-2")),
    egressMappings = Some(Seq(EgressMapping("prefix", "egress-prefix"))),
    prefixesToRemove = Some(Seq("test-prefix-1", "test-prefix-2")),
    egress = Some("test-egress")
  )

  private val detailsResponseWithoutDetails = DetailsResponse(
    description = None,
    status = None,
    domain = None,
    subdomain = None,
    backends = None,
    egressMappings = None,
    prefixesToRemove = None,
    egress = None,
  )

  private val deploymentDetailsWithoutEgressOrPrefixes = DeploymentDetails(
    description = Some("Keying Service API"),
    status = Some("ALPHA"),
    domain = Some("8"),
    subDomain = Some("8.16"),
    hods = Some(Seq("EMS")),
    egressMappings = None,
    prefixesToRemove = Seq.empty,
    egress = None,
  )

  def hipEnvironments(wireMockSupport: WireMockSupport): HipEnvironments = new HipEnvironments {
    override val environments: Seq[HipEnvironment] = Seq(
      HipEnvironment(
        id = "production",
        rank = 1,
        isProductionLike = true,
        apimUrl = s"http://${wireMockSupport.wireMockHost}:${wireMockSupport.wireMockPort}/production",
        clientId = primaryClientId,
        secret = primarySecret,
        useProxy = false,
        apiKey = None
      ),
      HipEnvironment(
        id = "test",
        rank = 2,
        isProductionLike = false,
        apimUrl = s"http://${wireMockSupport.wireMockHost}:${wireMockSupport.wireMockPort}/test",
        clientId = secondaryClientId,
        secret = secondarySecret,
        useProxy = false,
        apiKey = Some(secondaryApiKey)
      )
    )
    def productionEnvironment: HipEnvironment = environments.head
    def deploymentEnvironment: HipEnvironment = environments.last
  }

  private def buildConnector(wireMockSupport: WireMockSupport)(implicit ec: ExecutionContext): APIMConnector = {
    new APIMConnectorImpl(httpClientV2, hipEnvironments(wireMockSupport))
  }

}