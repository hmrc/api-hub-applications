/*
 * Copyright 2023 HM Revenue & Customs
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
import com.github.tomakehurst.wiremock.http.Fault
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor1}
import play.api.Configuration
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.IdmsConnectorSpec.{buildConnector, environmentNames, nonSuccessResponses, testClient, testClientId, testClientResponse, testSecret}
import uk.gov.hmrc.apihubapplications.connectors.{IdmsConnector, IdmsConnectorImpl}
import uk.gov.hmrc.apihubapplications.models.WithName
import uk.gov.hmrc.apihubapplications.models.application.{EnvironmentName, Primary, Secondary}
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, IdmsException, Secret}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.ExecutionContext

class IdmsConnectorSpec
  extends AsyncFreeSpec
  with Matchers
  with WireMockSupport
  with TableDrivenPropertyChecks {

  "IdmsConnector.createClient" - {
    "must place the correct request per environment to IDMS and return the ClientResponse" in {
      forAll(environmentNames) { environmentName: EnvironmentName =>
        stubFor(
          post(urlEqualTo(s"/$environmentName/identity/clients"))
            .withHeader("Accept", equalTo("application/json"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(
              equalToJson(Json.toJson(testClient).toString())
            )
            .willReturn(
              aResponse()
                .withBody(Json.toJson(testClientResponse).toString())
            )
        )

        buildConnector(this).createClient(environmentName, testClient)(HeaderCarrier()) map {
          clientResponse =>
            clientResponse mustBe Right(testClientResponse)
        }
      }
    }

    "must return IdmsException for any non-2xx response" in {
      forAll(nonSuccessResponses) { status: Int =>
        stubFor(
          post(urlEqualTo(s"/primary/identity/clients"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(
              equalToJson(Json.toJson(testClient).toString())
            )
            .willReturn(
              aResponse()
                .withStatus(status)
            )
        )

        buildConnector(this).createClient(Primary, testClient)(HeaderCarrier()) map {
          result =>
            result mustBe Left(IdmsException())
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

      buildConnector(this).createClient(Primary, testClient)(HeaderCarrier()) map {
        result =>
          result mustBe Left(IdmsException())
      }
    }
  }

  "IdmsConnector.clientSecret" - {
    "must place the correct request per environment to IDMS and return the Secret" in {
      forAll(environmentNames) { environmentName: EnvironmentName =>
        stubFor(
          get(urlEqualTo(s"/$environmentName/identity/clients/$testClientId/client-secret"))
            .withHeader("Accept", equalTo("application/json"))
            .willReturn(
              aResponse()
                .withBody(Json.toJson(testSecret).toString())
            )
        )

        buildConnector(this).clientSecret(environmentName, testClientId)(HeaderCarrier()) map {
          secret =>
            secret mustBe Right(Some(testSecret))
        }
      }
    }

    "must return None when IDMS returns 404 Not Found for a given Client Id" in {
      stubFor(
        get(urlEqualTo(s"/primary/identity/clients/$testClientId/client-secret"))
          .willReturn(
            aResponse()
              .withStatus(404)
          )
      )

      buildConnector(this).clientSecret(Primary, testClientId)(HeaderCarrier()) map {
        secret =>
          secret mustBe Right(None)
      }
    }

    "must return IdmsException for any non-2xx or 404 response" in {
      forAll(nonSuccessResponses) { status: Int =>
        stubFor(
          get(urlEqualTo(s"/primary/identity/clients/$testClientId/client-secret"))
            .willReturn(
              aResponse()
                .withStatus(status)
            )
        )

        buildConnector(this).clientSecret(Primary, testClientId)(HeaderCarrier()) map {
          secret =>
            secret mustBe Left(IdmsException())
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

      buildConnector(this).clientSecret(Primary, testClientId)(HeaderCarrier()) map {
        secret =>
          secret mustBe Left(IdmsException())
      }
    }
  }

}

object IdmsConnectorSpec extends HttpClientV2Support with TableDrivenPropertyChecks {

  def buildConnector(wireMockSupport: WireMockSupport)(implicit ec: ExecutionContext): IdmsConnector = {
    val servicesConfig = new ServicesConfig(
      Configuration.from(Map(
        "microservice.services.idms-primary.host" -> wireMockSupport.wireMockHost,
        "microservice.services.idms-primary.port" -> wireMockSupport.wireMockPort,
        "microservice.services.idms-primary.path" -> "primary",
        "microservice.services.idms-secondary.host" -> wireMockSupport.wireMockHost,
        "microservice.services.idms-secondary.port" -> wireMockSupport.wireMockPort,
        "microservice.services.idms-secondary.path" -> "secondary"
      ))
    )

    new IdmsConnectorImpl(servicesConfig, httpClientV2)
  }

  val testClientId: String = "test-client-id"
  val testSecret: Secret = Secret("test-secret")
  val testClient: Client = Client("test-name", "test-description")
  val testClientResponse: ClientResponse = ClientResponse(testClientId, testSecret.secret)

  val environmentNames: TableFor1[WithName with EnvironmentName] = Table(
    "environment",
    Primary,
    Secondary
  )

  val nonSuccessResponses: TableFor1[Int] = Table(
    "status",
    400,
    401,
    500
  )

}
