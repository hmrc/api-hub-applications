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

package uk.gov.hmrc.apihubapplications.controllers

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.{Application => PlayApplication}
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.application.TeamMember
import uk.gov.hmrc.apihubapplications.models.exception.{TeamMemberExistsException, TeamNameNotUniqueException, TeamNotFoundException}
import uk.gov.hmrc.apihubapplications.models.requests.TeamMemberRequest
import uk.gov.hmrc.apihubapplications.models.team.TeamLenses._
import uk.gov.hmrc.apihubapplications.models.team.{NewTeam, RenameTeamRequest, Team}
import uk.gov.hmrc.apihubapplications.services.TeamsService
import uk.gov.hmrc.apihubapplications.utils.CryptoUtils
import uk.gov.hmrc.crypto.ApplicationCrypto

import java.time.{Clock, LocalDateTime}
import scala.concurrent.Future
import uk.gov.hmrc.apihubapplications.models.exception.TeamMemberDoesNotExistException
import uk.gov.hmrc.apihubapplications.models.exception.LastTeamMemberException

class TeamsControllerSpec
  extends AnyFreeSpec
  with Matchers
  with MockitoSugar
  with ArgumentMatchersSugar
  with OptionValues
  with CryptoUtils {

  import TeamsControllerSpec._

  "create" - {
    "must create the team via the service and return 201 Created and the saved team as JSON" in {
      val fixture = buildFixture()

      val newTeam = NewTeam("test-team-name", Seq(teamMember1, teamMember2))
      val saved = newTeam.toTeam(Clock.systemDefaultZone()).setId("test-id")

      when(fixture.teamsService.create(eqTo(newTeam))(any)).thenReturn(Future.successful(Right(saved)))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.create())
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(newTeam))

        val result = route(fixture.application, request).value

        status(result) mustBe CREATED
        contentAsJson(result) mustBe Json.toJson(saved)
      }
    }

    "must return 400 BadRequest when the request body is not a valid NewTeam" in {
      val fixture = buildFixture()

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.create())
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.obj())

        val result = route(fixture.application, request).value

        status(result) mustBe BAD_REQUEST
        verifyZeroInteractions(fixture.teamsService)
      }
    }

    "must return 409 Conflict when the team name is not unique" in {
      val fixture = buildFixture()

      val newTeam = NewTeam("test-team-name", Seq(teamMember1, teamMember2))
      val exception = TeamNameNotUniqueException.forName(newTeam.name)

      when(fixture.teamsService.create(any)(any)).thenReturn(Future.successful(Left(exception)))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.create())
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(newTeam))

        val result = route(fixture.application, request).value

        status(result) mustBe CONFLICT
      }
    }
  }

  "findAll" - {
    "must return 200 Ok and all teams returned by the service when no teamMember is specified" in {
      val fixture = buildFixture()

      when(fixture.teamsService.findAll(eqTo(None))).thenReturn(Future.successful(Seq(team1, team2, team3)))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.findAll(None))
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(Seq(team1, team2, team3))
      }
    }

    "must return 200 Ok and all teams returned by the service when a teamMember is specified" in {
      val fixture = buildFixture()

      when(fixture.teamsService.findAll(eqTo(Some(teamMember1.email)))).thenReturn(Future.successful(Seq(team1, team3)))

      running(fixture.application) {
        val crypto = fixture.application.injector.instanceOf[ApplicationCrypto]
        val request = FakeRequest(routes.TeamsController.findAll(Some(encrypt(crypto, teamMember1.email))))
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(Seq(team1, team3))
      }
    }
  }

  "findById" - {
    "must return 200 Ok and the Team when it exists" in {
      val fixture = buildFixture()
      val id = "test-id"
      val team = team1.setId(id)

      when(fixture.teamsService.findById(id)).thenReturn(Future.successful(Right(team)))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.findById(id))
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(team)
      }
    }

    "must return 404 Not Found when the Team does not exist" in {
      val fixture = buildFixture()
      val id = "test-id"

      when(fixture.teamsService.findById(eqTo(id))).thenReturn(Future.successful(Left(TeamNotFoundException.forId(id))))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.findById(id))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }
  }

  "findByName" - {
    "must return 200 OK and the team as JSON when it exists in the repository" in {
      val fixture = buildFixture()

      when(fixture.teamsService.findByName(any)).thenReturn(Future.successful(Some(team1)))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.findByName(team1.name))
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(team1)
        verify(fixture.teamsService).findByName(eqTo(team1.name))
      }
    }

    "must return 404 Not Found when a team cannot be found" in {
      val fixture = buildFixture()

      when(fixture.teamsService.findByName(any)).thenReturn(Future.successful(None))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.findByName(team1.name))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }
  }

  "addTeamMember" - {
    "must add the team member and return 204 No Content when the request is valid" in {
      val fixture = buildFixture()
      val id = "test-id"
      val teamMemberRequest = TeamMemberRequest(teamMember1.email)

      when(fixture.teamsService.addTeamMember(any, any)(any)).thenReturn(Future.successful(Right(())))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.addTeamMember(id))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(teamMemberRequest))

        val result = route(fixture.application, request).value

        status(result) mustBe NO_CONTENT
        verify(fixture.teamsService).addTeamMember(eqTo(id), eqTo(teamMemberRequest))(any)
      }
    }

    "must return 404 Not Found when the service returns TeamNotFoundException" in {
      val fixture = buildFixture()
      val id = "test-id"
      val teamMemberRequest = TeamMemberRequest(teamMember1.email)

      when(fixture.teamsService.addTeamMember(any, any)(any))
        .thenReturn(Future.successful(Left(TeamNotFoundException.forId(id))))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.addTeamMember(id))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(teamMemberRequest))

        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return 400 Bad Request when the team member already exists in the team" in {
      val fixture = buildFixture()
      val id = "test-id"
      val teamMemberRequest = TeamMemberRequest(teamMember1.email)

      when(fixture.teamsService.addTeamMember(any, any)(any))
        .thenReturn(Future.successful(Left(TeamMemberExistsException.forId(id))))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.addTeamMember(id))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(teamMemberRequest))

        val result = route(fixture.application, request).value

        status(result) mustBe BAD_REQUEST
      }
    }

    "must return 400 BadRequest when the request body is not a valid TeamMemberRequest" in {
      val fixture = buildFixture()
      val id = "test-id"

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.addTeamMember(id))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.obj())

        val result = route(fixture.application, request).value

        status(result) mustBe BAD_REQUEST
        verifyZeroInteractions(fixture.teamsService)
      }
    }
  }

  "removeTeamMember" - {
    "must remove the team member and return 204 No Content when the request is valid" in {
      val fixture = buildFixture()
      val crypto = fixture.application.injector.instanceOf[ApplicationCrypto]
      val id = "test-id"
      val encryptedEmail = encrypt(crypto, teamMember1.email)

      when(fixture.teamsService.removeTeamMember(any, any)(any)).thenReturn(Future.successful(Right(())))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.removeTeamMember(id, encryptedEmail))

        val result = route(fixture.application, request).value

        status(result) mustBe NO_CONTENT
        verify(fixture.teamsService).removeTeamMember(eqTo(id), eqTo(teamMember1.email))(any)
      }
    }

    "must return 409 Conflict when the team member is the last one on the team" in {
      val fixture = buildFixture()
      val crypto = fixture.application.injector.instanceOf[ApplicationCrypto]
      val encryptedEmail = encrypt(crypto, teamMember1.email)
      val id = "test-id"

      when(fixture.teamsService.removeTeamMember(any, any)(any))
        .thenReturn(Future.successful(Left(LastTeamMemberException.forTeam(team1))))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.removeTeamMember(id, encryptedEmail))

        val result = route(fixture.application, request).value

        status(result) mustBe CONFLICT
      }
    }

    "must return 404 Not Found when the service returns TeamNotFoundException" in {
      val fixture = buildFixture()
      val crypto = fixture.application.injector.instanceOf[ApplicationCrypto]
      val encryptedEmail = encrypt(crypto, teamMember1.email)
      val id = "test-id"

      when(fixture.teamsService.removeTeamMember(any, any)(any))
        .thenReturn(Future.successful(Left(TeamNotFoundException.forId(id))))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.removeTeamMember(id, encryptedEmail))

        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return 404 Not Found when the team member is not part of the team" in {
      val fixture = buildFixture()
      val crypto = fixture.application.injector.instanceOf[ApplicationCrypto]
      val encryptedEmail = encrypt(crypto, teamMember1.email)
      val id = "test-id"

      when(fixture.teamsService.removeTeamMember(any, any)(any))
        .thenReturn(Future.successful(Left(TeamMemberDoesNotExistException.forTeam(team1))))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.removeTeamMember(id, encryptedEmail))

        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }
  }

  "renameTeam" - {
    "must rename team member and return 204 No Content when the request is valid" in {
      val fixture = buildFixture()
      val id = "test-id"
      val renameTeamRequest = RenameTeamRequest("new name")

      when(fixture.teamsService.renameTeam(any, any)).thenReturn(Future.successful(Right(())))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.renameTeam(id))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(renameTeamRequest))

        val result = route(fixture.application, request).value

        status(result) mustBe NO_CONTENT
        verify(fixture.teamsService).renameTeam(eqTo(id), eqTo(renameTeamRequest))
      }
    }

    "must return 404 Not Found when the service returns TeamNotFoundException" in {
      val fixture = buildFixture()
      val id = "test-id"
      val renameTeamRequest = RenameTeamRequest("new name")

      when(fixture.teamsService.renameTeam(any, any))
        .thenReturn(Future.successful(Left(TeamNotFoundException.forId(id))))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.renameTeam(id))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(renameTeamRequest))

        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return 400 BadRequest when the request body is not a valid TeamMemberRequest" in {
      val fixture = buildFixture()
      val id = "test-id"

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.renameTeam(id))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.obj())

        val result = route(fixture.application, request).value

        status(result) mustBe BAD_REQUEST
        verifyZeroInteractions(fixture.teamsService)
      }
    }

    "must return 409 Conflict when the team name is not unique" in {
      val fixture = buildFixture()
      val id = "test-id"
      val renameTeamRequest = RenameTeamRequest("new name")

      val exception = TeamNameNotUniqueException.forName("new name")

      when(fixture.teamsService.renameTeam(any, any)).thenReturn(Future.successful(Left(exception)))

      running(fixture.application) {
        val request = FakeRequest(routes.TeamsController.renameTeam(id))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(renameTeamRequest))

        val result = route(fixture.application, request).value

        status(result) mustBe CONFLICT
      }
    }
  }

  private case class Fixture(application: PlayApplication, teamsService: TeamsService)

  private def buildFixture(): Fixture = {
    val teamsService = mock[TeamsService]

    val application = new GuiceApplicationBuilder()
      .overrides(
        bind[ControllerComponents].toInstance(stubControllerComponents()),
        bind[TeamsService].toInstance(teamsService),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction])
      )
      .build()

    Fixture(application, teamsService)
  }

}

object TeamsControllerSpec {

  val teamMember1: TeamMember = TeamMember("test-team-member-1")
  val teamMember2: TeamMember = TeamMember("test-team-member-2")
  val teamMember3: TeamMember = TeamMember("test-team-member-3")
  val teamMember4: TeamMember = TeamMember("test-team-member-4")

  val team1: Team = Team("test-team-1", LocalDateTime.now(), Seq(teamMember1, teamMember2))
  val team2: Team = Team("test-team-2", LocalDateTime.now(), Seq(teamMember3, teamMember4))
  val team3: Team = Team("test-team-3", LocalDateTime.now(), Seq(teamMember1))

}
