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
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.matching.StringValuePattern
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor1}
import play.api.http.Status.{INTERNAL_SERVER_ERROR, NOT_FOUND}
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}
import uk.gov.hmrc.apihubapplications.connectors.{IdmsConnector, IdmsConnectorImpl}
import uk.gov.hmrc.apihubapplications.models.WithName
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator, Credential}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.{CallError, InvalidCredential}
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, ClientScope, Secret}
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.http.{HeaderCarrier, RequestId}
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext

class IdmsConnectorSpec
  extends AsyncFreeSpec
  with Matchers
  with WireMockSupport
  with TableDrivenPropertyChecks
  with EitherValues {

  import IdmsConnectorImplSpec.*

  private val correlationId = "correlation-id"
  private val requestId = Some(RequestId(correlationId))
  private val Seq(primaryEnvironment, secondaryEnvironment) = hipEnvironments(this).environments

  "IdmsConnector.createClient" - {
    "must place the correct request per environment to IDMS and return the ClientResponse" in {
      forAll(hipEnvironmentsTable(this)) { (hipEnvironment: HipEnvironment) =>
        stubFor(
          post(urlEqualTo(s"/${hipEnvironment.environmentName}/identity/clients"))
            .withHeader("Accept", equalTo("application/json"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withHeader("Authorization", equalTo(authorizationHeaderFor(hipEnvironment)))
            .withHeader("x-api-key", apiKeyHeaderPatternFor(hipEnvironment))
            .withHeader("X-Correlation-Id", equalTo(correlationId))
            .withRequestBody(
              equalToJson(Json.toJson(testClient).toString())
            )
            .willReturn(
              aResponse()
                .withBody(Json.toJson(testClientResponse).toString())
            )
        )

        buildConnector(this).createClient(hipEnvironment, testClient)(HeaderCarrier(requestId = requestId)) map {
          clientResponse =>
            clientResponse mustBe Right(testClientResponse)
        }
      }
    }

    "must return IdmsException for any non-2xx response" in {
      val context = Seq(
        "hipEnvironment" -> primaryEnvironment.id,
        "client" -> testClient,
        "X-Correlation-Id" -> correlationId
      )

      forAll(nonSuccessResponses) { (status: Int) =>
        stubFor(
          post(urlEqualTo(s"/primary/identity/clients"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withHeader("X-Correlation-Id", equalTo(correlationId))
            .withRequestBody(
              equalToJson(Json.toJson(testClient).toString())
            )
            .willReturn(
              aResponse()
                .withStatus(status)
            )
        )

        buildConnector(this).createClient(primaryEnvironment, testClient)(HeaderCarrier(requestId = requestId)) map {
          result =>
            result mustBe Left(IdmsException.unexpectedResponse(status, context))
        }
      }
    }

    "must return IdmsException for any errors" in {
      stubFor(
        post(urlEqualTo(s"/primary/identity/clients"))
          .withHeader("Content-Type", equalTo("application/json"))
          .withRequestBody(
            equalToJson(Json.toJson(testClient).toString())
          )
          .willReturn(
            aResponse()
              .withFault(Fault.CONNECTION_RESET_BY_PEER)
          )
      )

      buildConnector(this).createClient(primaryEnvironment, testClient)(HeaderCarrier(requestId = requestId)) map {
        result =>
          result.left.value mustBe a [IdmsException]
          result.left.value.issue mustBe CallError
      }
    }
  }

  "IdmsConnector.fetchClient" - {
    "must place the correct request per environment to IDMS and return the secret" in {
      forAll(hipEnvironmentsTable(this)) { (hipEnvironment: HipEnvironment) =>
        stubFor(
          get(urlEqualTo(s"/${hipEnvironment.environmentName}/identity/clients/$testClientId/client-secret"))
            .withHeader("Accept", equalTo("application/json"))
            .withHeader("Authorization", equalTo(authorizationHeaderFor(hipEnvironment)))
            .withHeader("x-api-key", apiKeyHeaderPatternFor(hipEnvironment))
            .withHeader("X-Correlation-Id", equalTo(correlationId))
            .willReturn(
              aResponse()
                .withBody(Json.toJson(testSecret).toString())
            )
        )

        buildConnector(this).fetchClient(hipEnvironment, testClientId)(HeaderCarrier(requestId = requestId)) map {
          clientResponse =>
            clientResponse mustBe Right(testClientResponse)
        }
      }
    }

    "must return IdmsException when IDMS returns 404 Not Found for a given Client Id" in {
      stubFor(
        get(urlEqualTo(s"/primary/identity/clients/$testClientId/client-secret"))
          .willReturn(
            aResponse()
              .withStatus(404)
          )
      )

      buildConnector(this).fetchClient(primaryEnvironment, testClientId)(HeaderCarrier(requestId = requestId)) map {
        clientResponse =>
          clientResponse mustBe Left(IdmsException.clientNotFound(testClientId))
      }
    }

    "must return IdmsException for any non-2xx response" in {
      val context = Seq(
        "hipEnvironment" -> primaryEnvironment.id,
        "clientId" -> testClientId,
        "X-Correlation-Id" -> correlationId
      )

      forAll(nonSuccessResponses) { (status: Int) =>
        stubFor(
          get(urlEqualTo(s"/primary/identity/clients/$testClientId/client-secret"))
            .willReturn(
              aResponse()
                .withStatus(status)
            )
        )

        buildConnector(this).fetchClient(primaryEnvironment, testClientId)(HeaderCarrier(requestId = requestId)) map {
          clientResponse =>
            clientResponse mustBe Left(IdmsException.unexpectedResponse(status, context))
        }
      }
    }

    "must return IdmsException for any errors" in {
      stubFor(
        get(urlEqualTo(s"/primary/identity/clients/$testClientId/client-secret"))
          .willReturn(
            aResponse()
              .withFault(Fault.CONNECTION_RESET_BY_PEER)
          )
      )

      buildConnector(this).fetchClient(primaryEnvironment, testClientId)(HeaderCarrier(requestId = requestId)) map {
        clientResponse =>
          clientResponse.left.value mustBe a [IdmsException]
          clientResponse.left.value.issue mustBe CallError
      }
    }
  }

  "IdmsConnector.deleteClient" - {
    "must place the correct request per environment to IDMS and succeed" in {
      forAll(hipEnvironmentsTable(this)) { (hipEnvironment: HipEnvironment) =>
        stubFor(
          delete(urlEqualTo(s"/${hipEnvironment.environmentName}/identity/clients/$testClientId"))
            .withHeader("Authorization", equalTo(authorizationHeaderFor(hipEnvironment)))
            .withHeader("x-api-key", apiKeyHeaderPatternFor(hipEnvironment))
            .willReturn(
              aResponse()
            )
        )

        buildConnector(this).deleteClient(hipEnvironment, testClientId)(HeaderCarrier(requestId = requestId)) map {
          actual =>
            actual mustBe Right(())
        }
      }
    }

    "must throw IdmsException with an IdmsIssue of ClientNotFound when IDMS returns 404 Not Found" in {
      stubFor(
        delete(urlEqualTo(s"/${primaryEnvironment.environmentName}/identity/clients/$testClientId"))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )

      buildConnector(this).deleteClient(primaryEnvironment, testClientId)(HeaderCarrier(requestId = requestId)) map {
        actual =>
          actual mustBe Left(IdmsException.clientNotFound(testClientId))
      }
    }

    "must return IdmsException for any non-2xx or 404 response" in {
      val context = Seq(
        "hipEnvironment" -> primaryEnvironment.id,
        "clientId" -> testClientId,
        "X-Correlation-Id" -> correlationId
      )

      forAll(nonSuccessResponses) { (status: Int) =>
        stubFor(
          delete(urlEqualTo(s"/${primaryEnvironment.environmentName}/identity/clients/$testClientId"))
            .willReturn(
              aResponse()
                .withStatus(status)
            )
        )

        buildConnector(this).deleteClient(primaryEnvironment, testClientId)(HeaderCarrier(requestId = requestId)) map {
          actual =>
            actual mustBe Left(IdmsException.unexpectedResponse(status, context))
        }
      }
    }

    "must return IdmsException for any errors" in {
      stubFor(
        delete(urlEqualTo(s"/${primaryEnvironment.environmentName}/identity/clients/$testClientId"))
          .willReturn(
            aResponse()
              .withFault(Fault.CONNECTION_RESET_BY_PEER)
          )
      )

      buildConnector(this).deleteClient(primaryEnvironment, testClientId)(HeaderCarrier(requestId = requestId)) map {
        actual =>
          actual.left.value mustBe a[IdmsException]
          actual.left.value.issue mustBe CallError
      }
    }
  }

  "IdmsConnector.newSecret" - {
    "must place the correct request per environment to IDMS and return the new secret" in {
      forAll(hipEnvironmentsTable(this)) { (hipEnvironment: HipEnvironment) =>
        stubFor(
          post(urlEqualTo(s"/${hipEnvironment.environmentName}/identity/clients/${hipEnvironment.clientId}/client-secret"))
            .withHeader("Accept", equalTo("application/json"))
            .withHeader("Authorization", equalTo(authorizationHeaderFor(hipEnvironment)))
            .withHeader("x-api-key", apiKeyHeaderPatternFor(hipEnvironment))
            .withHeader("X-Correlation-Id", equalTo(correlationId))
            .willReturn(
              aResponse()
                .withBody(Json.toJson(testSecret).toString())
            )
        )

        buildConnector(this).newSecret(hipEnvironment, hipEnvironment.clientId)(HeaderCarrier(requestId = requestId)) map {
          secretResponse =>
            secretResponse mustBe Right(testSecret)
        }
      }
    }

    "must throw IdmsException with an IdmsIssue of ClientNotFound when IDMS returns 404 Not Found" in {
      stubFor(
        post(urlEqualTo(s"/primary/identity/clients/$testClientId/client-secret"))
          .willReturn(
            aResponse()
              .withStatus(404)
          )
      )

      buildConnector(this).fetchClient(primaryEnvironment, testClientId)(HeaderCarrier(requestId = requestId)) map {
        clientResponse =>
          clientResponse mustBe Left(IdmsException.clientNotFound(testClientId))
      }
    }

    "must return IdmsException for any non-2xx response" in {
      val context = Seq(
        "hipEnvironment" -> primaryEnvironment.id,
        "clientId" -> testClientId,
        "X-Correlation-Id" -> correlationId
      )

      forAll(nonSuccessResponses) { (status: Int) =>
        stubFor(
          post(urlEqualTo(s"/primary/identity/clients/$testClientId/client-secret"))
            .willReturn(
              aResponse()
                .withStatus(status)
            )
        )

        buildConnector(this).newSecret(primaryEnvironment, testClientId)(HeaderCarrier(requestId = requestId)) map {
          clientResponse =>
            clientResponse mustBe Left(IdmsException.unexpectedResponse(status, context))
        }
      }
    }

    "must return IdmsException for any errors" in {
      stubFor(
        post(urlEqualTo(s"/primary/identity/clients/$testClientId/client-secret"))
          .willReturn(
            aResponse()
              .withFault(Fault.CONNECTION_RESET_BY_PEER)
          )
      )

      buildConnector(this).newSecret(primaryEnvironment, testClientId)(HeaderCarrier(requestId = requestId)) map {
        clientResponse =>
          clientResponse.left.value mustBe a[IdmsException]
          clientResponse.left.value.issue mustBe CallError
      }
    }
  }

  "IdmsConnector.addClientScope" - {
    "must place the correct request per environment to IDMS and return true" in {
      forAll(hipEnvironmentsTable(this)) { (hipEnvironment: HipEnvironment) =>
        stubFor(
          put(urlEqualTo(s"/${hipEnvironment.environmentName}/identity/clients/${hipEnvironment.clientId}/client-scopes/$testScopeId"))
            .withHeader("Authorization", equalTo(authorizationHeaderFor(hipEnvironment)))
            .withHeader("x-api-key", apiKeyHeaderPatternFor(hipEnvironment))
            .withHeader("X-Correlation-Id", equalTo(correlationId))
            .willReturn(
              aResponse()
                .withStatus(200)
            )
        )

        buildConnector(this).addClientScope(hipEnvironment, hipEnvironment.clientId, testScopeId)(HeaderCarrier(requestId = requestId)) map {
          response => response mustBe Right(())
        }
      }
    }

    "must throw IdmsException with an IdmsIssue of ClientNotFound when IDMS returns 404 Not Found" in {
      stubFor(
        put(urlEqualTo(s"/primary/identity/clients/$testClientId/client-scopes/$testScopeId"))
          .willReturn(
            aResponse()
              .withStatus(404)
          )
      )

      buildConnector(this).addClientScope(primaryEnvironment, testClientId, testScopeId)(HeaderCarrier(requestId = requestId)) map {
        response =>
          response mustBe Left(IdmsException.clientNotFound(testClientId))
      }
    }

    "must return IdmsException for any non-2xx response" in {
      val context = Seq(
        "hipEnvironment" -> primaryEnvironment.id,
        "clientId" -> testClientId,
        "scopeId" -> testScopeId,
        "X-Correlation-Id" -> correlationId
      )

      forAll(nonSuccessResponses) { (status: Int) =>
        stubFor(
          put(urlEqualTo(s"/primary/identity/clients/$testClientId/client-scopes/$testScopeId"))
            .willReturn(
              aResponse()
                .withStatus(status)
            )
        )

        buildConnector(this).addClientScope(primaryEnvironment, testClientId, testScopeId)(HeaderCarrier(requestId = requestId)) map {
          response =>
            response mustBe Left(IdmsException.unexpectedResponse(status, context))
        }
      }
    }

    "must return IdmsException for any errors" in {
      stubFor(
        put(urlEqualTo(s"/primary/identity/clients/$testClientId/client-scopes/$testScopeId"))
          .willReturn(
            aResponse()
              .withFault(Fault.CONNECTION_RESET_BY_PEER)
          )
      )

      buildConnector(this).addClientScope(primaryEnvironment, testClientId, testScopeId)(HeaderCarrier(requestId = requestId)) map {
        response =>
          response.left.value mustBe a[IdmsException]
          response.left.value.issue mustBe CallError
      }
    }
  }

  "IdmsConnector.deleteClientScope" - {
    "must place the correct request per environment to IDMS" in {
      forAll(hipEnvironmentsTable(this)) { (hipEnvironment: HipEnvironment) =>
        stubFor(
          delete(urlEqualTo(s"/${hipEnvironment.environmentName}/identity/clients/${hipEnvironment.clientId}/client-scopes/$testScopeId"))
            .withHeader("Authorization", equalTo(authorizationHeaderFor(hipEnvironment)))
            .withHeader("x-api-key", apiKeyHeaderPatternFor(hipEnvironment))
            .willReturn(
              aResponse()
                .withStatus(200)
            )
        )

        buildConnector(this).deleteClientScope(hipEnvironment, hipEnvironment.clientId, testScopeId)(HeaderCarrier(requestId = requestId)) map {
          response => response mustBe Right(())
        }
      }
    }

    "must throw IdmsException with an IdmsIssue of ClientNotFound when IDMS returns 404 Not Found" in {
      stubFor(
        delete(urlEqualTo(s"/primary/identity/clients/$testClientId/client-scopes/$testScopeId"))
          .willReturn(
            aResponse()
              .withStatus(404)
          )
      )

      buildConnector(this).deleteClientScope(primaryEnvironment, testClientId, testScopeId)(HeaderCarrier(requestId = requestId)) map {
        response =>
          response mustBe Left(IdmsException.clientNotFound(testClientId))
      }
    }

    "must return IdmsException for any non-2xx response" in {
      val context = Seq(
        "hipEnvironment" -> primaryEnvironment.id,
        "clientId" -> testClientId,
        "scopeId" -> testScopeId,
        "X-Correlation-Id" -> correlationId
      )

      forAll(nonSuccessResponses) { (status: Int) =>
        stubFor(
          delete(urlEqualTo(s"/primary/identity/clients/$testClientId/client-scopes/$testScopeId"))
            .willReturn(
              aResponse()
                .withStatus(status)
            )
        )

        buildConnector(this).deleteClientScope(primaryEnvironment, testClientId, testScopeId)(HeaderCarrier(requestId = requestId)) map {
          response =>
            response mustBe Left(IdmsException.unexpectedResponse(status, context))
        }
      }
    }

    "must return IdmsException for any errors" in {
      stubFor(
        delete(urlEqualTo(s"/primary/identity/clients/$testClientId/client-scopes/$testScopeId"))
          .willReturn(
            aResponse()
              .withFault(Fault.CONNECTION_RESET_BY_PEER)
          )
      )

      buildConnector(this).deleteClientScope(primaryEnvironment, testClientId, testScopeId)(HeaderCarrier(requestId = requestId)) map {
        response =>
          response.left.value mustBe a[IdmsException]
          response.left.value.issue mustBe CallError
      }
    }
  }

  "IdmsConnector.fetchClientScopes" - {
    "must place the correct request per environment to IDMS and return the client scopes" in {
      val scopes = Seq(ClientScope("test-scope-1"), ClientScope("test-scope-2"))

      forAll(hipEnvironmentsTable(this)) { (hipEnvironment: HipEnvironment) =>
        stubFor(
          get(urlEqualTo(s"/${hipEnvironment.environmentName}/identity/clients/${hipEnvironment.clientId}/client-scopes"))
            .withHeader("Accept", equalTo("application/json"))
            .withHeader("Authorization", equalTo(authorizationHeaderFor(hipEnvironment)))
            .withHeader("x-api-key", apiKeyHeaderPatternFor(hipEnvironment))
            .withHeader("X-Correlation-Id", equalTo(correlationId))
            .willReturn(
              aResponse()
                .withBody(Json.toJson(scopes).toString())
            )
        )

        buildConnector(this).fetchClientScopes(hipEnvironment, hipEnvironment.clientId)(HeaderCarrier(requestId = requestId)) map {
          actual =>
            actual mustBe Right(scopes)
        }
      }
    }

    "must throw IdmsException with an IdmsIssue of ClientNotFound when IDMS returns 404 Not Found" in {
      stubFor(
        get(urlEqualTo(s"/primary/identity/clients/$testClientId/client-scopes"))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("X-Correlation-Id", equalTo(correlationId))
          .willReturn(
            aResponse()
              .withStatus(404)
          )
      )

      buildConnector(this).fetchClientScopes(primaryEnvironment, testClientId)(HeaderCarrier(requestId = requestId)) map {
        actual =>
          actual mustBe Left(IdmsException.clientNotFound(testClientId))
      }
    }

    "must return IdmsException for any non-2xx response" in {
      val context = Seq(
        "hipEnvironment" -> primaryEnvironment.id,
        "clientId" -> testClientId,
        "X-Correlation-Id" -> correlationId
      )

      forAll(nonSuccessResponses) { (status: Int) =>
        stubFor(
          get(urlEqualTo(s"/primary/identity/clients/$testClientId/client-scopes"))
            .withHeader("Accept", equalTo("application/json"))
            .withHeader("X-Correlation-Id", equalTo(correlationId))
            .willReturn(
              aResponse()
                .withStatus(status)
            )
        )

        buildConnector(this).fetchClientScopes(primaryEnvironment, testClientId)(HeaderCarrier(requestId = requestId)) map {
          actual =>
            actual mustBe Left(IdmsException.unexpectedResponse(status, context))
        }
      }
    }

    "must return IdmsException for any errors" in {
      stubFor(
        get(urlEqualTo(s"/primary/identity/clients/$testClientId/client-scopes"))
          .withHeader("Accept", equalTo("application/json"))
          .withHeader("X-Correlation-Id", equalTo(correlationId))
          .willReturn(
            aResponse()
              .withFault(Fault.CONNECTION_RESET_BY_PEER)
          )
      )

      buildConnector(this).fetchClientScopes(primaryEnvironment, testClientId)(HeaderCarrier(requestId = requestId)) map {
        actual =>
          actual.left.value mustBe a[IdmsException]
          actual.left.value.issue mustBe CallError
      }
    }
  }
}

object IdmsConnectorImplSpec extends HttpClientV2Support with TableDrivenPropertyChecks {

  def hipEnvironments(wireMockSupport: WireMockSupport): HipEnvironments = new HipEnvironments {
    override val environments: Seq[HipEnvironment] = Seq(
      HipEnvironment(
        id = "production",
        rank = 1,
        environmentName = FakeHipEnvironments.primaryEnvironment.environmentName,
        isProductionLike = true,
        apimUrl = s"http://${wireMockSupport.wireMockHost}:${wireMockSupport.wireMockPort}/primary",
        clientId = primaryClientId,
        secret = primarySecret,
        useProxy = false,
        apiKey = None
      ),
      HipEnvironment(
        id = "test",
        rank = 2,
        environmentName = FakeHipEnvironments.secondaryEnvironment.environmentName,
        isProductionLike = false,
        apimUrl = s"http://${wireMockSupport.wireMockHost}:${wireMockSupport.wireMockPort}/secondary",
        clientId = secondaryClientId,
        secret = secondarySecret,
        useProxy = false,
        apiKey = Some(secondaryApiKey)
      )
    )
  }

  def buildConnector(wireMockSupport: WireMockSupport)(implicit ec: ExecutionContext): IdmsConnector = {
    new IdmsConnectorImpl(httpClientV2, hipEnvironments(wireMockSupport))
  }

  def authorizationHeaderFor(hipEnvironment: HipEnvironment): String = {
    val encoded = if hipEnvironment.isProductionLike then "cHJpbWFyeS1jbGllbnQtaWQ6cHJpbWFyeS1zZWNyZXQ="
      else "c2Vjb25kYXJ5LWNsaWVudC1pZDpzZWNvbmRhcnktc2VjcmV0"

    s"Basic $encoded"
  }

  def apiKeyHeaderPatternFor(hipEnvironment: HipEnvironment): StringValuePattern = {
    if hipEnvironment.isProductionLike then absent()
    else equalTo(secondaryApiKey)
  }

  val primaryClientId: String = "primary-client-id"
  val primarySecret: String = "primary-secret"
  val secondaryClientId: String = "secondary-client-id"
  val secondarySecret: String = "secondary-secret"
  val secondaryApiKey: String = "secondary-api-key"
  val testClientId: String = "test-client-id"
  val testScopeId: String = "test-scope-id"
  val testSecret: Secret = Secret("test-secret")
  val testClient: Client = Client("test-name", "test-description")
  val testClientResponse: ClientResponse = ClientResponse(testClientId, testSecret.secret)

  def hipEnvironmentsTable(wireMockSupport: WireMockSupport): TableFor1[HipEnvironment] = Table(
    "hipEnvironment",
    hipEnvironments(wireMockSupport).environments*
  )

  val nonSuccessResponses: TableFor1[Int] = Table(
    "status",
    400,
    401,
    500
  )

}
