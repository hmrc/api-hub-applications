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
import play.api.http.Status.{ACCEPTED, BAD_GATEWAY}
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.connectors.{EmailConnector, EmailConnectorImpl, SendEmailRequest}
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, Pending}
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator, TeamMember}
import uk.gov.hmrc.apihubapplications.models.exception.EmailException
import uk.gov.hmrc.apihubapplications.models.exception.EmailException.{CallError, UnexpectedResponse}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.time.LocalDateTime
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
      val request = SendEmailRequest(
        Seq(email1, email2),
        addTeamMemberTemplateId,
        Map(
          "applicationname" -> application.name,
          "creatorusername" -> application.createdBy.email
        )
      )

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

      buildConnector(this).sendAddTeamMemberEmail(application)(new HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }

    "must return EmailException for any non-2xx response" in {
      forAll(nonSuccessResponses) { status: Int =>
        stubFor(
          post(urlEqualTo("/hmrc/email"))
            .willReturn(
              aResponse()
                .withStatus(status)
            )
        )

        buildConnector(this).sendAddTeamMemberEmail(application)(new HeaderCarrier()) map {
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

      buildConnector(this).sendAddTeamMemberEmail(application)(new HeaderCarrier()) map {
        result =>
          result.left.value mustBe a[EmailException]
          result.left.value.issue mustBe CallError
      }
    }

    "must not call the Email API if the creator is the only team member" in {
      val applicationWithoutTeam = application.copy(teamMembers = Seq.empty)
      buildConnector(this).sendAddTeamMemberEmail(applicationWithoutTeam)(new HeaderCarrier()) map {
        _ =>
          verify(0, postRequestedFor(urlEqualTo("/hmrc/email")))
          succeed
      }
    }
  }

  "EmailConnector.sendApplicationDeletedEmailToUser" - {
    "must place the correct request" in {
      val aUser = "user@hmrc.gov.uk"
      val request = SendEmailRequest(
        Seq(aUser),
        deleteApplicationEmailToUserTemplateId,
        Map(
          "applicationname" -> application.name
        )
      )

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

      buildConnector(this).sendApplicationDeletedEmailToCurrentUser(application, aUser)(new HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }
  }

  "EmailConnector.sendApplicationCreatedEmailToCreator" - {
    "must place the correct request" in {
      val request = SendEmailRequest(
        Seq(application.createdBy.email),
        applicationCreatedEmailToCreatorTemplateId,
        Map(
          "applicationname" -> application.name
        )
      )

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

      buildConnector(this).sendApplicationCreatedEmailToCreator(application)(new HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }
  }

  "EmailConnector.sendApplicationDeletedEmailToTeam" - {
    "must place the correct request" in {
      val request = SendEmailRequest(
        Seq(email1, email2),
        deleteApplicationEmailToTeamTemplateId,
        Map(
          "applicationname" -> application.name
        )
      )

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

      buildConnector(this).sendApplicationDeletedEmailToTeam(application, "user@hmrc.gov.uk")(new HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }

    "must not send team email to user" in {
      val request = SendEmailRequest(
        Seq(email1, email2),
        deleteApplicationEmailToTeamTemplateId,
        Map(
          "applicationname" -> application.name
        )
      )

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

      buildConnector(this).sendApplicationDeletedEmailToTeam(application, "user@hmrc.gov.uk")(new HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }
  }

  "EmailConnector.sendAccessApprovedEmailToTeam" - {
    "must place the correct requests" in {
      val accessRequest = AccessRequest(
        id = Some("test-id"),
        applicationId = application.id.get,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(),
        requestedBy = "test-requested-by",
        decision = None
      )

      val request = SendEmailRequest(
        application.teamMembers.map(teamMember => teamMember.email),
        accessApprovedEmailToTeamTemplateId,
        Map(
          "applicationname" -> application.name,
          "apispecificationname" -> accessRequest.apiName
        )
      )

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

      buildConnector(this).sendAccessApprovedEmailToTeam(application, accessRequest)(new HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }

    "must handle non-2xx responses" in {
      val accessRequest = AccessRequest(
        id = Some("test-id"),
        applicationId = application.id.get,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(),
        requestedBy = "test-requested-by",
        decision = None
      )

      val request = SendEmailRequest(
        application.teamMembers.map(teamMember => teamMember.email),
        accessApprovedEmailToTeamTemplateId,
        Map(
          "applicationname" -> application.name,
          "apispecificationname" -> accessRequest.apiName
        )
      )

      stubFor(
        post(urlEqualTo("/hmrc/email"))
          .withHeader("Content-Type", equalTo("application/json"))
          .withRequestBody(
            equalToJson(Json.toJson(request).toString())
          )
          .willReturn(
            aResponse()
              .withStatus(BAD_GATEWAY)
          )
      )

      buildConnector(this).sendAccessApprovedEmailToTeam(application, accessRequest)(new HeaderCarrier()) map {
        response =>
          response mustBe Left(EmailException(s"Unexpected response $BAD_GATEWAY returned from Email API", null, UnexpectedResponse))
      }
    }
  }
}
object EmailConnectorSpec extends HttpClientV2Support with TableDrivenPropertyChecks {

  val addTeamMemberTemplateId: String = "test-add-team-member-template-id"
  val deleteApplicationEmailToUserTemplateId: String = "test-delete-application-to-user-template-id"
  val deleteApplicationEmailToTeamTemplateId: String = "test-delete-application-to-team-template-id"
  val applicationCreatedEmailToCreatorTemplateId: String = "test-application-created-to-creator-template-id"
  val accessApprovedEmailToTeamTemplateId: String = "test-access-approved-to-team-template-id"

  val email1: String = "test-email1@test.com"
  val email2: String = "test-email2@test.com"

  val application: Application = Application(
    Some("test-id"),
    "test-name",
    Creator("creator-email@test.com"),
    Seq(TeamMember(email1), TeamMember(email2))
  )

  def buildConnector(wireMockSupport: WireMockSupport)(implicit ec: ExecutionContext): EmailConnector = {
    val servicesConfig = new ServicesConfig(
      Configuration.from(Map(
        "microservice.services.email.host" -> wireMockSupport.wireMockHost,
        "microservice.services.email.port" -> wireMockSupport.wireMockPort,
        "microservice.services.email.addTeamMemberToApplicationTemplateId" -> addTeamMemberTemplateId,
        "microservice.services.email.deleteApplicationEmailToUserTemplateId" -> deleteApplicationEmailToUserTemplateId,
        "microservice.services.email.deleteApplicationEmailToTeamTemplateId" -> deleteApplicationEmailToTeamTemplateId,
        "microservice.services.email.applicationCreatedEmailToCreatorTemplateId" -> applicationCreatedEmailToCreatorTemplateId,
        "microservice.services.email.accessApprovedEmailToTeamTemplateId" -> accessApprovedEmailToTeamTemplateId
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

