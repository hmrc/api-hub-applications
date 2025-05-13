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
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.connectors.EmailConnector
import uk.gov.hmrc.apihubapplications.models.application.TeamMember
import uk.gov.hmrc.apihubapplications.models.exception.*
import uk.gov.hmrc.apihubapplications.models.requests.TeamMemberRequest
import uk.gov.hmrc.apihubapplications.models.team.TeamLenses.*
import uk.gov.hmrc.apihubapplications.models.team.{AddEgressesRequest, NewTeam, RenameTeamRequest, Team}
import uk.gov.hmrc.apihubapplications.repositories.TeamsRepository
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future
import scala.util.Right

class TeamsServiceSpec
  extends AsyncFreeSpec
    with Matchers
    with MockitoSugar
    with EitherValues {

  import TeamsServiceSpec.*

  val userEmail = "user@example.com"

  "create" - {
    "must transform NewTeam to Team, pass it to the repository, and return the saved result" in {
      val fixture = buildFixture()

      val newTeam = NewTeam("test-team-name", Seq(teamMember1, teamMember2))
      val team = newTeam.toTeam(fixture.clock)
      val savedTeam = team.setId("test-id")

      when(fixture.repository.insert(eqTo(team))).thenReturn(Future.successful(Right(savedTeam)))
      when(fixture.emailConnector.sendTeamMemberAddedEmailToTeamMembers(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.eventsService.create(eqTo(savedTeam), eqTo(userEmail))).thenReturn(Future.successful(()))

      fixture.service.create(newTeam, userEmail)(any).map {
        result =>
          result.value mustBe savedTeam
      }
    }

    "must send appropriate emails if repository inserts successfully" in {
      val fixture = buildFixture()

      val newTeam = NewTeam("test-team-name", Seq(teamMember1, teamMember2))
      val team = newTeam.toTeam(fixture.clock)
      val savedTeam = team.setId("test-id")

      when(fixture.repository.insert(eqTo(team))).thenReturn(Future.successful(Right(savedTeam)))
      when(fixture.emailConnector.sendTeamMemberAddedEmailToTeamMembers(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.eventsService.create(eqTo(savedTeam), eqTo(userEmail))).thenReturn(Future.successful(()))

      fixture.service.create(newTeam, userEmail)(any).map {
        result =>
          verify(fixture.emailConnector).sendTeamMemberAddedEmailToTeamMembers(eqTo(Seq(teamMember1, teamMember2)), eqTo(savedTeam))(any)

          result.value mustBe savedTeam
      }
    }

    "must tolerate email failure and treat as success" in {
      val fixture = buildFixture()

      val newTeam = NewTeam("test-team-name", Seq(teamMember1, teamMember2))
      val team = newTeam.toTeam(fixture.clock)
      val savedTeam = team.setId("test-id")

      when(fixture.repository.insert(eqTo(team))).thenReturn(Future.successful(Right(savedTeam)))
      when(fixture.emailConnector.sendTeamMemberAddedEmailToTeamMembers(any, any)(any)).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))
      when(fixture.eventsService.create(eqTo(savedTeam), eqTo(userEmail))).thenReturn(Future.successful(()))

      fixture.service.create(newTeam, userEmail)(any).map {
        result =>
          verify(fixture.emailConnector).sendTeamMemberAddedEmailToTeamMembers(eqTo(Seq(teamMember1, teamMember2)), eqTo(savedTeam))(any)

          result.value mustBe savedTeam
      }
    }

    "must return an exception when returned by the repository" in {
      val fixture = buildFixture()

      val newTeam = NewTeam("test-team-name", Seq(teamMember1, teamMember2))

      when(fixture.repository.insert(any)).thenReturn(Future.successful(Left(applicationException)))

      fixture.service.create(newTeam, userEmail)(any).map {
        result =>
          result.left.value mustBe applicationException
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

  "findById" - {
    "must return the correct Team when it exists in the repository" in {
      val fixture = buildFixture()

      val id = "test-id"
      val team = team1.setId(id)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team)))

      fixture.service.findById(id).map {
        result =>
          result mustBe Right(team)
      }
    }

    "must return TeamNotFoundException when the Team does not exist in the repository" in {
      val fixture = buildFixture()

      val id = "test-id"

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Left(TeamNotFoundException.forId(id))))

      fixture.service.findById(id).map {
        result =>
          result mustBe Left(TeamNotFoundException.forId(id))
      }
    }
  }

  "findByName" - {
    "must return the value from the repository" in {
      val fixture = buildFixture()

      when(fixture.repository.findByName(any)).thenReturn(Future.successful(Some(team1)))

      fixture.service.findByName(team1.name).map {
        result =>
          result mustBe Some(team1)
          verify(fixture.repository).findByName(eqTo(team1.name))
          succeed
      }
    }
  }

  "addTeamMember" - {
    "must add the new team member to the team" in {
      val fixture = buildFixture()

      val id = "test-id"
      val team = team1.setId(id)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team)))
      when(fixture.repository.update(any)).thenReturn(Future.successful(Right(())))
      when(fixture.emailConnector.sendTeamMemberAddedEmailToTeamMembers(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.eventsService.addMember(any, eqTo(userEmail), eqTo(teamMember3.email), any)).thenReturn(Future.successful(()))

      fixture.service.addTeamMember(id, TeamMemberRequest(teamMember3.email), userEmail)(HeaderCarrier()).map {
        result =>
          verify(fixture.repository).update(eqTo(team.addTeamMember(teamMember3)))
          result mustBe Right(())
      }
    }

    "must return TeamMemberExistsException if the team member already exists within the team" in {
      val fixture = buildFixture()

      val id = "test-id"
      val team = team1.setId(id)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team)))

      fixture.service.addTeamMember(id, TeamMemberRequest(teamMember1.email), userEmail)(HeaderCarrier()).map {
        result =>
          result mustBe Left(TeamMemberExistsException.forTeam(team))
      }
    }

    "must return any exception returned by the repository" in {
      val fixture = buildFixture()

      val id = "test-id"
      val team = team1.setId(id)
      val expected = TeamNotFoundException.forTeam(team)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team)))
      when(fixture.repository.update(any)).thenReturn(Future.successful(Left(expected)))

      fixture.service.addTeamMember(id, TeamMemberRequest(teamMember3.email), userEmail)(HeaderCarrier()).map {
        result =>
          result mustBe Left(expected)
      }
    }

    "must send appropriate email if repository updates successfully" in {
      val fixture = buildFixture()

      val id = "test-id"
      val team = team1.setId(id)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team)))
      when(fixture.repository.update(any)).thenReturn(Future.successful(Right(())))
      when(fixture.emailConnector.sendTeamMemberAddedEmailToTeamMembers(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.eventsService.addMember(any, eqTo(userEmail), eqTo(teamMember3.email), any)).thenReturn(Future.successful(()))

      fixture.service.addTeamMember(id, TeamMemberRequest(teamMember3.email), userEmail)(HeaderCarrier()).map {
        result =>
          verify(fixture.repository).update(eqTo(team.addTeamMember(teamMember3)))
          verify(fixture.emailConnector).sendTeamMemberAddedEmailToTeamMembers(eqTo(Seq(teamMember3)), eqTo(team))(any)

          result mustBe Right(())
      }

    }

    "must tolerate email failure and treat as success" in {
      val fixture = buildFixture()

      val id = "test-id"
      val team = team1.setId(id)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team)))
      when(fixture.repository.update(any)).thenReturn(Future.successful(Right(())))
      when(fixture.emailConnector.sendTeamMemberAddedEmailToTeamMembers(any, any)(any)).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))
      when(fixture.eventsService.addMember(any, eqTo(userEmail), eqTo(teamMember3.email), any)).thenReturn(Future.successful(()))

      fixture.service.addTeamMember(id, TeamMemberRequest(teamMember3.email), userEmail)(HeaderCarrier()).map {
        result =>
          verify(fixture.repository).update(eqTo(team.addTeamMember(teamMember3)))
          verify(fixture.emailConnector).sendTeamMemberAddedEmailToTeamMembers(eqTo(Seq(teamMember3)), eqTo(team))(any)

          result mustBe Right(())
      }
    }
  }

  "removeTeamMember" - {
    "must remove the team member from the team" in {
      val fixture = buildFixture()

      val id = "test-id"
      val team = team1
        .setId(id)
        .addTeamMember(teamMember1)
        .addTeamMember(teamMember3)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team)))
      when(fixture.repository.update(any)).thenReturn(Future.successful(Right(())))
      when(fixture.emailConnector.sendRemoveTeamMemberFromTeamEmail(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.eventsService.removeMember(any, eqTo(userEmail), eqTo(teamMember3.email))).thenReturn(Future.successful(()))

      fixture.service.removeTeamMember(id, teamMember3.email, userEmail)(HeaderCarrier()).map {
        result =>
          verify(fixture.repository).update(eqTo(team.removeTeamMember(teamMember3.email)))
          result mustBe Right(())
      }
    }

    "must prevent removing the last team member from the team" in {
      val fixture = buildFixture()

      val id = "test-id"
      val team = team3
        .setId(id)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team)))

      fixture.service.removeTeamMember(id, teamMember1.email, userEmail)(HeaderCarrier()).map {
        result =>
          result mustBe Left(LastTeamMemberException.forTeam(team))
      }
    }

    "must return TeamMemberDoesNotExistException if the team member is not part of the team" in {
      val fixture = buildFixture()

      val id = "test-id"
      val team = team1.setId(id)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team)))

      fixture.service.removeTeamMember(id, teamMember3.email, userEmail)(HeaderCarrier()).map {
        result =>
          result mustBe Left(TeamMemberDoesNotExistException.forTeam(team))
      }
    }

    "must return any exception returned by the repository" in {
      val fixture = buildFixture()

      val id = "test-id"
      val team = team1.setId(id)
      val expected = TeamNotFoundException.forTeam(team)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team)))
      when(fixture.repository.update(any)).thenReturn(Future.successful(Left(expected)))
      when(fixture.emailConnector.sendRemoveTeamMemberFromTeamEmail(any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.service.removeTeamMember(id, teamMember1.email, userEmail)(HeaderCarrier()).map {
        result =>
          result mustBe Left(expected)
      }
    }
  }

  "renameTeam" - {
    "must lookup the team, update it, and persist" in {
      val fixture = buildFixture()

      val id = "test-id"
      val team = team1.setId(id)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team)))
      when(fixture.repository.update(any)).thenReturn(Future.successful(Right(())))
      when(fixture.eventsService.rename(any, eqTo(userEmail), eqTo(team.name))).thenReturn(Future.successful(()))

      fixture.service.renameTeam(id, RenameTeamRequest("new name"), userEmail).map {
        result =>
          verify(fixture.repository).update(eqTo(team.setName("new name")))
          result mustBe Right(())
      }
    }

    "must return an exception when returned by the repository" in {
      val fixture = buildFixture()

      val id = "test-id"
      val team = team1.setId(id)
      val expected = TeamNotFoundException.forTeam(team)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team)))
      when(fixture.repository.update(any)).thenReturn(Future.successful(Left(expected)))

      fixture.service.renameTeam(id, RenameTeamRequest("new name"), userEmail).map {
        result =>
          result mustBe Left(expected)
      }
    }
  }
  "addEgressesToTeam" - {
    "must add the set of egresses to the team when the team has no egresses" in {
      val fixture = buildFixture()

      val id = "test-id"
      val team = team1.setId(id)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team)))
      when(fixture.repository.update(any)).thenReturn(Future.successful(Right(())))
      when(fixture.eventsService.addEgresses(any, eqTo(userEmail), any, any)).thenReturn(Future.successful(()))

      val request = AddEgressesRequest(Seq("egress1", "egress1", "egress2"))
      fixture.service.addEgressesToTeam(id, request, userEmail).map {
        result =>
          verify(fixture.repository).update(eqTo(team.copy(egresses = Seq("egress1", "egress2"))))
          result mustBe Right(())
      }
    }

    "must add the set of egresses to the team when the team has some egresses" in {
      val fixture = buildFixture()

      val id = "test-id"
      val team = team1.setId(id)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team.addEgresses(Seq("egress1","egress2")))))
      when(fixture.repository.update(any)).thenReturn(Future.successful(Right(())))
      when(fixture.eventsService.addEgresses(any, eqTo(userEmail), any, any)).thenReturn(Future.successful(()))

      val request = AddEgressesRequest(Seq("egress2","egress3"))
      fixture.service.addEgressesToTeam(id, request, userEmail).map {
        result =>
          verify(fixture.repository).update(eqTo(team.copy(egresses = Seq("egress1","egress2","egress3"))))
          result mustBe Right(())
      }
    }

  }

  "removeEgressFromTeam" - {
    "must remove the egress from the team when it exists" in {
      val fixture = buildFixture()

      val id = "test-id"
      val egressId1 = "test-egress-id-1"
      val egressId2 = "test-egress-id-2"

      val team = team1
        .setId(id)
        .addEgresses(Seq(egressId1, egressId2))

      val expected = team1
        .setId(id)
        .addEgresses(Seq(egressId2))

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team)))
      when(fixture.repository.update(any)).thenReturn(Future.successful(Right(())))
      when(fixture.eventsService.removeEgress(any, eqTo(userEmail), eqTo(egressId1), any)).thenReturn(Future.successful(()))

      fixture.service.removeEgressFromTeam(id, egressId1, userEmail).map {
        result =>
          verify(fixture.repository).update(eqTo(expected))
          result mustBe Right(())
      }
    }

    "must return EgressNotFoundException when the egress has not been added to the team" in {
      val fixture = buildFixture()

      val id = "test-id"
      val egressId1 = "test-egress-id-1"
      val egressId2 = "test-egress-id-2"

      val team = team1
        .setId(id)
        .addEgresses(Seq(egressId2))

      val expected = EgressNotFoundException.forTeamAndEgress(team, egressId1)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Right(team)))

      fixture.service.removeEgressFromTeam(id, egressId1, userEmail).map {
        result =>
          verify(fixture.repository, never()).update(any)
          result mustBe Left(expected)
      }
    }

    "must return TeamNotFoundException when the team does not exist in the repository" in {
      val fixture = buildFixture()

      val id = "test-id"
      val egressId = "test-egress-id-1"

      val team = team1.setId(id)

      val expected = TeamNotFoundException.forTeam(team)

      when(fixture.repository.findById(eqTo(id))).thenReturn(Future.successful(Left(expected)))

      fixture.service.removeEgressFromTeam(id, egressId, userEmail).map {
        result =>
          verify(fixture.repository, never()).update(any)
          result mustBe Left(expected)
      }
    }
  }

  private case class Fixture(
                              repository: TeamsRepository,
                              clock: Clock,
                              service: TeamsService,
                              emailConnector: EmailConnector,
                              eventsService: TeamsEventService
                            )

  private def buildFixture(): Fixture = {
    val repository = mock[TeamsRepository]
    val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
    val emailConnector = mock[EmailConnector]
    val eventsService = mock[TeamsEventService]

    val service = new TeamsService(repository, clock, emailConnector, eventsService)

    Fixture(repository, clock, service, emailConnector, eventsService)
  }

}

object TeamsServiceSpec {

  val teamMember1: TeamMember = TeamMember("test-team-member-1")
  val teamMember2: TeamMember = TeamMember("test-team-member-2")
  val teamMember3: TeamMember = TeamMember("test-team-member-3")
  val teamMember4: TeamMember = TeamMember("test-team-member-4")

  val team1: Team = Team("test-team-1", Seq(teamMember1, teamMember2), created = Some(LocalDateTime.now()))
  val team2: Team = Team("test-team-2", Seq(teamMember3, teamMember4), created = Some(LocalDateTime.now()))
  val team3: Team = Team("test-team-3", Seq(teamMember1), created = Some(LocalDateTime.now()))

  val applicationException: ApplicationsException = new ApplicationsException("test-message", null) {}

}
