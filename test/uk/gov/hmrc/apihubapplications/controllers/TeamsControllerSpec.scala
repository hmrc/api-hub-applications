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
import uk.gov.hmrc.apihubapplications.models.exception.TeamNotFoundException
import uk.gov.hmrc.apihubapplications.models.team.{NewTeam, Team}
import uk.gov.hmrc.apihubapplications.models.team.TeamLenses._
import uk.gov.hmrc.apihubapplications.services.TeamsService
import uk.gov.hmrc.apihubapplications.utils.CryptoUtils
import uk.gov.hmrc.crypto.ApplicationCrypto

import java.time.{Clock, LocalDateTime}
import scala.concurrent.Future

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

      when(fixture.teamsService.create(eqTo(newTeam))).thenReturn(Future.successful(saved))

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