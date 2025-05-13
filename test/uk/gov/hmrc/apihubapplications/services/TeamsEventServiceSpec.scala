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

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{verify, when}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.models.application.TeamMember
import uk.gov.hmrc.apihubapplications.models.event.{Created, EgressAdded, EgressRemoved, Event, MemberAdded, MemberRemoved, Renamed}
import uk.gov.hmrc.apihubapplications.models.team.{Team, TeamType}
import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future
import uk.gov.hmrc.apihubapplications.models.event

class TeamsEventServiceSpec
  extends AsyncFreeSpec
    with Matchers
    with MockitoSugar {

  "TeamsEventService" - {
    "create() logs the correct event" in {
      val fixture = buildFixture()

      fixture.service.create(fixture.team, "requestingUser").map {
        _ =>
          verify(fixture.eventsService).log(
            Event.newEvent(
              entityId = fixture.team.id.get,
              entityType = event.Team,
              eventType = Created,
              user = "requestingUser",
              timestamp = fixture.team.created,
              description = "",
              detail = "",
              parameters = event.Parameter("teamName", fixture.team.name),
              event.Parameter("teamMembers", fixture.team.teamMembers.map(_.email)),
              event.Parameter("teamType", fixture.team.teamType.toString)
            )
          )
          succeed
      }
    }

    "addMember() logs the correct event" in {
      val fixture = buildFixture()
      val newMember = "new@example.com"

      fixture.service.addMember(fixture.team, "requestingUser", newMember).map {
        _ =>
          verify(fixture.eventsService).log(
            Event.newEvent(
              entityId = fixture.team.id.get,
              entityType = event.Team,
              eventType = MemberAdded,
              user = "requestingUser",
              timestamp = fixture.now,
              description = "",
              detail = "",
              parameters = event.Parameter("teamMember", newMember)
            )
          )
          succeed
      }
    }

    "removeMember() logs the correct event" in {
      val fixture = buildFixture()
      val removedMember = "removed@example.com"

      fixture.service.removeMember(fixture.team, "requestingUser", removedMember).map {
        _ =>
          verify(fixture.eventsService).log(
            Event.newEvent(
              entityId = fixture.team.id.get,
              entityType = event.Team,
              eventType = MemberRemoved,
              user = "requestingUser",
              timestamp = fixture.now,
              description = "",
              detail = "",
              parameters = event.Parameter("teamMember", removedMember)
            )
          )
          succeed
      }
    }

    "rename() logs the correct event" in {
      val fixture = buildFixture()
      val oldName = "old team name"

      fixture.service.rename(fixture.team, "requestingUser", oldName).map {
        _ =>
          verify(fixture.eventsService).log(
            Event.newEvent(
              entityId = fixture.team.id.get,
              entityType = event.Team,
              eventType = Renamed,
              user = "requestingUser",
              timestamp = fixture.now,
              description = "",
              detail = "",
              parameters = event.Parameter("oldName", oldName), event.Parameter("newName", fixture.team.name)
            )
          )
          succeed
      }
    }

    "addEgresses() logs the correct event" in {
      val fixture = buildFixture()
      val egresses = Seq("egress1", "egress2")

      fixture.service.addEgresses(fixture.team, "requestingUser", egresses).map {
        _ =>
          verify(fixture.eventsService).log(
            Event.newEvent(
              entityId = fixture.team.id.get,
              entityType = event.Team,
              eventType = EgressAdded,
              user = "requestingUser",
              timestamp = fixture.now,
              description = "",
              detail = "",
              parameters = event.Parameter("egresses", egresses)
            )
          )
          succeed
      }
    }

    "removeEgress() logs the correct event" in {
      val fixture = buildFixture()
      val egress = "egress to remove"

      fixture.service.removeEgress(fixture.team, "requestingUser", egress).map {
        _ =>
          verify(fixture.eventsService).log(
            Event.newEvent(
              entityId = fixture.team.id.get,
              entityType = event.Team,
              eventType = EgressRemoved,
              user = "requestingUser",
              timestamp = fixture.now,
              description = "",
              detail = "",
              parameters = event.Parameter("egress", egress)
            )
          )
          succeed
      }
    }
  }

  private case class Fixture(service: TeamsEventService, eventsService: EventsService, now: LocalDateTime, team: Team)

  private def buildFixture(): Fixture = {
    val eventsService = mock[EventsService]
    val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
    val now = LocalDateTime.now(clock)
    val service = new TeamsEventService(eventsService, clock)
    val team = Team(
      Some("test-team-id"), "test-team-name", LocalDateTime.now(), Seq(TeamMember("member@example.com")), TeamType.ConsumerTeam, Seq("egress1")
    )
    when(eventsService.log(any())).thenReturn(Future.successful(()))

    Fixture(service, eventsService, now, team)
  }

}
