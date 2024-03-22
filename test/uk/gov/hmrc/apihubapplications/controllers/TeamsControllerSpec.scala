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
import uk.gov.hmrc.apihubapplications.models.team.NewTeam
import uk.gov.hmrc.apihubapplications.models.team.TeamLenses._
import uk.gov.hmrc.apihubapplications.services.TeamsService

import java.time.Clock
import scala.concurrent.Future

class TeamsControllerSpec
  extends AnyFreeSpec
  with Matchers
  with MockitoSugar
  with ArgumentMatchersSugar
  with OptionValues {

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

}
