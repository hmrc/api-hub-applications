/*
 * Copyright 2025 HM Revenue & Customs
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
import uk.gov.hmrc.apihubapplications.models.application.{Api, Application, Creator, Credential, Endpoint}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.event
import uk.gov.hmrc.apihubapplications.models.event.{ApiAdded, ApiRemoved, CredentialCreated, CredentialRevoked, Deleted, Event, EventType, Parameter, Registered, ScopesFixed, TeamChanged}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.models.team.TeamLenses.*
import uk.gov.hmrc.apihubapplications.models.team.TeamType.ConsumerTeam
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments

import java.time.LocalDateTime
import scala.concurrent.Future

class ApplicationsEventServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar {

  import ApplicationsEventServiceSpec.*

  "register" - {
    "must log the correct event when the team uses a global team" in {
      val fixture = buildFixture()
      val application = applicationWithTeam(team)

      val expected = buildEvent(
        application = application,
        eventType = Registered,
        parameters =
          Parameter("applicationName", application.name),
          Parameter("teamId", team.safeId),
          Parameter("teamName", team.name)
      )

      when(fixture.eventService.log(any)).thenReturn(Future.successful(()))

      fixture.applicationsEventService.register(application, user, timestamp).map(
        _ =>
          verify(fixture.eventService).log(eqTo(expected))
          succeed
      )
    }

    "must log the correct event when the team has an embedded team" in {
      val fixture = buildFixture()

      val expected = buildEvent(
        application = application,
        eventType = Registered,
        parameters = Parameter("applicationName", application.name)
      )

      when(fixture.eventService.log(any)).thenReturn(Future.successful(()))

      fixture.applicationsEventService.register(application, user, timestamp).map(
        _ =>
          verify(fixture.eventService).log(eqTo(expected))
          succeed
      )
    }
  }

  "delete" - {
    "must log the correct event" in {
      val fixture = buildFixture()
      val softDeleted = true

      val expected = buildEvent(
        application = application,
        eventType = Deleted,
        parameters = Parameter("softDeleted", softDeleted)
      )

      when(fixture.eventService.log(any)).thenReturn(Future.successful(()))

      fixture.applicationsEventService.delete(application, softDeleted, user, timestamp).map(
        _ =>
          verify(fixture.eventService).log(eqTo(expected))
          succeed
      )
    }
  }

  "addApi" - {
    "must log the correct event" in {
      val fixture = buildFixture()

      val endpoints = api.endpoints.map(
        endpoint =>
          s"${endpoint.httpMethod} ${endpoint.path}"
      )

      val expected = buildEvent(
        application = application,
        eventType = ApiAdded,
        parameters =
          Parameter("apiId", api.id),
          Parameter("apiTitle", api.title),
          Parameter("endpoints", endpoints)
      )

      when(fixture.eventService.log(any)).thenReturn(Future.successful(()))

      fixture.applicationsEventService.addApi(application, api, user, timestamp).map(
        _ =>
          verify(fixture.eventService).log(eqTo(expected))
          succeed
      )
    }
  }

  "removeApi" - {
    "must log the correct event" in {
      val fixture = buildFixture()

      val expected = buildEvent(
        application = application,
        eventType = ApiRemoved,
        parameters =
          Parameter("apiId", api.id),
          Parameter("apiTitle", api.title)
      )

      when(fixture.eventService.log(any)).thenReturn(Future.successful(()))

      fixture.applicationsEventService.removeApi(application, api, user, timestamp).map(
        _ =>
          verify(fixture.eventService).log(eqTo(expected))
          succeed
      )
    }
  }

  "changeTeam" - {
    "must log the correct event when the old team is a global team" in {
      val fixture = buildFixture()
      val application = applicationWithTeam(team)

      val expected = buildEvent(
        application = application,
        eventType = TeamChanged,
        parameters =
          Parameter("newTeamId", team.safeId),
          Parameter("newTeamName", team.name),
          Parameter("oldTeamId", oldTeam.safeId),
          Parameter("oldTeamName", oldTeam.name)
      )

      when(fixture.eventService.log(any)).thenReturn(Future.successful(()))

      fixture.applicationsEventService.changeTeam(application, team, Some(oldTeam), user, timestamp).map(
        _ =>
          verify(fixture.eventService).log(eqTo(expected))
          succeed
      )
    }

    "must log the correct event when the old team was an embedded team" in {
      val fixture = buildFixture()
      val application = applicationWithTeam(team)

      val expected = buildEvent(
        application = application,
        eventType = TeamChanged,
        parameters =
          Parameter("newTeamId", team.safeId),
          Parameter("newTeamName", team.name)
      )

      when(fixture.eventService.log(any)).thenReturn(Future.successful(()))

      fixture.applicationsEventService.changeTeam(application, team, None, user, timestamp).map(
        _ =>
          verify(fixture.eventService).log(eqTo(expected))
          succeed
      )
    }
  }

  "createCredential" - {
    "must log the correct event" in {
      val fixture = buildFixture()

      val expected = buildEvent(
        application = application,
        eventType = CredentialCreated,
        parameters =
          Parameter("environmentId", credential.environmentId),
          Parameter("clientId", credential.clientId)
      )

      when(fixture.eventService.log(any)).thenReturn(Future.successful(()))

      fixture.applicationsEventService.createCredential(application, credential, user, timestamp).map(
        _ =>
          verify(fixture.eventService).log(eqTo(expected))
          succeed
      )
    }
  }

  "revokeCredential" - {
    "must log the correct event" in {
      val fixture = buildFixture()

      val expected = buildEvent(
        application = application,
        eventType = CredentialRevoked,
        parameters =
          Parameter("environmentId", credential.environmentId),
          Parameter("clientId", credential.clientId)
      )

      when(fixture.eventService.log(any)).thenReturn(Future.successful(()))

      fixture.applicationsEventService.revokeCredential(application, FakeHipEnvironments.productionEnvironment, credential.clientId, user, timestamp).map(
        _ =>
          verify(fixture.eventService).log(eqTo(expected))
          succeed
      )
    }
  }

  "fixScopes" - {
    "must log the correct event" in {
      val fixture = buildFixture()

      val expected = buildEvent(
        application = application,
        eventType = ScopesFixed
      )

      when(fixture.eventService.log(any)).thenReturn(Future.successful(()))

      fixture.applicationsEventService.fixScopes(application, user, timestamp).map(
        _ =>
          verify(fixture.eventService).log(eqTo(expected))
          succeed
      )
    }
  }

  private def buildFixture(): Fixture = {
    val eventService = mock[EventsService]
    val applicationsEventService = new ApplicationsEventService(eventService, FakeHipEnvironments)

    Fixture(eventService, applicationsEventService)
  }

}

private object ApplicationsEventServiceSpec {

  case class Fixture(eventService: EventsService, applicationsEventService: ApplicationsEventService)

  val user = "test-user"
  val timestamp: LocalDateTime = LocalDateTime.now()

  val applicationId = "test-application-id"

  val application: Application = Application(
    id = Some(applicationId),
    name = "test-name",
    createdBy = Creator("test-creator"),
    teamMembers = Seq.empty
  )

  def applicationWithTeam(team: Team): Application = {
    application.copy(
      teamId = Some(team.safeId),
      teamName = Some(team.name)
    )
  }

  val teamId = "test-team-id"

  val team: Team = Team(
    id = Some(teamId),
    name = "test-team-name",
    created = LocalDateTime.now(),
    teamMembers = Seq.empty,
    teamType = ConsumerTeam,
    egresses = Seq.empty
  )

  val oldTeamId = "test-old-team-id"

  val oldTeam: Team = Team(
    id = Some(oldTeamId),
    name = "test-old-team-name",
    created = LocalDateTime.now(),
    teamMembers = Seq.empty,
    teamType = ConsumerTeam,
    egresses = Seq.empty
  )

  val api: Api = Api(
    id = "test-api-id",
    title = "test-api-title",
    endpoints = Seq(
      Endpoint("test-method-1", "test-path-1"),
      Endpoint("test-method-2", "test-path-2")
    )
  )

  val credential: Credential = Credential(
    clientId = "test-client-id",
    created = LocalDateTime.now(),
    clientSecret = None,
    secretFragment = None,
    environmentId = FakeHipEnvironments.productionEnvironment.id
  )

  def buildEvent(application: Application, eventType: EventType, parameters: Parameter *): Event = {
    Event.newEvent(
      entityId = application.safeId,
      entityType = event.Application,
      eventType = eventType,
      user = user,
      timestamp = timestamp,
      description = "",
      detail = "",
      parameters = parameters *
    )
  }

}
