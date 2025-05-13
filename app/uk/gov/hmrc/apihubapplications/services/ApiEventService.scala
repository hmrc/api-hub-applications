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

import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.apihubapplications.config.HipEnvironment
import uk.gov.hmrc.apihubapplications.models.apim.{RedeploymentRequest, SuccessfulDeploymentsResponse}
import uk.gov.hmrc.apihubapplications.models.event.{Api, Event, Parameter, Promoted, TeamChanged, Updated}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.models.team.TeamLenses.*

import java.time.LocalDateTime
import scala.concurrent.Future

@Singleton
class ApiEventService @Inject()(eventService: EventsService) {

  def update(
    apiId: String,
    hipEnvironment: HipEnvironment,
    oasVersion: String,
    request: RedeploymentRequest,
    response: SuccessfulDeploymentsResponse,
    userEmail: String,
    timestamp: LocalDateTime
  ): Future[Unit] = {
    eventService.log(
      Event.newEvent(
        entityId = apiId,
        entityType = Api,
        eventType = Updated,
        user = userEmail,
        timestamp = timestamp,
        description = "",
        detail = "",
        parameters =
          Parameter("environmentId", hipEnvironment.id),
          Parameter("oasVersion", oasVersion),
          Parameter("egress", request.egress),
          Parameter("status", request.status),
          Parameter("basePath", request.basePath),
          Parameter("deploymentVersion", response.version),
          Parameter("mergeRequestIid", response.mergeRequestIid)
      )
    )
  }

  def promote(
    apiId: String,
    fromEnvironment: HipEnvironment,
    toEnvironment: HipEnvironment,
    oasVersion: String,
    egress: String,
    response: SuccessfulDeploymentsResponse,
    userEmail: String,
    timestamp: LocalDateTime
  ): Future[Unit] = {
    eventService.log(
      Event.newEvent(
        entityId = apiId,
        entityType = Api,
        eventType = Promoted,
        user = userEmail,
        timestamp = timestamp,
        description = "",
        detail = "",
        parameters =
          Parameter("fromEnvironmentId", fromEnvironment.id),
          Parameter("toEnvironmentId", toEnvironment.id),
          Parameter("oasVersion", oasVersion),
          Parameter("egress", egress),
          Parameter("deploymentVersion", response.version),
          Parameter("mergeRequestIid", response.mergeRequestIid)
      )
    )
  }

  def changeTeam(
    apiId: String,
    newTeam: Team,
    oldTeam: Option[Team],
    userEmail: String,
    timestamp: LocalDateTime
  ): Future[Unit] = {
    val parameters = Seq(
      Some(Parameter("newTeamId", newTeam.safeId)),
      Some(Parameter("newTeamName", newTeam.name)),
      oldTeam.map(team => Parameter("oldTeamId", team.safeId)),
      oldTeam.map(team => Parameter("oldTeamName", team.name)),
    ).flatten

    eventService.log(
      Event.newEvent(
        entityId = apiId,
        entityType = Api,
        eventType = TeamChanged,
        user = userEmail,
        timestamp = timestamp,
        description = "",
        detail = "",
        parameters = parameters *
      )
    )
  }

}
