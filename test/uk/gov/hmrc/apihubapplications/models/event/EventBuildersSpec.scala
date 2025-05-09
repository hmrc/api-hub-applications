/*
 * Copyright 2023 HM Revenue & Customs
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

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses.*
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, AccessRequestEndpoint, AccessRequestRequest, AccessRequestStatus, Approved, Pending}
import uk.gov.hmrc.apihubapplications.models.api.EndpointMethod
import uk.gov.hmrc.apihubapplications.models.application.{Api, Application, Creator, Credential, Endpoint, TeamMember}
import uk.gov.hmrc.apihubapplications.models.event.Application as ApplicationEntity
import uk.gov.hmrc.apihubapplications.models.requests.AddApiRequest

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.collection.mutable

class EventBuildersSpec extends AnyFreeSpec with Matchers {

  "EventBuilders" - {
    "application" - {
      val application = Application(
        id = Some("application-id"),
        name = "application-name",
        created = LocalDateTime.now(),
        createdBy = Creator("creator-email"),
        lastUpdated = LocalDateTime.now(),
        teamId = Some("team-id"),
        teamMembers = Seq(
          TeamMember("team-member-email")
        ),
        issues = Seq("issue"),
        apis = Seq(
          Api(
            id = "api-id-1",
            title = "api-title-1",
            endpoints = Seq(
              Endpoint(
                httpMethod = "GET",
                path = "/path-1",
              )
            )
          ),
          Api(
            id = "api-id-2",
            title = "api-title-2",
            endpoints = Seq(
              Endpoint(
                httpMethod = "POST",
                path = "/path-2",
              )
            )
          )
        ),
        deleted = None,
        teamName = Some("team-name"),
        credentials = mutable.LinkedHashSet(
          Credential(
            clientId = "client-id-1",
            created = LocalDateTime.now(),
            clientSecret = Some("client-secret-1"),
            secretFragment = Some("secret-fragment-1"),
            environmentId = "environment-id-1"
          ),
          Credential(
            clientId = "client-id-2",
            created = LocalDateTime.now(),
            clientSecret = Some("client-secret-2"),
            secretFragment = Some("secret-fragment-2"),
            environmentId = "environment-id-2"
          )
        ).toSet
      )

      "toEvent" in {
        val eventType = Created
        val user = "event-user"
        val description = "event-description"
        val detail = "event-detail"
        val parameters = Json.obj("event-key" -> "event-value")

        val event = application.toEvent(eventType, user, description, detail, parameters)

        event mustBe Event(
          id = None,
          entityId = application.id.get,
          entityType = ApplicationEntity,
          eventType = eventType,
          user = user,
          timestamp = application.created,
          description = description,
          detail = detail,
          parameters = parameters
        )
      }

      "toCreatedEvent" in {
        val event = application.toCreatedEvent

        event mustBe Event(
          id = None,
          entityId = application.id.get,
          entityType = ApplicationEntity,
          eventType = Registered,
          user = application.createdBy.email,
          timestamp = application.created,
          description = application.name,
          detail = s"The application ${application.name} was registered by team ${application.teamName.get}.",
          parameters = Json.toJson(application)
        )
      }

      "toApiAddedEvents" in {
        val events = application.toApiAddedEvents

        events mustBe Seq(
            Event(
              id = None,
              entityId = application.id.get,
              entityType = ApplicationEntity,
              eventType = ApiAdded,
              user = MIGRATION_USER,
              timestamp = application.created,
              description = application.apis(0).title,
              detail = s"The API api-title-1 was added to the application using the following endpoints: GET /path-1.",
              parameters = Json.toJson(AddApiRequest(
                id = application.apis(0).id,
                title = application.apis(0).title,
                endpoints = application.apis(0).endpoints
              ))
            ),
            Event(
              id = None,
              entityId = application.id.get,
              entityType = ApplicationEntity,
              eventType = ApiAdded,
              user = MIGRATION_USER,
              timestamp = application.created,
              description = application.apis(1).title,
              detail = s"The API api-title-2 was added to the application using the following endpoints: POST /path-2.",
              parameters = Json.toJson(AddApiRequest(
                id = application.apis(1).id,
                title = application.apis(1).title,
                endpoints = application.apis(1).endpoints
              ))
            )
        )
      }

      "toCredentialCreatedEvents" in {
        val events = application.toCredentialCreatedEvents

        events mustBe Seq(
          Event(
            id = None,
            entityId = application.id.get,
            entityType = ApplicationEntity,
            eventType = CredentialCreated,
            user = MIGRATION_USER,
            timestamp = application.created,
            description = application.credentials.toSeq(0).environmentId,
            detail = s"A credential with client Id client-id-1 was created in the environment-id-1 environment.",
            parameters = Json.toJson("environment-id-1")
          ),
          Event(
            id = None,
            entityId = application.id.get,
            entityType = ApplicationEntity,
            eventType = CredentialCreated,
            user = MIGRATION_USER,
            timestamp = application.created,
            description = application.credentials.toSeq(1).environmentId,
            detail = s"A credential with client Id client-id-2 was created in the environment-id-2 environment.",
            parameters = Json.toJson("environment-id-2")
          )
        )
      }

      val pendingAccessRequest = AccessRequest(
        id = Some("access-request-id"),
        applicationId = application.id.get,
        apiId = "api-id",
        apiName = "api-name",
        status = Pending,
        endpoints = Seq(
          AccessRequestEndpoint(
            httpMethod = "GET",
            path = "/path-1",
            scopes = Seq("scope-1", "scope-2")
          )
        ),
        supportingInformation = "supporting-information",
        requested = LocalDateTime.now(),
        requestedBy = "requested-by",
        decision = None,
        cancelled = None,
        environmentId = "environment-id-1"
      )

      "toAccessRequestCreatedEvent" in {
        val event = application.toAccessRequestCreatedEvent(pendingAccessRequest)

        event mustBe Event(
          id = None,
          entityId = application.id.get,
          entityType = ApplicationEntity,
          eventType = AccessRequestCreated,
          user = pendingAccessRequest.requestedBy,
          timestamp = pendingAccessRequest.requested,
          description = pendingAccessRequest.apiName,
          detail = s"This access request was created for the ${pendingAccessRequest.environmentId} environment requesting access to ${pendingAccessRequest.apiName}.",
          parameters = Json.toJson(pendingAccessRequest)
        )
      }
      
//      "toAccessRequestApprovedEvent" in {
//        val approvedAccessRequest = pendingAccessRequest.copy(
//          status = Approved,
//          de
//        )
//      }
    }
  }
}