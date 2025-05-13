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
import play.api.Logging
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}
import uk.gov.hmrc.apihubapplications.models.application.{Api, Application, Credential, NewApplication}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.event
import uk.gov.hmrc.apihubapplications.models.event.{ApiAdded, ApiRemoved, CredentialCreated, CredentialRevoked, Deleted, Event, Parameter, Registered, ScopesFixed, TeamChanged}
import uk.gov.hmrc.apihubapplications.models.requests.AddApiRequest
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.models.team.TeamLenses.*

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class ApplicationsEventService @Inject()(
  eventService: EventsService,
  hipEnvironments: HipEnvironments
)(implicit ec: ExecutionContext) extends Logging {

  def register(application: Application, user: String, timestamp: LocalDateTime): Future[Unit] = {
    val parameters = Seq(
      Some(Parameter("applicationName", application.name)),
      application.teamId.map(teamId => Parameter("teamId", teamId)),
      application.teamName.map(teamName => Parameter("teamName", teamName))
    ).flatten

    eventService.log(
      Event.newEvent(
        entityId = application.safeId,
        entityType = event.Application,
        eventType = Registered,
        user = user,
        timestamp = timestamp,
        description = "",
        detail = "",
        parameters = parameters *
      )
    )
  }

  def delete(application: Application, softDeleted: Boolean, user: String, timestamp: LocalDateTime): Future[Unit] = {
    eventService.log(
      Event.newEvent(
        entityId = application.safeId,
        entityType = event.Application,
        eventType = Deleted,
        user = user,
        timestamp = timestamp,
        description = "",
        detail = "",
        parameters = Parameter("softDeleted", softDeleted)
      )
    )
  }

  def addApi(application: Application, api: Api, user: String, timestamp: LocalDateTime): Future[Unit] = {
    val endpoints = api.endpoints
      .map(endpoint => s"${endpoint.httpMethod} ${endpoint.path}")

    eventService.log(
      Event.newEvent(
        entityId = application.safeId,
        entityType = event.Application,
        eventType = ApiAdded,
        user = user,
        timestamp = timestamp,
        description = "",
        detail = "",
        parameters =
          Parameter("apiId", api.id),
          Parameter("apiTitle", api.title),
          Parameter("endpoints", endpoints)
      )
    )
  }

  def removeApi(application: Application, api: Api, user: String, timestamp: LocalDateTime): Future[Unit] = {
    eventService.log(
      Event.newEvent(
        entityId = application.safeId,
        entityType = event.Application,
        eventType = ApiRemoved,
        user = user,
        timestamp = timestamp,
        description = "",
        detail = "",
        parameters =
          Parameter("apiId", api.id),
          Parameter("apiTitle", api.title)
      )
    )
  }

  def changeTeam(application: Application, newTeam: Team, oldTeam: Option[Team], user: String, timestamp: LocalDateTime): Future[Unit] = {
    val parameters = Seq(
      Some(Parameter("newTeamId", newTeam.safeId)),
      Some(Parameter("newTeamName", newTeam.name)),
      oldTeam.map(team => Parameter("oldTeamId", team.safeId)),
      oldTeam.map(team => Parameter("oldTeamName", team.name)),
    ).flatten

    eventService.log(
      Event.newEvent(
        entityId = application.safeId,
        entityType = event.Application,
        eventType = TeamChanged,
        user = user,
        timestamp = timestamp,
        description = "",
        detail = "",
        parameters = parameters *
      )
    )
  }

  def createCredential(application: Application, credential: Credential, user: String, timestamp: LocalDateTime): Future[Unit] = {
    eventService.log(
      Event.newEvent(
        entityId = application.safeId,
        entityType = event.Application,
        eventType = CredentialCreated,
        user = user,
        timestamp = timestamp,
        description = "",
        detail = "",
        parameters =
          Parameter("environmentId", credential.environmentId),
          Parameter("clientId", credential.clientId)
      )
    )
  }

  def revokeCredential(application: Application, hipEnvironment: HipEnvironment, clientId: String, user: String, timestamp: LocalDateTime): Future[Unit] = {
    eventService.log(
      Event.newEvent(
        entityId = application.safeId,
        entityType = event.Application,
        eventType = CredentialRevoked,
        user = user,
        timestamp = timestamp,
        description = "",
        detail = "",
        parameters = 
          Parameter("environmentId", hipEnvironment.id),
          Parameter("clientId", clientId)
      )
    )
  }

  def fixScopes(application: Application, user: String, timestamp: LocalDateTime): Future[Unit] = {
    eventService.log(
      Event.newEvent(
        entityId = application.safeId,
        entityType = event.Application,
        eventType = ScopesFixed,
        user = user,
        timestamp = timestamp,
        description = "",
        detail = ""
      )
    )
  }

}
