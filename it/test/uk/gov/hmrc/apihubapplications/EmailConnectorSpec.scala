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

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.http.Fault
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor1}
import play.api.Configuration
import play.api.http.Status.{ACCEPTED, BAD_GATEWAY}
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.connectors.{EmailConnector, EmailConnectorImpl, SendEmailRequest}
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, AccessRequestApi, AccessRequestRequest, Pending}
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses.*
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator, TeamMember}
import uk.gov.hmrc.apihubapplications.models.exception.EmailException
import uk.gov.hmrc.apihubapplications.models.exception.EmailException.{CallError, UnexpectedResponse}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.testhelpers.{ApiDetailGenerators, FakeHipEnvironments}
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
    with EitherValues
    with ApiDetailGenerators {

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
      forAll(nonSuccessResponses) { (status: Int) =>
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
        applicationId = application.id.get,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(),
        requestedBy = "test-requested-by",
        environmentId = "test"
      ).setId(Some("test-id"))

      val request = SendEmailRequest(
        application.teamMembers.map(teamMember => teamMember.email),
        accessApprovedEmailToTeamTemplateId,
        Map(
          "applicationname" -> application.name,
          "apispecificationname" -> accessRequest.apiName,
          "environmentname" -> FakeHipEnvironments.testEnvironment.name
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
        applicationId = application.id.get,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(),
        requestedBy = "test-requested-by",
        environmentId = "test"
      ).setId(Some("test-id"))

      val request = SendEmailRequest(
        application.teamMembers.map(teamMember => teamMember.email),
        accessApprovedEmailToTeamTemplateId,
        Map(
          "applicationname" -> application.name,
          "apispecificationname" -> accessRequest.apiName,
          "environmentname" -> FakeHipEnvironments.testEnvironment.name
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

  "EmailConnector.sendAccessRequestSubmittedEmailToRequester" - {
    "must place the correct request" in {
      val accessRequest = AccessRequestApi(
        apiId = "test-api-id",
        apiName = "test-api-name",
        endpoints = Seq.empty
      )

      val accessRequestRequest = AccessRequestRequest(
        applicationId = application.id.get,
        supportingInformation = "",
        requestedBy = "test-requested-by",
        apis = Seq(accessRequest),
        environmentId = "test"
      )

      val request = SendEmailRequest(
        Seq(accessRequestRequest.requestedBy),
        accessRequestSubmittedEmailToRequesterTemplateId,
        Map(
          "applicationname" -> application.name,
          "apispecificationname" -> accessRequest.apiName,
          "environmentname" -> FakeHipEnvironments.testEnvironment.name
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

      buildConnector(this).sendAccessRequestSubmittedEmailToRequester(application, accessRequestRequest)(HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }

    "must handle non-2xx responses" in {
      val accessRequest = AccessRequestApi(
        apiId = "test-api-id",
        apiName = "test-api-name",
        endpoints = Seq.empty
      )

      val accessRequestRequest = AccessRequestRequest(
        applicationId = application.id.get,
        supportingInformation = "",
        requestedBy = "test-requested-by",
        apis = Seq(accessRequest),
        environmentId = "test"
      )

      stubFor(
        post(urlEqualTo("/hmrc/email"))
          .withHeader("Content-Type", equalTo("application/json"))
          .willReturn(
            aResponse()
              .withStatus(BAD_GATEWAY)
          )
      )

      buildConnector(this).sendAccessRequestSubmittedEmailToRequester(application, accessRequestRequest)(new HeaderCarrier()) map {
        response =>
          response mustBe Left(EmailException(s"Unexpected response $BAD_GATEWAY returned from Email API", null, UnexpectedResponse))
      }
    }
  }

  "EmailConnector.sendNewAccessRequestEmailToApprovers" - {
    "must place the correct request" in {
      val accessRequest = AccessRequestApi(
        apiId = "test-api-id",
        apiName = "test-api-name",
        endpoints = Seq.empty
      )

      val accessRequestRequest = AccessRequestRequest(
        applicationId = application.id.get,
        supportingInformation = "",
        requestedBy = "test-requested-by",
        apis = Seq(accessRequest),
        environmentId = "test"
      )

      val request = SendEmailRequest(
        Seq("dummy.test1@digital.hmrc.gov.uk", "dummy.test2@digital.hmrc.gov.uk"),
        newAccessRequestEmailToApproversTemplateId,
        Map(
          "applicationname" -> application.name,
          "apispecificationname" -> accessRequest.apiName,
          "environmentname" -> FakeHipEnvironments.testEnvironment.name
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

      buildConnector(this).sendNewAccessRequestEmailToApprovers(application, accessRequestRequest)(HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }

    "must handle non-2xx responses" in {
      val accessRequest = AccessRequestApi(
        apiId = "test-api-id",
        apiName = "test-api-name",
        endpoints = Seq.empty
      )

      val accessRequestRequest = AccessRequestRequest(
        applicationId = application.id.get,
        supportingInformation = "",
        requestedBy = "test-requested-by",
        apis = Seq(accessRequest),
        environmentId = "test"
      )

      stubFor(
        post(urlEqualTo("/hmrc/email"))
          .withHeader("Content-Type", equalTo("application/json"))
          .willReturn(
            aResponse()
              .withStatus(BAD_GATEWAY)
          )
      )

      buildConnector(this).sendNewAccessRequestEmailToApprovers(application, accessRequestRequest)(new HeaderCarrier()) map {
        response =>
          response mustBe Left(EmailException(s"Unexpected response $BAD_GATEWAY returned from Email API", null, UnexpectedResponse))
      }
    }
  }

  "EmailConnector.sendAccessRejectedEmailToTeam" - {
    "must place the correct requests" in {
      val accessRequest = AccessRequest(
        applicationId = application.id.get,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(),
        requestedBy = "test-requested-by",
        environmentId = "test"
      ).setId(Some("test-id"))

      val request = SendEmailRequest(
        application.teamMembers.map(teamMember => teamMember.email),
        accessRejectedEmailToTeamTemplateId,
        Map(
          "applicationname" -> application.name,
          "apispecificationname" -> accessRequest.apiName,
          "environmentname" -> FakeHipEnvironments.testEnvironment.name
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

      buildConnector(this).sendAccessRejectedEmailToTeam(application, accessRequest)(new HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }

    "must handle non-2xx responses" in {
      val accessRequest = AccessRequest(
        applicationId = application.id.get,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(),
        requestedBy = "test-requested-by",
        environmentId = "test"
      ).setId(Some("test-id"))

      val request = SendEmailRequest(
        application.teamMembers.map(teamMember => teamMember.email),
        accessRejectedEmailToTeamTemplateId,
        Map(
          "applicationname" -> application.name,
          "apispecificationname" -> accessRequest.apiName,
          "environmentname" -> FakeHipEnvironments.testEnvironment.name
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

      buildConnector(this).sendAccessRejectedEmailToTeam(application, accessRequest)(new HeaderCarrier()) map {
        response =>
          response mustBe Left(EmailException(s"Unexpected response $BAD_GATEWAY returned from Email API", null, UnexpectedResponse))
      }
    }
  }

  "EmailConnector.sendTeamMemberAddedEmailToTeamMember" - {
    val teamMemberEmail = "test@hmrc.digital.gov.uk"
    val testTeamName = "test_team_name"

    "must place the correct request" in {
      val request = SendEmailRequest(
        Seq(teamMemberEmail),
        teamMemberAddedToTeamTemplateId,
        Map(
          "teamname" -> testTeamName
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

      buildConnector(this).sendTeamMemberAddedEmailToTeamMembers(Seq(TeamMember(teamMemberEmail)), Team(testTeamName, Seq.empty, created = Some(LocalDateTime.now())))(new HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }

    "must handle non-2xx responses" in {
      stubFor(
        post(urlEqualTo("/hmrc/email"))
          .withHeader("Content-Type", equalTo("application/json"))
          .willReturn(
            aResponse()
              .withStatus(BAD_GATEWAY)
          )
      )

      buildConnector(this).sendTeamMemberAddedEmailToTeamMembers(Seq(TeamMember(teamMemberEmail)), Team(testTeamName, Seq.empty, created = Some(LocalDateTime.now())))(new HeaderCarrier()) map {
        response =>
          response mustBe Left(EmailException(s"Unexpected response $BAD_GATEWAY returned from Email API", null, UnexpectedResponse))
      }
    }
  }

  "EmailConnector.sendApiOwnershipChangedEmailToOldTeamMembers" - {
    val teamMemberEmail = "test@hmrc.digital.gov.uk"
    val apiname = "api name"
    val testTeamName = "test_team_name"
    val apiDetail = sampleApiDetail().copy(title = apiname)
    val newTeamName = "new team"

    "must place the correct request" in {
      val request = SendEmailRequest(
        Seq(teamMemberEmail),
        apiOwnershipChangedToOldTeamTemplateId,
        Map(
          "teamname" -> testTeamName,
          "apispecificationname" -> apiname,
          "otherteamname" -> newTeamName
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

      val currentTeam = Team(testTeamName, Seq(TeamMember(teamMemberEmail)), created = Some(LocalDateTime.now()))
      val newTeam = Team(newTeamName, Seq(TeamMember(teamMemberEmail)), created = Some(LocalDateTime.now()))
      buildConnector(this).sendApiOwnershipChangedEmailToOldTeamMembers(currentTeam, newTeam, apiDetail)(new HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }

    "must handle non-2xx responses" in {
      stubFor(
        post(urlEqualTo("/hmrc/email"))
          .withHeader("Content-Type", equalTo("application/json"))
          .willReturn(
            aResponse()
              .withStatus(BAD_GATEWAY)
          )
      )

      val currentTeam = Team(testTeamName, Seq(TeamMember(teamMemberEmail)), created = Some(LocalDateTime.now()))
      val newTeam = Team(newTeamName, Seq(TeamMember(teamMemberEmail)), created = Some(LocalDateTime.now()))

      buildConnector(this).sendApiOwnershipChangedEmailToOldTeamMembers(currentTeam, newTeam, apiDetail)(new HeaderCarrier()) map {
        response =>
          response mustBe Left(EmailException(s"Unexpected response $BAD_GATEWAY returned from Email API", null, UnexpectedResponse))
      }
    }
  }

  "EmailConnector.sendApiOwnershipChangedEmailToNewdTeamMembers" - {
    val teamMemberEmail = "test@hmrc.digital.gov.uk"
    val apiname = "api name"
    val testTeamName = "test_team_name"
    val apiDetail = sampleApiDetail().copy(title = apiname)

    "must place the correct request" in {
      val request = SendEmailRequest(
        Seq(teamMemberEmail),
        apiOwnershipChangedToNewTeamTemplateId,
        Map(
          "teamname" -> testTeamName,
          "apispecificationname" -> apiname
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

      buildConnector(this).sendApiOwnershipChangedEmailToNewTeamMembers(Team(testTeamName, Seq(TeamMember(teamMemberEmail)), created = Some(LocalDateTime.now())), apiDetail)(new HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }

    "must handle non-2xx responses" in {
      stubFor(
        post(urlEqualTo("/hmrc/email"))
          .withHeader("Content-Type", equalTo("application/json"))
          .willReturn(
            aResponse()
              .withStatus(BAD_GATEWAY)
          )
      )

      buildConnector(this).sendApiOwnershipChangedEmailToNewTeamMembers(Team(testTeamName, Seq(TeamMember(teamMemberEmail)), created = Some(LocalDateTime.now())), apiDetail)(new HeaderCarrier()) map {
        response =>
          response mustBe Left(EmailException(s"Unexpected response $BAD_GATEWAY returned from Email API", null, UnexpectedResponse))
      }
    }
  }

  "EmailConnector.sendRemoveTeamMemberFromTeamEmail" - {
    val teamMemberEmail = "test@hmrc.digital.gov.uk"
    val testTeamName = "test_team_name"

    "must place the correct request" in {
      val request = SendEmailRequest(
        Seq(teamMemberEmail),
        removeTeamMemberFromTeamTemplateId,
        Map(
          "teamname" -> testTeamName,
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

      buildConnector(this).sendRemoveTeamMemberFromTeamEmail(teamMemberEmail, Team(testTeamName, Seq(TeamMember(teamMemberEmail)), created = Some(LocalDateTime.now())))(new HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }

    "must handle non-2xx responses" in {
      stubFor(
        post(urlEqualTo("/hmrc/email"))
          .withHeader("Content-Type", equalTo("application/json"))
          .willReturn(
            aResponse()
              .withStatus(BAD_GATEWAY)
          )
      )

      buildConnector(this).sendRemoveTeamMemberFromTeamEmail(teamMemberEmail, Team(testTeamName, Seq(TeamMember(teamMemberEmail)), created = Some(LocalDateTime.now())))(new HeaderCarrier()) map {
        response =>
          response mustBe Left(EmailException(s"Unexpected response $BAD_GATEWAY returned from Email API", null, UnexpectedResponse))
      }
    }
  }

  "EmailConnector.sendApplicationOwnershipChangedEmailToOldTeamMembers" - {
    val teamMemberEmail = "test@hmrc.digital.gov.uk"
    val oldTeamMemberEmail = "test@hmrc.digital.gov.uk"
    val testTeamName = "test_team_name"
    val testOldTeamName = "test_old_team_name"
    val testApplicationName = application.name

    "must place the correct request" in {
      val request = SendEmailRequest(
        Seq(oldTeamMemberEmail),
        applicationOwnershipChangedToOldTeamTemplateId,
        Map(
          "teamname" -> testTeamName,
          "oldteamname" -> testOldTeamName,
          "applicationname" -> testApplicationName,
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

      val newTeam = Team(testTeamName, Seq(TeamMember(teamMemberEmail)), created = Some(LocalDateTime.now()))
      val oldTeam = Team(testOldTeamName, Seq(TeamMember(oldTeamMemberEmail)), created = Some(LocalDateTime.now()))
      buildConnector(this).sendApplicationOwnershipChangedEmailToOldTeamMembers(oldTeam, newTeam, application)(new HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }

    "must handle non-2xx responses" in {
      stubFor(
        post(urlEqualTo("/hmrc/email"))
          .withHeader("Content-Type", equalTo("application/json"))
          .willReturn(
            aResponse()
              .withStatus(BAD_GATEWAY)
          )
      )

      val newTeam = Team(testTeamName, Seq(TeamMember(teamMemberEmail)), created = Some(LocalDateTime.now()))
      val oldTeam = Team(testOldTeamName, Seq(TeamMember(teamMemberEmail)), created = Some(LocalDateTime.now()))
      buildConnector(this).sendApplicationOwnershipChangedEmailToOldTeamMembers(oldTeam, newTeam, application)(new HeaderCarrier()) map {
        response =>
          response mustBe Left(EmailException(s"Unexpected response $BAD_GATEWAY returned from Email API", null, UnexpectedResponse))
      }
    }
  }

  "EmailConnector.sendApplicationOwnershipChangedEmailToNewTeamMembers" - {
    val teamMemberEmail = "test@hmrc.digital.gov.uk"
    val testTeamName = "test_team_name"
    val testApplicationName = application.name

    "must place the correct request" in {
      val request = SendEmailRequest(
        Seq(teamMemberEmail),
        applicationOwnershipChangedToNewTeamTemplateId,
        Map(
          "teamname" -> testTeamName,
          "applicationname" -> testApplicationName,
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

      val newTeam = Team(testTeamName, Seq(TeamMember(teamMemberEmail)), created = Some(LocalDateTime.now()))
      buildConnector(this).sendApplicationOwnershipChangedEmailToNewTeamMembers(newTeam, application)(new HeaderCarrier()) map {
        response =>
          response mustBe Right(())
      }
    }

    "must handle non-2xx responses" in {
      stubFor(
        post(urlEqualTo("/hmrc/email"))
          .withHeader("Content-Type", equalTo("application/json"))
          .willReturn(
            aResponse()
              .withStatus(BAD_GATEWAY)
          )
      )

      val newTeam = Team(testTeamName, Seq(TeamMember(teamMemberEmail)), created = Some(LocalDateTime.now()))
      buildConnector(this).sendApplicationOwnershipChangedEmailToNewTeamMembers(newTeam, application)(new HeaderCarrier()) map {
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
  val accessRejectedEmailToTeamTemplateId: String = "test-access-rejected-to-team-template-id"
  val accessRequestSubmittedEmailToRequesterTemplateId: String = "test-access-request-submitted-to-requester-template-id"
  val newAccessRequestEmailToApproversTemplateId: String = "test-new-access-request-to-approvers-template-id"
  val teamMemberAddedToTeamTemplateId: String = "test-team-member-added-to-team-template-id"
  val apiOwnershipChangedToOldTeamTemplateId = "test-api-ownership-removed-to-team-template-id"
  val apiOwnershipChangedToNewTeamTemplateId = "test-api-ownership-given-to-team-template-id"
  val removeTeamMemberFromTeamTemplateId = "test-remove-team-member-from-team-template-id"
  val applicationOwnershipChangedToOldTeamTemplateId = "hipp_notify_application_old_owning_team"
  val applicationOwnershipChangedToNewTeamTemplateId = "hipp_notify_application_new_owning_team"

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
        "microservice.services.email.applicationCreatedEmailToCreatorTemplateId" -> applicationCreatedEmailToCreatorTemplateId,
        "microservice.services.email.accessApprovedEmailToTeamTemplateId" -> accessApprovedEmailToTeamTemplateId,
        "microservice.services.email.accessRejectedEmailToTeamTemplateId" -> accessRejectedEmailToTeamTemplateId,
        "microservice.services.email.accessRequestSubmittedEmailToRequesterTemplateId" -> accessRequestSubmittedEmailToRequesterTemplateId,
        "microservice.services.email.newAccessRequestEmailToApproversTemplateId" -> newAccessRequestEmailToApproversTemplateId,
        "microservice.services.email.approversTeamEmails" -> "dummy.test1@digital.hmrc.gov.uk,dummy.test2@digital.hmrc.gov.uk",
        "microservice.services.email.teamMemberAddedToTeamTemplateId" -> teamMemberAddedToTeamTemplateId,
        "microservice.services.email.apiOwnershipChangedToOldTeamTemplateId" -> apiOwnershipChangedToOldTeamTemplateId,
        "microservice.services.email.apiOwnershipChangedToNewTeamTemplateId" -> apiOwnershipChangedToNewTeamTemplateId,
        "microservice.services.email.removeTeamMemberFromTeamTemplateId" -> removeTeamMemberFromTeamTemplateId,
        "microservice.services.email.applicationOwnershipChangedToOldTeamTemplateId" -> applicationOwnershipChangedToOldTeamTemplateId,
        "microservice.services.email.applicationOwnershipChangedToNewTeamTemplateId" -> applicationOwnershipChangedToNewTeamTemplateId,
      ))
    )

    new EmailConnectorImpl(servicesConfig, httpClientV2, FakeHipEnvironments)
  }

  val nonSuccessResponses: TableFor1[Int] = Table(
    "status",
    400,
    401,
    500
  )

}

