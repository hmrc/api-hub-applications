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

package uk.gov.hmrc.apihubapplications.services

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{verify, when}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{EitherValues, OptionValues}
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.models.accessRequest.*
import uk.gov.hmrc.apihubapplications.models.event.{AccessRequestApproved, AccessRequestCreated, AccessRequestRejected, AccessRequestCancelled, Event, Parameter, Application as ApplicationEvent}
import uk.gov.hmrc.apihubapplications.testhelpers.{AccessRequestGenerator, FakeHipEnvironments}

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses.*

class AccessRequestsEventServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar with AccessRequestGenerator with TableDrivenPropertyChecks with OptionValues with EitherValues {

  import AccessRequestsServiceSpec.*

  private val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

  "create" - {
    "must pass the correct requests to the events service" in {
      val fixture = buildFixture()

      when(fixture.eventsService.log(any())).thenReturn(Future.successful(()))

      val endpoints = Seq(AccessRequestEndpoint("GET", "some/path", Seq("a scope")))
      val accessRequestApi = AccessRequestApi(
        apiId = "test-api-id",
        apiName = "test-api-name",
        endpoints = endpoints
      )

      val accessRequestRequest = AccessRequestRequest(
        applicationId = "applicationId",
        supportingInformation = "supporting information",
        requestedBy = "test-requested-by",
        apis = Seq(accessRequestApi),
        environmentId = "test"
      )

      val accessRequests = accessRequestRequest.toAccessRequests(clock).map(accessRequest => accessRequest.copy(id = Some("access-request-id")))

      val now = LocalDateTime.now(clock)
      val expected = Event.newEvent(
        entityId = "applicationId",
        entityType = ApplicationEvent,
        eventType = AccessRequestCreated,
        user = "test-requested-by",
        timestamp = now,
        description = "test-api-name",
        detail = s"This access request was created for the Test environment requesting access to test-api-name.",
        parameters = Seq(
          Parameter("accessRequestId", "access-request-id"),
          Parameter("apiId","test-api-id"),
          Parameter("apiTitle","test-api-name"),
          Parameter("environmentId","test")
        ) *
      )

      fixture.accessRequestsEventService.created(accessRequests).map {
        result =>
          verify(fixture.eventsService).log(expected)
          result mustBe ()
      }
    }

    "approve" - {
      "must pass the correct requests to the events service" in {
        val fixture = buildFixture()

        when(fixture.eventsService.log(any())).thenReturn(Future.successful(()))

        val approvalRequest = AccessRequestDecisionRequest("test-requested-by", None)
        val now = LocalDateTime.now(clock)
        val accessRequest = AccessRequest(
          applicationId = "applicationId",
          apiId = "api-id",
          apiName = "test-api-name",
          status = Pending,
          supportingInformation = "supporting information",
          requested = now,
          requestedBy = "test-requested-by",
          environmentId = "test")
          .copy(
            id = Some("access-request-id"),
            decision = Some(AccessRequestDecision(now,"test-requested-by", None))
          )


        val expected = Event.newEvent(
          entityId = "applicationId",
          entityType = ApplicationEvent,
          eventType = AccessRequestApproved,
          user = "test-requested-by",
          timestamp = now,
          description = "test-api-name",
          detail = "This request for access to test-api-name was approved and scopes were added to the application's credentials in the Test environment.",
          parameters = Seq(
            Parameter("accessRequestId", "access-request-id"),
            Parameter("apiId","api-id"),
            Parameter("apiTitle","test-api-name"),
            Parameter("environmentId","test")
          ) *
        )

        fixture.accessRequestsEventService.approved(accessRequest).map {
          result =>
            verify(fixture.eventsService).log(expected)
            result mustBe()
        }
      }
    }

    "reject" - {
      "must pass the correct requests to the events service" in {
        val fixture = buildFixture()

        when(fixture.eventsService.log(any())).thenReturn(Future.successful(()))

        val rejectionRequest = AccessRequestDecisionRequest("test-requested-by", Some("rejection reason"))
        val now = LocalDateTime.now(clock)
        val accessRequest = AccessRequest(
          applicationId = "applicationId",
          apiId = "api-id",
          apiName = "test-api-name",
          status = Pending,
          supportingInformation = "supporting information",
          requested = now,
          requestedBy = "test-requested-by",
          environmentId = "test")
          .copy(
            id = Some("access-request-id"),
            decision = Some(AccessRequestDecision(now,"test-requested-by", Some("some reason")))
          )

        val expected = Event.newEvent(
          entityId = "applicationId",
          entityType = ApplicationEvent,
          eventType = AccessRequestRejected,
          user = "test-requested-by",
          timestamp = now,
          description = "Rejected for test-api-name",
          detail = "This request for access to test-api-name in the Test environment was rejected.",
          parameters = Seq(
            Parameter("accessRequestId", "access-request-id"),
            Parameter("apiId","api-id"),
            Parameter("apiTitle","test-api-name"),
            Parameter("environmentId","test")
          ) *
        )

        fixture.accessRequestsEventService.rejected(accessRequest).map {
          result =>
            verify(fixture.eventsService).log(expected)
            result mustBe()
        }
      }
    }

    "cancel" - {
      "must pass the correct requests to the events service" in {
        val fixture = buildFixture()

        when(fixture.eventsService.log(any())).thenReturn(Future.successful(()))

        val cancelRequest = AccessRequestCancelRequest("test-requested-by")
        val now = LocalDateTime.now(clock)
        val accessRequest = AccessRequest(
          applicationId = "applicationId",
          apiId = "api-id",
          apiName = "test-api-name",
          status = Pending,
          supportingInformation = "supporting information",
          requested = now,
          requestedBy = "test-requested-by",
          environmentId = "test")
          .copy(
            id = Some("access-request-id"),
            decision = Some(AccessRequestDecision(now,"test-requested-by", None)))


        val expected = Event.newEvent(
          entityId = "applicationId",
          entityType = ApplicationEvent,
          eventType = AccessRequestCancelled,
          user = "test-requested-by",
          timestamp = now,
          description = "Cancelled for test-api-name",
          detail = "This request for access to test-api-name in the Test environment was cancelled.",
          parameters = Seq(
            Parameter("accessRequestId", "access-request-id"),
            Parameter("apiId","api-id"),
            Parameter("apiTitle","test-api-name"),
            Parameter("environmentId","test")
          ) *
        )

        fixture.accessRequestsEventService.cancelled(accessRequest).map {
          result =>
            verify(fixture.eventsService).log(expected)
            result mustBe()
        }
      }
    }
  }

  private case class Fixture(
                              clock: Clock,
                              eventsService: EventsService,
                              accessRequestsEventService: AccessRequestsEventService
                            )

  private def buildFixture(): Fixture = {
    val eventsService = mock[EventsService]
    val accessRequestsEventService = new AccessRequestsEventService(eventsService, clock, FakeHipEnvironments)
    Fixture(clock, eventsService, accessRequestsEventService)
  }

}
