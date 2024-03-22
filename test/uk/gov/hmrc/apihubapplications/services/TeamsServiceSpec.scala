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

package uk.gov.hmrc.apihubapplications.services

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.models.application.TeamMember
import uk.gov.hmrc.apihubapplications.models.team.{NewTeam, Team}
import uk.gov.hmrc.apihubapplications.models.team.TeamLenses._
import uk.gov.hmrc.apihubapplications.repositories.TeamsRepository

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future

class TeamsServiceSpec
  extends AsyncFreeSpec
  with Matchers
  with MockitoSugar
  with ArgumentMatchersSugar {

  import TeamsServiceSpec._

  "create" - {
    "must transform NewTeam to Team, pass it to the repository, and return the saved result" in {
      val fixture = buildFixture()

      val newTeam = NewTeam("test-team-name", Seq(teamMember1, teamMember2))
      val team = newTeam.toTeam(fixture.clock)
      val saved = team.setId("test-id")

      when(fixture.repository.insert(eqTo(team))).thenReturn(Future.successful(saved))

      fixture.service.create(newTeam).map {
        result =>
          result mustBe saved
      }
    }
  }

  "findAll" - {
    "must return the teams returned by the repository when no teamMember is specified" in {
      val fixture = buildFixture()

      when(fixture.repository.findAll(eqTo(None))).thenReturn(Future.successful(Seq(team1, team2, team3)))

      fixture.service.findAll(None).map {
        result =>
          result mustBe Seq(team1, team2, team3)
      }
    }

    "must return the teams returned by the repository when a teamMember is specified" in {
      val fixture = buildFixture()

      when(fixture.repository.findAll(eqTo(Some(teamMember1.email)))).thenReturn(Future.successful(Seq(team1, team3)))

      fixture.service.findAll(Some(teamMember1.email)).map {
        result =>
          result mustBe Seq(team1, team3)
      }
    }
  }

  private case class Fixture(
    repository: TeamsRepository,
    clock: Clock,
    service: TeamsService
  )

  private def buildFixture(): Fixture = {
    val repository = mock[TeamsRepository]
    val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
    val service = new TeamsService(repository, clock)

    Fixture(repository, clock, service)
  }

}

object TeamsServiceSpec {

  val teamMember1: TeamMember = TeamMember("test-team-member-1")
  val teamMember2: TeamMember = TeamMember("test-team-member-2")
  val teamMember3: TeamMember = TeamMember("test-team-member-3")
  val teamMember4: TeamMember = TeamMember("test-team-member-4")

  val team1: Team = Team("test-team-1", LocalDateTime.now(), Seq(teamMember1, teamMember2))
  val team2: Team = Team("test-team-2", LocalDateTime.now(), Seq(teamMember3, teamMember4))
  val team3: Team = Team("test-team-3", LocalDateTime.now(), Seq(teamMember1))

}
