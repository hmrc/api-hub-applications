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
import uk.gov.hmrc.apihubapplications.config.HipEnvironment
import uk.gov.hmrc.apihubapplications.models.apim.{RedeploymentRequest, SuccessfulDeploymentsResponse}
import uk.gov.hmrc.apihubapplications.models.event.*
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.models.team.TeamType.ConsumerTeam
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments

import java.time.LocalDateTime
import scala.concurrent.Future

class ApiEventServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar {

  import ApiEventServiceSpec.*

  "update" - {
    "must log the correct event" in {
      val fixture = buildFixture()

      val expected = buildEvent(
        eventType = Updated,
        parameters =
          Parameter("environmentId", deployToEnvironment.id),
          Parameter("oasVersion", oasVersion),
          Parameter("egress", egress),
          Parameter("status", request.status),
          Parameter("basePath", request.basePath),
          Parameter("deploymentVersion", response.version),
          Parameter("mergeRequestIid", response.mergeRequestIid)
      )

      when(fixture.eventService.log(any)).thenReturn(Future.successful(()))

      fixture.apiEventService.update(
        apiId = apiId,
        hipEnvironment = deployToEnvironment,
        oasVersion = oasVersion,
        request = request,
        response = response,
        userEmail = userEmail,
        timestamp = timestamp
      ).map(
        _ =>
          verify(fixture.eventService).log(eqTo(expected))
          succeed
      )
    }
  }

  "promote" - {
    "must log the correct event" in {
      val fixture = buildFixture()

      val expected = buildEvent(
        eventType = Promoted,
        parameters =
          Parameter("fromEnvironmentId", deployToEnvironment.id),
          Parameter("toEnvironmentId", promoteToEnvironment.id),
          Parameter("oasVersion", oasVersion),
          Parameter("egress", egress),
          Parameter("deploymentVersion", response.version),
          Parameter("mergeRequestIid", response.mergeRequestIid)
      )

      when(fixture.eventService.log(any)).thenReturn(Future.successful(()))

      fixture.apiEventService.promote(
        apiId = apiId,
        fromEnvironment = deployToEnvironment,
        toEnvironment = promoteToEnvironment,
        oasVersion = oasVersion,
        egress = egress,
        response = response,
        userEmail = userEmail,
        timestamp = timestamp
      ).map(
        _ =>
          verify(fixture.eventService).log(eqTo(expected))
          succeed
      )
    }
  }

  "changeTeam" - {
    "must log the correct event when the API already had an owning team" in {
      val fixture = buildFixture()

      val expected = buildEvent(
        eventType = TeamChanged,
        parameters =
          Parameter("newTeamId", teamId),
          Parameter("newTeamName", team.name),
          Parameter("oldTeamId", oldTeamId),
          Parameter("oldTeamName", oldTeam.name)
      )

      when(fixture.eventService.log(any)).thenReturn(Future.successful(()))

      fixture.apiEventService.changeTeam(
        apiId = apiId,
        newTeam = team,
        oldTeam = Some(oldTeam),
        userEmail = userEmail,
        timestamp = timestamp
      ).map(
        _ =>
          verify(fixture.eventService).log(eqTo(expected))
          succeed
      )
    }
  }

  private def buildFixture(): Fixture = {
    val eventService = mock[EventsService]
    val apiEventService = ApiEventService(eventService)
    Fixture(eventService, apiEventService)
  }

}

private object ApiEventServiceSpec {

  case class Fixture(eventService: EventsService, apiEventService: ApiEventService)

  val apiId = "test-api-id"
  val userEmail = "test-user-email"
  val timestamp: LocalDateTime = LocalDateTime.now()

  val deployToEnvironment: HipEnvironment = FakeHipEnvironments.testEnvironment
  val promoteToEnvironment: HipEnvironment = FakeHipEnvironments.productionEnvironment
  val oasVersion = "test-oas-version"
  val egress = "test-egress"

  val request: RedeploymentRequest = RedeploymentRequest(
    description = "",
    oas = "",
    status = "test-status",
    domain = "",
    subDomain = "",
    hods = Seq.empty,
    prefixesToRemove = Seq.empty,
    egressMappings = None,
    egress = Some(egress),
    basePath = "test-base-path"
  )

  val response: SuccessfulDeploymentsResponse = SuccessfulDeploymentsResponse(
    id = "",
    version = "test-deployment-version",
    mergeRequestIid = 101,
    uri = ""
  )

  def buildEvent(eventType: EventType, parameters: Parameter *): Event = {
    Event.newEvent(
      entityId = apiId,
      entityType = Api,
      eventType = eventType,
      user = userEmail,
      timestamp = timestamp,
      description = "",
      detail = "",
      parameters = parameters *
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

}
