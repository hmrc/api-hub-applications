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

package uk.gov.hmrc.apihubapplications.models.event

import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequest
import uk.gov.hmrc.apihubapplications.models.application.Application
import uk.gov.hmrc.apihubapplications.models.event.Application as ApplicationEntity
import uk.gov.hmrc.apihubapplications.models.event.Team as TeamEntity
import uk.gov.hmrc.apihubapplications.models.requests.AddApiRequest
import uk.gov.hmrc.apihubapplications.models.team.Team

private val UNKNOWN = "<unknown>"
private val MIGRATION_USER = "<migration>"

extension(application: Application) {
  def toEvent(eventType: EventType, user: String, description: String, detail: String, parameters: JsValue) =
    Event(
      id = None,
      entityId = application.id.getOrElse(UNKNOWN),
      entityType = ApplicationEntity,
      eventType = eventType,
      user = user,
      timestamp = application.created,
      description = description,
      detail = detail,
      parameters = parameters
    )

  def toCreatedEvent: Event = {
    toEvent(
      Registered,
      user = application.createdBy.email,
      description = application.name,
      detail = s"The application ${application.name} was registered by team ${application.teamName.getOrElse(UNKNOWN)}.",
      parameters = Json.toJson(application)
    )
  }

  def toApiAddedEvents: Seq[Event] = {
    application.apis.map { api =>
      val request = AddApiRequest(
        id = api.id,
        title = api.title,
        endpoints = api.endpoints
      )
      toEvent(
        eventType = ApiAdded,
        user = MIGRATION_USER,
        description = api.title,
        detail = s"The API ${api.title} was added to the application using the following endpoints: ${api.endpoints.map(_.toString).mkString(", ")}.",
        parameters = Json.toJson(request)
      )
    }
  }

  def toCredentialCreatedEvents: Seq[Event] = {
    application.credentials.map { credential =>
      toEvent(
        eventType = CredentialCreated,
        user = MIGRATION_USER,
        description = credential.environmentId,
        detail = s"A credential with client Id ${credential.clientId} was created in the ${credential.environmentId} environment.",
        parameters = Json.toJson(credential.environmentId)
      )
    }.toSeq
  }

  def toAccessRequestCreatedEvent(accessRequest: AccessRequest): Event = {
    toEvent(
      eventType = AccessRequestCreated,
      user = accessRequest.requestedBy,
      description = accessRequest.apiName,
      detail = s"This access request was created for the ${accessRequest.environmentId} environment requesting access to ${accessRequest.apiName}.",
      parameters = Json.toJson(accessRequest)
    ).copy(
      timestamp = accessRequest.requested
    )
  }

  def toAccessRequestApprovedEvent(accessRequest: AccessRequest): Event = {
    toEvent(
      eventType = AccessRequestApproved,
      user = accessRequest.decision.map(_.decidedBy).getOrElse(UNKNOWN),
      description = accessRequest.apiName,
      detail = s"This request for access to ${accessRequest.apiName} was approved and scopes were added to the application's credentials in the ${accessRequest.environmentId} environment.",
      parameters = Json.toJson(accessRequest)
    )
  }

  def toAccessRequestRejectedEvent(accessRequest: AccessRequest): Event = {
    toEvent(
      eventType = AccessRequestRejected,
      user = accessRequest.decision.map(_.decidedBy).getOrElse(UNKNOWN),
      description = s"Rejected for ${accessRequest.apiName}",
      detail = s"This request for access to ${accessRequest.apiName} in the ${accessRequest.environmentId} environment was rejected.",
      parameters = Json.toJson(accessRequest)
    )
  }

  def toAccessRequestCancelledEvent(accessRequest: AccessRequest): Event = {
    toEvent(
      eventType = AccessRequestCanceled,
      user = accessRequest.cancelled.map(_.cancelledBy).getOrElse(UNKNOWN),
      description = s"Cancelled for ${accessRequest.apiName}",
      detail = s"This request for access to ${accessRequest.apiName} in the ${accessRequest.environmentId} environment was cancelled.",
      parameters = Json.toJson(accessRequest)
    )
  }
}

extension(team: Team) {
  def toEvent(eventType: EventType, description: String, detail: String, parameters: JsValue) =
    Event(
      id = None,
      entityId = team.id.getOrElse(UNKNOWN),
      entityType = TeamEntity,
      eventType = eventType,
      user = MIGRATION_USER,
      timestamp = team.created,
      description = description,
      detail = detail,
      parameters = parameters
    )

  def toTeamCreatedEvent: Event = {
    toEvent(
      eventType = Created,
      description = team.name,
      detail = s"The team ${team.name} was created with the following members: ${team.teamMembers.map(_.email).mkString(", ")}.",
      parameters = Json.toJson(team)
    )
  }

  def toEgressesAddedToTeamEvent: Event = {
    toEvent(
      eventType = EgressAdded,
      description = s"${team.egresses.size} egress(es) added",
      detail = s"The following egresses were added to the team: ${team.egresses.mkString(", ")}.",
      parameters = Json.toJson(team)
    )
  }

  def toTeamMemberAddedEvents: Seq[Event] = {
    team.teamMembers.map {
      teamMember =>
        toEvent(
          eventType = MemberAdded,
          description = teamMember.email,
          detail = s"${teamMember.email} was added to the team",
          parameters = Json.toJson(team)
        )
    }
  }

}