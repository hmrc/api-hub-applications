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

import com.github.tomakehurst.wiremock.client.WireMock.{aMultipart, _}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.Configuration
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.connectors.{SimpleApiDeploymentConnector, SimpleApiDeploymentConnectorImpl}
import uk.gov.hmrc.apihubapplications.models.exception.SimpleApiDeploymentException
import uk.gov.hmrc.apihubapplications.models.simpleapideployment.{DeploymentsRequest, DeploymentsMetadata, InvalidOasResponse, SuccessfulDeploymentsResponse, SuccessfulValidateResponse, ValidationFailure}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.util.Base64

class SimpleApiDeploymentConnectorSpec
  extends AsyncFreeSpec
  with Matchers
  with WireMockSupport
  with HttpClientV2Support
  with EitherValues {

  private val oas = "test-oas-document"
  private val clientId = "test-client-id"
  private val secret = "test-secret"
  private val path = "test-path"

  private val authorizationToken = {
    val endcoded = Base64.getEncoder.encodeToString(s"$clientId:$secret".getBytes("UTF-8"))
    s"Basic $endcoded"
  }

  private val deploymentsRequest = DeploymentsRequest(
    lineOfBusiness = "test-line-of-business",
    name = "test-name",
    description = "test-description",
    egress = "test-egress",
    oas = oas
  )

  "SimpleApiDeploymentConnector.validate" - {
    "must place the correct request to the Simple API Deployment service" in {
      stubFor(
        post(urlEqualTo(s"/$path/v1/simple-api-deployment/validate"))
          .withHeader("Content-Type", equalTo("application/yaml"))
          .withHeader("Authorization", equalTo(authorizationToken))
          .withRequestBody(equalTo(oas))
          .willReturn(
            aResponse()
          )
      )

      buildConnector().validate(oas)(HeaderCarrier()).map {
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
        post(urlEqualTo(s"/$path/v1/simple-api-deployment/validate"))
          .willReturn(
            aResponse()
              .withStatus(400)
              .withBody(Json.toJson(failures).toString())
          )
      )

      buildConnector().validate(oas)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(InvalidOasResponse(failures))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns Bad Request but no errors" in {
      stubFor(
        post(urlEqualTo(s"/$path/v1/simple-api-deployment/validate"))
          .willReturn(
            aResponse()
              .withStatus(400)
          )
      )

      buildConnector().validate(oas)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(SimpleApiDeploymentException.unexpectedResponse(400))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns one" in {
      stubFor(
        post(urlEqualTo(s"/$path/v1/simple-api-deployment/validate"))
          .willReturn(
            aResponse()
              .withStatus(500)
          )
      )

      buildConnector().validate(oas)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(SimpleApiDeploymentException.unexpectedResponse(500))
      }
    }
  }

  "SimpleApiDeploymentConnector.deployments" - {
    "must place the correct request to the Simple API Deployment service and return the response" in {
      val response = SuccessfulDeploymentsResponse(
        projectId = 101,
        lineOfBusiness = deploymentsRequest.lineOfBusiness,
        branchName = "test-branch-name",
        mergeRequestIid = 201
      )

      stubFor(
        post(urlEqualTo(s"/$path/v1/simple-api-deployment/deployments"))
          .withHeader("Content-Type", containing("multipart/form-data"))
          .withHeader("Authorization", equalTo(authorizationToken))
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

      buildConnector().deployments(deploymentsRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(response)
      }
    }

    "must return invalid response when the Simple API Deployment service's response cannot be parsed'" in {
      stubFor(
        post(urlEqualTo(s"/$path/v1/simple-api-deployment/deployments"))
          .willReturn(
            aResponse()
              .withBody("{}")
          )
      )

      buildConnector().deployments(deploymentsRequest)(HeaderCarrier()).map {
        actual =>
          actual.left.value.issue mustBe SimpleApiDeploymentException.InvalidResponse
      }
    }

    "must return OAS validation failures when returned from the Simple API Deployment service" in {
      val failures = Seq(
        ValidationFailure("test-type-1", "test-message-1"),
        ValidationFailure("test-type-2", "test-message-2")
      )

      stubFor(
        post(urlEqualTo(s"/$path/v1/simple-api-deployment/deployments"))
          .willReturn(
            aResponse()
              .withStatus(400)
              .withBody(Json.toJson(failures).toString())
          )
      )

      buildConnector().deployments(deploymentsRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(InvalidOasResponse(failures))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns Bad Request but no errors" in {
      stubFor(
        post(urlEqualTo(s"/$path/v1/simple-api-deployment/deployments"))
          .willReturn(
            aResponse()
              .withStatus(400)
          )
      )

      buildConnector().deployments(deploymentsRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(SimpleApiDeploymentException.unexpectedResponse(400))
      }
    }

    "must return unexpected response when the Simple API Deployment service returns one" in {
      stubFor(
        post(urlEqualTo(s"/$path/v1/simple-api-deployment/deployments"))
          .willReturn(
            aResponse()
              .withStatus(500)
          )
      )

      buildConnector().deployments(deploymentsRequest)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(SimpleApiDeploymentException.unexpectedResponse(500))
      }
    }
  }

  private def buildConnector(): SimpleApiDeploymentConnector = {
    val servicesConfig = new ServicesConfig(
      Configuration.from(Map(
        "microservice.services.simple-api-deployment.host" -> wireMockHost,
        "microservice.services.simple-api-deployment.port" -> wireMockPort,
        "microservice.services.simple-api-deployment.path" -> path,
        "microservice.services.simple-api-deployment.clientId" -> clientId,
        "microservice.services.simple-api-deployment.secret" -> secret
      ))
    )

    new SimpleApiDeploymentConnectorImpl(servicesConfig, httpClientV2)
  }

}
