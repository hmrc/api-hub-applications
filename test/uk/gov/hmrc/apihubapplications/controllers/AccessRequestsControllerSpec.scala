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

package uk.gov.hmrc.apihubapplications.controllers

import org.mockito.ArgumentMatchers.any
import org.mockito.{ArgumentMatchers, MockitoSugar}
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import play.api.{Application => PlayApplication}
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequestDecisionRequest, AccessRequestRequest, AccessRequestStatus, Pending}
import uk.gov.hmrc.apihubapplications.models.exception.{AccessRequestNotFoundException, AccessRequestStatusInvalidException, ApplicationNotFoundException}
import uk.gov.hmrc.apihubapplications.services.AccessRequestsService
import uk.gov.hmrc.apihubapplications.testhelpers.AccessRequestGenerator

import scala.concurrent.Future

class AccessRequestsControllerSpec
  extends AnyFreeSpec
    with Matchers
    with MockitoSugar
    with ScalaCheckDrivenPropertyChecks
    with AccessRequestGenerator
    with OptionValues
    with TableDrivenPropertyChecks {

  "createAccessRequest" - {
    "must pass the request on to the service layer and return 201 Created on success" in {
      val fixture = buildFixture()

      running(fixture.application) {
        forAll {(accessRequestRequest: AccessRequestRequest) =>
          val request = FakeRequest(POST, routes.AccessRequestsController.createAccessRequest().url)
            .withJsonBody(Json.toJson(accessRequestRequest))

          when(fixture.accessRequestsService.createAccessRequest(any())).thenReturn(Future.successful(Seq.empty))

          val result = route(fixture.application, request).value

          status(result) mustBe CREATED
          verify(fixture.accessRequestsService).createAccessRequest(ArgumentMatchers.eq(accessRequestRequest))
        }
      }
    }

    "must return 400 Bad Request when the request body is not a valid request" in {
      val fixture = buildFixture()

      running(fixture.application) {
        val request = FakeRequest(POST, routes.AccessRequestsController.createAccessRequest().url)
          .withJsonBody(Json.parse("{}"))

        val result = route(fixture.application, request).value

        status(result) mustBe BAD_REQUEST
      }
    }
  }

  "getAccessRequests" - {
    "must request the correct access requests from the service layer" in {
      val filters = Table(
        ("Application Id", "Status"),
        (Some("test-application-id"), Some(Pending)),
        (Some("test-application-id"), None),
        (None, Some(Pending)),
        (None, None)
      )

      val fixture = buildFixture()

      running(fixture.application) {
        forAll(filters) { (applicationIdFilter: Option[String], statusFilter: Option[AccessRequestStatus]) =>
          val expected = sampleAccessRequests()
          when(fixture.accessRequestsService.getAccessRequests(any(), any())).thenReturn(Future.successful(expected))

          val request = FakeRequest(GET, routes.AccessRequestsController.getAccessRequests(applicationIdFilter, statusFilter).url)
          val result = route(fixture.application, request).value

          status(result) mustBe OK
          contentAsJson(result) mustBe Json.toJson(expected)
          verify(fixture.accessRequestsService).getAccessRequests(ArgumentMatchers.eq(applicationIdFilter), ArgumentMatchers.eq(statusFilter))
        }
      }
    }
  }

  "getAccessRequest" - {
    "must return 200 Ok and the access request when it exists" in {
      val fixture = buildFixture()

      running(fixture.application) {
        val accessRequest = sampleAccessRequest()
        when(fixture.accessRequestsService.getAccessRequest(any())).thenReturn(Future.successful(Some(accessRequest)))

        val request = FakeRequest(GET, routes.AccessRequestsController.getAccessRequest(accessRequest.id.value).url)
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(accessRequest)
        verify(fixture.accessRequestsService).getAccessRequest(ArgumentMatchers.eq(accessRequest.id.value))
      }
    }

    "must return 4040 Not Found when the access request does not exist" in {
      val fixture = buildFixture()

      running(fixture.application) {
        when(fixture.accessRequestsService.getAccessRequest(any())).thenReturn(Future.successful(None))

        val request = FakeRequest(GET, routes.AccessRequestsController.getAccessRequest("test-id").url)
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }
  }

  "approveAccessRequest" - {
    "must process a valid request and return No Content" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)

      when(fixture.accessRequestsService.approveAccessRequest(any(), any(), any())(any()))
        .thenReturn(Future.successful(Right(())))

      running(fixture.application) {
        val request = FakeRequest(PUT, routes.AccessRequestsController.approveAccessRequest(id).url)
          .withJsonBody(Json.toJson(decisionRequest))
        val result = route(fixture.application, request).value

        status(result) mustBe NO_CONTENT
        verify(fixture.accessRequestsService).approveAccessRequest(ArgumentMatchers.eq(id), ArgumentMatchers.eq(decisionRequest), any())(any())
      }
    }

    "must return 404 Not Found when the access request does not exist" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)

      when(fixture.accessRequestsService.approveAccessRequest(any(), any(), any())(any()))
        .thenReturn(Future.successful(Left(AccessRequestNotFoundException.forId(id))))

      running(fixture.application) {
        val request = FakeRequest(PUT, routes.AccessRequestsController.approveAccessRequest(id).url)
          .withJsonBody(Json.toJson(decisionRequest))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return 409 Conflict when the access request's status is not pending" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)

      when(fixture.accessRequestsService.approveAccessRequest(any(), any(), any())(any()))
        .thenReturn(Future.successful(Left(AccessRequestStatusInvalidException("test-message"))))

      running(fixture.application) {
        val request = FakeRequest(PUT, routes.AccessRequestsController.approveAccessRequest(id).url)
          .withJsonBody(Json.toJson(decisionRequest))
        val result = route(fixture.application, request).value

        status(result) mustBe CONFLICT
      }
    }

    "must return 400 Bad Request when the application does not exist" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)

      when(fixture.accessRequestsService.approveAccessRequest(any(), any(), any())(any()))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException("test-message"))))

      running(fixture.application) {
        val request = FakeRequest(PUT, routes.AccessRequestsController.approveAccessRequest(id).url)
          .withJsonBody(Json.toJson(decisionRequest))
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_REQUEST
      }
    }

    "must return 400 Bad Request when the request body is not valid" in {
      val fixture = buildFixture()
      val id = "test-id"

      running(fixture.application) {
        val request = FakeRequest(PUT, routes.AccessRequestsController.approveAccessRequest(id).url)
          .withJsonBody(Json.obj())
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_REQUEST
      }
    }
  }

  "rejectAccessRequest" - {
    "must process a valid request and return No Content" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", Some("test-rejected-reason"))

      when(fixture.accessRequestsService.rejectAccessRequest(any(), any())(any()))
        .thenReturn(Future.successful(Right(())))

      running(fixture.application) {
        val request = FakeRequest(PUT, routes.AccessRequestsController.rejectAccessRequest(id).url)
          .withJsonBody(Json.toJson(decisionRequest))
        val result = route(fixture.application, request).value

        status(result) mustBe NO_CONTENT
        verify(fixture.accessRequestsService).rejectAccessRequest(ArgumentMatchers.eq(id), ArgumentMatchers.eq(decisionRequest))(any())
      }
    }

    "must return 404 Not Found when the access request does not exist" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", Some("test-rejected-reason"))

      when(fixture.accessRequestsService.rejectAccessRequest(any(), any())(any()))
        .thenReturn(Future.successful(Left(AccessRequestNotFoundException.forId(id))))

      running(fixture.application) {
        val request = FakeRequest(PUT, routes.AccessRequestsController.rejectAccessRequest(id).url)
          .withJsonBody(Json.toJson(decisionRequest))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return 409 Conflict when the access request's status is not pending" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", Some("test-rejected-reason"))

      when(fixture.accessRequestsService.rejectAccessRequest(any(), any())(any()))
        .thenReturn(Future.successful(Left(AccessRequestStatusInvalidException("test-message"))))

      running(fixture.application) {
        val request = FakeRequest(PUT, routes.AccessRequestsController.rejectAccessRequest(id).url)
          .withJsonBody(Json.toJson(decisionRequest))
        val result = route(fixture.application, request).value

        status(result) mustBe CONFLICT
      }
    }

    "must return 400 Bad Request when the request body is not valid" in {
      val fixture = buildFixture()
      val id = "test-id"

      running(fixture.application) {
        val request = FakeRequest(PUT, routes.AccessRequestsController.rejectAccessRequest(id).url)
          .withJsonBody(Json.obj())
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_REQUEST
      }
    }

    "must return 400 Bad Request when the request has no rejected reason" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)

      running(fixture.application) {
        val request = FakeRequest(PUT, routes.AccessRequestsController.rejectAccessRequest(id).url)
          .withJsonBody(Json.toJson(decisionRequest))
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_REQUEST
      }
    }
  }

  private case class Fixture(application: PlayApplication, accessRequestsService: AccessRequestsService)

  private def buildFixture(): Fixture = {
    val accessRequestsService = mock[AccessRequestsService]

    val application = new GuiceApplicationBuilder()
      .overrides(
        bind[ControllerComponents].toInstance(Helpers.stubControllerComponents()),
        bind[AccessRequestsService].toInstance(accessRequestsService),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction])
      )
      .build()

    Fixture(application, accessRequestsService)
  }

}
