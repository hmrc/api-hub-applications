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
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor1}
import play.api.Configuration
import play.api.http.Status.ACCEPTED
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.connectors.{EmailConnector, EmailConnectorImpl, SendEmailRequest}
import uk.gov.hmrc.apihubapplications.models.exception.EmailException
import uk.gov.hmrc.apihubapplications.models.exception.EmailException.CallError
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.ExecutionContext

class EmailConnectorSpec
  extends AsyncFreeSpec
  with Matchers
  with WireMockSupport
  with TableDrivenPropertyChecks
  with EitherValues {

  import EmailConnectorSpec._

  "EmailConnector.sendAddTeamMemberEmail" - {
    "must place the correct request" in {
      val email1 = "test-email1@test.com"
      val email2 = "test-email1@test.com"
      val request = SendEmailRequest(Seq(email1, email2), addTeamMemberTemplateId)

      stubFor(
        post(urlEqualTo("/hmrc/email"))
          .withHeader("Content-Type", equalTo("application/json"))
          .withRequestBody(
            equalToJson(Json.toJson(request).toString())
          )
          .willReturn(
            aResponse()
              .withStatus(ACCEPTED)
          )
      )

      buildConnector(this).sendAddTeamMemberEmail(Seq(email1, email2))(new HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }

    "must return EmailException for any non-2xx response" in {
      forAll(nonSuccessResponses) {status: Int =>
        stubFor(
          post(urlEqualTo("/hmrc/email"))
            .willReturn(
              aResponse()
                .withStatus(status)
            )
        )

        buildConnector(this).sendAddTeamMemberEmail(Seq.empty)(new HeaderCarrier()) map {
          result =>
            result mustBe Left(EmailException.unexpectedResponse(status))
        }
      }
    }

    "must return EmailException for any errors" in {
      stubFor(
        post(urlEqualTo("/hmrc/email"))
          .willReturn(
            aResponse()
              .withFault(Fault.CONNECTION_RESET_BY_PEER)
          )
      )

      buildConnector(this).sendAddTeamMemberEmail(Seq.empty)(new HeaderCarrier()) map {
        result =>
          result.left.value mustBe a [EmailException]
          result.left.value.issue mustBe CallError
      }
    }
  }

}

object EmailConnectorSpec extends HttpClientV2Support with TableDrivenPropertyChecks {

  val addTeamMemberTemplateId: String = "test-add-team-member-template-id"

  def buildConnector(wireMockSupport: WireMockSupport)(implicit ec: ExecutionContext): EmailConnector = {
    val servicesConfig = new ServicesConfig(
      Configuration.from(Map(
        "microservice.services.email.host" -> wireMockSupport.wireMockHost,
        "microservice.services.email.port" -> wireMockSupport.wireMockPort,
        "microservice.services.email.addTeamMemberToApplicationTemplateId" -> addTeamMemberTemplateId
      ))
    )

    new EmailConnectorImpl(servicesConfig, httpClientV2)
  }

  val nonSuccessResponses: TableFor1[Int] = Table(
    "status",
    400,
    401,
    500
  )

}
