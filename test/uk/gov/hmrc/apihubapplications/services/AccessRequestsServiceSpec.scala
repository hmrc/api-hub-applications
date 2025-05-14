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

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{verify, verifyNoInteractions, verifyNoMoreInteractions, when}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{EitherValues, OptionValues}
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.connectors.EmailConnector
import uk.gov.hmrc.apihubapplications.models.accessRequest.*
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses.AccessRequestLensOps
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator, TeamMember}
import uk.gov.hmrc.apihubapplications.models.exception.*
import uk.gov.hmrc.apihubapplications.repositories.AccessRequestsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.ScopeFixer
import uk.gov.hmrc.apihubapplications.testhelpers.{AccessRequestGenerator, FakeHipEnvironments}
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future

class AccessRequestsServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar with AccessRequestGenerator with TableDrivenPropertyChecks with OptionValues with EitherValues {

  import AccessRequestsServiceSpec.*

  "createAccessRequest" - {
    "must pass the correct requests to the accessRequestsRepository" in {
      val fixture = buildFixture()

      val app = Application(
        id = Some("test-app-id"),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        credentials = Set.empty
      )

      val request = sampleAccessRequestRequest()
      val expected = sampleAccessRequests()

      when(fixture.accessRequestsRepository.insert(any())).thenReturn(Future.successful(expected))
      when(fixture.searchService.findById(any())(any)).thenReturn(Future.successful(Right(app)))
      when(fixture.emailConnector.sendAccessRequestSubmittedEmailToRequester(any(), any())(any())).thenReturn(Future.successful(Right(())))
      when(fixture.emailConnector.sendNewAccessRequestEmailToApprovers(any(), any())(any())).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsEventService.created(any(), any())).thenReturn(Future.successful(()))

      fixture.accessRequestsService.createAccessRequest(request)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsRepository).insert(eqTo(request.toAccessRequests(fixture.clock)))
          result mustBe expected
      }
    }

    "must send appropriate email if access request repository updates successfully" in {
      val fixture = buildFixture()
      val accessRequestRequest = sampleAccessRequestRequest()
      val accessRequests = accessRequestRequest.toAccessRequests(fixture.clock)
      val app = Application(
        id = Some(accessRequestRequest.applicationId),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        credentials = Set.empty
      )

      when(fixture.accessRequestsRepository.insert(any())).thenReturn(Future.successful(accessRequests))
      when(fixture.searchService.findById(eqTo(accessRequestRequest.applicationId))(any)).thenReturn(Future.successful(Right(app)))
      when(fixture.emailConnector.sendAccessRequestSubmittedEmailToRequester(eqTo(app), eqTo(accessRequestRequest))(any())).thenReturn(Future.successful(Right(())))
      when(fixture.emailConnector.sendNewAccessRequestEmailToApprovers(any(), any())(any())).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsEventService.created(any(), any())).thenReturn(Future.successful(()))

      fixture.accessRequestsService.createAccessRequest(accessRequestRequest)(HeaderCarrier()).map {
        result =>
          verify(fixture.emailConnector).sendAccessRequestSubmittedEmailToRequester(eqTo(app), eqTo(accessRequestRequest))(any())
          verify(fixture.emailConnector).sendNewAccessRequestEmailToApprovers(eqTo(app), eqTo(accessRequestRequest))(any())

          result mustBe accessRequests
      }
    }

    "must tolerate email failure and treat as success" in {
      val fixture = buildFixture()
      val accessRequestRequest = sampleAccessRequestRequest()
      val accessRequests = accessRequestRequest.toAccessRequests(fixture.clock)
      val app = Application(
        id = Some(accessRequestRequest.applicationId),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        credentials = Set.empty
      )

      when(fixture.accessRequestsRepository.insert(any())).thenReturn(Future.successful(accessRequests))
      when(fixture.searchService.findById(eqTo(accessRequestRequest.applicationId))(any)).thenReturn(Future.successful(Right(app)))
      when(fixture.emailConnector.sendAccessRequestSubmittedEmailToRequester(eqTo(app), eqTo(accessRequestRequest))(any())).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))
      when(fixture.emailConnector.sendNewAccessRequestEmailToApprovers(eqTo(app), eqTo(accessRequestRequest))(any())).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))
      when(fixture.accessRequestsEventService.created(any(), any())).thenReturn(Future.successful(()))

      fixture.accessRequestsService.createAccessRequest(accessRequestRequest)(HeaderCarrier()).map {
        result =>
          verify(fixture.emailConnector).sendAccessRequestSubmittedEmailToRequester(eqTo(app), eqTo(accessRequestRequest))(any())
          verify(fixture.emailConnector).sendNewAccessRequestEmailToApprovers(eqTo(app), eqTo(accessRequestRequest))(any())

          result mustBe accessRequests
      }
    }

    "must log the create access request event" in {
      val fixture = buildFixture()
      val accessRequestRequest = sampleAccessRequestRequest()
      val accessRequests = accessRequestRequest.toAccessRequests(fixture.clock)
      val app = Application(
        id = Some(accessRequestRequest.applicationId),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        credentials = Set.empty
      )

      when(fixture.accessRequestsRepository.insert(any())).thenReturn(Future.successful(accessRequests))
      when(fixture.searchService.findById(eqTo(accessRequestRequest.applicationId))(any)).thenReturn(Future.successful(Right(app)))
      when(fixture.emailConnector.sendAccessRequestSubmittedEmailToRequester(eqTo(app), eqTo(accessRequestRequest))(any())).thenReturn(Future.successful(Right(())))
      when(fixture.emailConnector.sendNewAccessRequestEmailToApprovers(any(), any())(any())).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsEventService.created(any(), any())).thenReturn(Future.successful(()))

      fixture.accessRequestsService.createAccessRequest(accessRequestRequest)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsEventService).created(accessRequestRequest, accessRequests)
          result mustBe accessRequests
      }
    }
  }

  "getAccessRequests" - {
    "must request the correct access requests from the accessRequestsRepository" in {
      val filters = Table(
        ("Application Id", "Status"),
        (Some("test-application-id"), Some(Pending)),
        (Some("test-application-id"), None),
        (None, Some(Pending)),
        (None, None)
      )

      val fixture = buildFixture()

      forAll(filters) {(applicationIdFilter: Option[String], statusFilter: Option[AccessRequestStatus]) =>
        val expected = sampleAccessRequests()
        when(fixture.accessRequestsRepository.find(any(), any())).thenReturn(Future.successful(expected))

        fixture.accessRequestsService.getAccessRequests(applicationIdFilter, statusFilter).map {
          actual =>
            verify(fixture.accessRequestsRepository).find(eqTo(applicationIdFilter), eqTo(statusFilter))
            actual mustBe expected
        }
      }
    }
  }

  "getAccessRequest" - {
    "must request the correct access request from the accessRequestsRepository" in {
      val accessRequests = Table(
        ("Id", "Access Request"),
        ("test-id-1", Some(sampleAccessRequest())),
        ("test-id-2", None)
      )

      val fixture = buildFixture()

      forAll(accessRequests) {(id: String, accessRequest: Option[AccessRequest]) =>
        when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(accessRequest))

        fixture.accessRequestsService.getAccessRequest(id).map {
          actual =>
            verify(fixture.accessRequestsRepository).findById(eqTo(id))
            actual mustBe accessRequest
        }
      }
    }
  }

  "approveAccessRequest" - {
    "must process and update the access request to Approved" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)

      val applicationId = "test-application-id"
      val application = buildApplication(applicationId)

      val accessRequest = AccessRequest(
        id = Some(id),
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(fixture.clock),
        requestedBy = "test-requested-by",
        decision = None,
        cancelled = None,
        environmentId = "test"
      )

      val updated = accessRequest
        .setStatus(Approved)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.searchService.findById(any)(any)).thenReturn(Future.successful(Right(application)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.emailConnector.sendAccessApprovedEmailToTeam(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsRepository.find(any, any)).thenReturn(Future.successful(Seq(accessRequest)))
      when(fixture.scopeFixer.fix(any, any, eqTo(FakeHipEnvironments.testEnvironment))(any)).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsEventService.approved(any())).thenReturn(Future.successful(()))

      fixture.accessRequestsService.approveAccessRequest(id, decisionRequest)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsRepository).findById(eqTo(id))
          verify(fixture.searchService).findById(eqTo(applicationId))(any)
          verify(fixture.accessRequestsRepository).update(eqTo(updated))
          verify(fixture.accessRequestsRepository).find(eqTo(Some(applicationId)), eqTo(None))
          verify(fixture.scopeFixer).fix(eqTo(application), eqTo(Seq(accessRequest)), eqTo(FakeHipEnvironments.testEnvironment))(any)

          result mustBe Right(())
      }
    }

    "must send appropriate emails if access request repository updates successfully" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)

      val applicationId = "test-application-id"
      val application = buildApplication(applicationId)

      val accessRequest = AccessRequest(
        id = Some(id),
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(fixture.clock),
        requestedBy = "test-requested-by",
        decision = None,
        cancelled = None,
        environmentId = "test"
      )

      val updated = accessRequest
        .setStatus(Approved)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.searchService.findById(any)(any)).thenReturn(Future.successful(Right(application)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.emailConnector.sendAccessApprovedEmailToTeam(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsRepository.find(any, any)).thenReturn(Future.successful(Seq(accessRequest)))
      when(fixture.scopeFixer.fix(any, any, eqTo(FakeHipEnvironments.testEnvironment))(any)).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsEventService.approved(any())).thenReturn(Future.successful(()))

      fixture.accessRequestsService.approveAccessRequest(id, decisionRequest)(HeaderCarrier()).map {
        result =>
          verify(fixture.emailConnector).sendAccessApprovedEmailToTeam(eqTo(application), eqTo(updated))(any())
          result mustBe Right(())
      }
    }

    "must tolerate email failure and treat as success" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)

      val applicationId = "test-application-id"
      val application = buildApplication(applicationId)

      val accessRequest = AccessRequest(
        id = Some(id),
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(fixture.clock),
        requestedBy = "test-requested-by",
        decision = None,
        cancelled = None,
        environmentId = "test"
      )

      val updated = accessRequest
        .setStatus(Approved)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.searchService.findById(any)(any)).thenReturn(Future.successful(Right(application)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsRepository.find(any, any)).thenReturn(Future.successful(Seq(accessRequest)))
      when(fixture.scopeFixer.fix(any, any, eqTo(FakeHipEnvironments.testEnvironment))(any)).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsEventService.approved(any())).thenReturn(Future.successful(()))
      when(fixture.emailConnector.sendAccessApprovedEmailToTeam(eqTo(application), eqTo(updated))(any()))
        .thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))

      fixture.accessRequestsService.approveAccessRequest(id, decisionRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Right(())
      }
    }

    "must log the approve access request event" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)

      val applicationId = "test-application-id"
      val application = buildApplication(applicationId)

      val accessRequest = AccessRequest(
        id = Some(id),
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(fixture.clock),
        requestedBy = "test-requested-by",
        decision = None,
        cancelled = None,
        environmentId = "test"
      ).copy(decision = Some(AccessRequestDecision(LocalDateTime.now(clock), "test-requested-by", None)))

      val updated = accessRequest
        .setStatus(Approved)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.searchService.findById(any)(any)).thenReturn(Future.successful(Right(application)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsRepository.find(any, any)).thenReturn(Future.successful(Seq(accessRequest)))
      when(fixture.scopeFixer.fix(any, any, eqTo(FakeHipEnvironments.testEnvironment))(any)).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsEventService.approved(any())).thenReturn(Future.successful(()))
      when(fixture.emailConnector.sendAccessApprovedEmailToTeam(eqTo(application), eqTo(updated))(any()))
        .thenReturn(Future.successful(Right(())))


      fixture.accessRequestsService.approveAccessRequest(id, decisionRequest)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsEventService).approved(updated)
          result mustBe Right(())
      }
    }

    "must not send emails if access request repository does not update successfully" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)

      val applicationId = "test-application-id"
      val application = buildApplication(applicationId)

      val accessRequest = AccessRequest(
        id = Some(id),
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(fixture.clock),
        requestedBy = "test-requested-by",
        decision = None,
        cancelled = None,
        environmentId = "test"
      )

      val updated = accessRequest
        .setStatus(Approved)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.searchService.findById(any)(any)).thenReturn(Future.successful(Right(application)))

      val exception = NotUpdatedException.forAccessRequest(accessRequest)
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Left(exception)))

      fixture.accessRequestsService.approveAccessRequest(id, decisionRequest)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsRepository).update(eqTo(updated))
          verifyNoInteractions(fixture.emailConnector)
          result mustBe Left(exception)
      }
    }

    "must return AccessRequestStatusInvalidException if the access request's status in not Pending" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)

      val accessRequest = AccessRequest(
        id = Some(id),
        applicationId = "test-application-id",
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Approved,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(fixture.clock),
        requestedBy = "test-requested-by",
        decision = None,
        cancelled = None,
        environmentId = "test"
      )

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))

      fixture.accessRequestsService.approveAccessRequest(id, decisionRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Left(AccessRequestStatusInvalidException.forAccessRequest(accessRequest))
      }
    }

    "must return AccessRequestNotFoundException if the access request does not exist" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(None))

      fixture.accessRequestsService.approveAccessRequest(id, decisionRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Left(AccessRequestNotFoundException.forId(id))
      }
    }

    "must return an exception if call to the credentials service fails" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)

      val applicationId = "test-application-id"
      val application = buildApplication(applicationId)

      val accessRequest = AccessRequest(
        id = Some(id),
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(fixture.clock),
        requestedBy = "test-requested-by",
        decision = None,
        cancelled = None,
        environmentId = "test"
      )

      val updated = accessRequest
        .setStatus(Approved)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)
      
      val exception = IdmsException.unexpectedResponse(UpstreamErrorResponse("nope", 500), Seq.empty)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.searchService.findById(any)(any)).thenReturn(Future.successful(Right(application)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.emailConnector.sendAccessApprovedEmailToTeam(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsRepository.find(any, any)).thenReturn(Future.successful(Seq(accessRequest)))
      when(fixture.scopeFixer.fix(any, any, eqTo(FakeHipEnvironments.testEnvironment))(any)).thenReturn(Future.successful(Left(exception)))
      when(fixture.accessRequestsEventService.approved(any())).thenReturn(Future.successful(()))

      fixture.accessRequestsService.approveAccessRequest(id, decisionRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Left(exception)
      }
    }
  }

  "rejectAccessRequest" - {
    "must update the access request to Rejected" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", Some("test-rejected-reason"))

      val accessRequest = AccessRequest(
        id = Some(id),
        applicationId = "test-application-id",
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(fixture.clock),
        requestedBy = "test-requested-by",
        decision = None,
        cancelled = None,
        environmentId = "test"
      )

      val app = Application(
        id = Some(accessRequest.applicationId),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        credentials = Set.empty
      )

      val updated = accessRequest
        .setStatus(Rejected)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy, decisionRequest.rejectedReason.value)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.searchService.findById(eqTo(accessRequest.applicationId))(any)).thenReturn(Future.successful(Right(app)))
      when(fixture.emailConnector.sendAccessRejectedEmailToTeam(any(), any())(any())).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsEventService.rejected(any())).thenReturn(Future.successful(()))

      fixture.accessRequestsService.rejectAccessRequest(id, decisionRequest)(new HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsRepository).findById(eqTo(id))
          verify(fixture.accessRequestsRepository).update(eqTo(updated))
          result mustBe Right(())
      }
    }

    "must return AccessRequestStatusInvalidException if the access request's status in not Pending" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", Some("test-rejected-reason"))

      val accessRequest = AccessRequest(
        id = Some(id),
        applicationId = "test-application-id",
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Approved,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(fixture.clock),
        requestedBy = "test-requested-by",
        decision = None,
        cancelled = None,
        environmentId = "test"
      )

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))

      fixture.accessRequestsService.rejectAccessRequest(id, decisionRequest)(new HeaderCarrier()).map {
        result =>
          result mustBe Left(AccessRequestStatusInvalidException.forAccessRequest(accessRequest))
      }
    }

    "must return AccessRequestNotFoundException if the access request does not exist" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", Some("test-rejected-reason"))

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(None))

      fixture.accessRequestsService.rejectAccessRequest(id, decisionRequest)(new HeaderCarrier()).map {
        result =>
          result mustBe Left(AccessRequestNotFoundException.forId(id))
      }
    }

    "must send appropriate emails if access request repository updates successfully" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)
      val applicationId = "test-application-id"

      val accessRequest = AccessRequest(
        id = Some(id),
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(fixture.clock),
        requestedBy = "test-requested-by",
        decision = None,
        cancelled = None,
        environmentId = "test"
      )

      val app = Application(
        id = Some(applicationId),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        credentials = Set.empty
      )

      val updated = accessRequest
        .setStatus(Rejected)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.searchService.findById(eqTo(applicationId))(any)).thenReturn(Future.successful(Right(app)))
      when(fixture.accessRequestsEventService.rejected(any())).thenReturn(Future.successful(()))
      when(fixture.emailConnector.sendAccessRejectedEmailToTeam(eqTo(app), eqTo(updated))(any())).thenReturn(Future.successful(Right(())))

      fixture.accessRequestsService.rejectAccessRequest(id, decisionRequest)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsRepository).update(eqTo(updated))
          verify(fixture.emailConnector).sendAccessRejectedEmailToTeam(eqTo(app), eqTo(updated))(any())
          result mustBe Right(())
      }
    }

    "must log the access request rejected event" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)
      val applicationId = "test-application-id"

      val accessRequest = AccessRequest(
        id = Some(id),
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(fixture.clock),
        requestedBy = "test-requested-by",
        decision = None,
        cancelled = None,
        environmentId = "test"
      ).copy(decision = Some(AccessRequestDecision(LocalDateTime.now(clock), "test-requested-by", Some("some rejection"))))

      val app = Application(
        id = Some(applicationId),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        credentials = Set.empty
      )

      val updated = accessRequest
        .setStatus(Rejected)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.searchService.findById(eqTo(applicationId))(any)).thenReturn(Future.successful(Right(app)))
      when(fixture.accessRequestsEventService.rejected(any())).thenReturn(Future.successful(()))
      when(fixture.emailConnector.sendAccessRejectedEmailToTeam(eqTo(app), eqTo(updated))(any())).thenReturn(Future.successful(Right(())))

      fixture.accessRequestsService.rejectAccessRequest(id, decisionRequest)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsEventService).rejected(updated)
          result mustBe Right(())
      }
    }

    "must tolerate email failure and treat as success" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)
      val applicationId = "test-application-id"

      val accessRequest = AccessRequest(
        id = Some(id),
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(fixture.clock),
        requestedBy = "test-requested-by",
        decision = None,
        cancelled = None,
        environmentId = "test"
      )

      val app = Application(
        id = Some(applicationId),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        credentials = Set.empty
      )

      val updated = accessRequest
        .setStatus(Rejected)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.searchService.findById(eqTo(applicationId))(any)).thenReturn(Future.successful(Right(app)))
      when(fixture.emailConnector.sendAccessRejectedEmailToTeam(eqTo(app), eqTo(updated))(any())).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))
      when(fixture.accessRequestsEventService.rejected(any())).thenReturn(Future.successful(()))

      fixture.accessRequestsService.rejectAccessRequest(id, decisionRequest)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsRepository).update(eqTo(updated))
          verify(fixture.emailConnector).sendAccessRejectedEmailToTeam(eqTo(app), eqTo(updated))(any())
          result mustBe Right(())
      }
    }

    "must not send emails if access request repository does not update successfully" in {
      val fixture = buildFixture()
      val id = "test-id"
      val rejectedReason = "test-rejected-reason"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", Some(rejectedReason))
      val applicationId = "test-application-id"

      val accessRequest = AccessRequest(
        id = Some(id),
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Pending,
        endpoints = Seq.empty,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(fixture.clock),
        requestedBy = "test-requested-by",
        decision = None,
        cancelled = None,
        environmentId = "test"
      )

      val app = Application(
        id = Some(applicationId),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        credentials = Set.empty
      )

      val updated = accessRequest
        .setStatus(Rejected)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy, rejectedReason)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      val exception = NotUpdatedException.forAccessRequest(accessRequest)
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Left(exception)))
      when(fixture.searchService.findById(eqTo(applicationId))(any)).thenReturn(Future.successful(Right(app)))
      when(fixture.emailConnector.sendAccessRejectedEmailToTeam(eqTo(app), eqTo(accessRequest))(any())).thenReturn(Future.successful(Right(())))

      fixture.accessRequestsService.rejectAccessRequest(id, decisionRequest)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsRepository).update(eqTo(updated))
          verifyNoInteractions(fixture.emailConnector)
          result mustBe Left(exception)
      }
    }
  }

  "cancelAccessRequest" - {
    "must correctly update a Pending access request to Cancelled" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val accessRequest = buildPendingAccessRequest(applicationId, 1)
      val cancelRequest = AccessRequestCancelRequest("test-requested-by")

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsEventService.cancelled(any())).thenReturn(Future.successful(()))

      val expected = accessRequest.cancel(cancelRequest.toCancelled(fixture.clock))

      fixture.accessRequestsService.cancelAccessRequest(accessRequest.id.value, cancelRequest).map {
        result =>
          verify(fixture.accessRequestsRepository).findById(eqTo(accessRequest.id.value))
          verify(fixture.accessRequestsRepository).update(expected)
          result.value mustBe ()
      }
    }

    "must log the access request cancelled event" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val accessRequest = buildPendingAccessRequest(applicationId, 1)

      val updated = accessRequest.cancel(LocalDateTime.now(clock), "test-requested-by")

      val cancelRequest = AccessRequestCancelRequest("test-requested-by")

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsEventService.cancelled(any())).thenReturn(Future.successful(()))

      fixture.accessRequestsService.cancelAccessRequest(accessRequest.id.value, cancelRequest).map {
        result =>
          verify(fixture.accessRequestsEventService).cancelled(updated)
          result.value mustBe()
      }
    }


    "must return AccessRequestStatusInvalidException if the access request is not Pending" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val accessRequest = buildApprovedAccessRequest(applicationId, 1)
      val cancelRequest = AccessRequestCancelRequest("test-requested-by")

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))

      fixture.accessRequestsService.cancelAccessRequest(accessRequest.id.value, cancelRequest).map {
        result =>
          verify(fixture.accessRequestsRepository).findById(eqTo(accessRequest.id.value))
          verifyNoMoreInteractions(fixture.accessRequestsRepository)
          result.left.value mustBe AccessRequestStatusInvalidException.forAccessRequest(accessRequest)
      }
    }

    "must return AccessRequestNotFoundException if the access request if not found" in {
      val fixture = buildFixture()
      val accessRequestId = "test-access-request-id"
      val cancelRequest = AccessRequestCancelRequest("test-requested-by")

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(None))

      fixture.accessRequestsService.cancelAccessRequest(accessRequestId, cancelRequest).map {
        result =>
          verify(fixture.accessRequestsRepository).findById(eqTo(accessRequestId))
          verifyNoMoreInteractions(fixture.accessRequestsRepository)
          result.left.value mustBe AccessRequestNotFoundException.forId(accessRequestId)
      }
    }
  }

  "cancelAccessRequests" - {
    "must cancel all pending access requests for the application and API and record appropriate events" in {
      val fixture = buildFixture()

      val applicationId = "test-application-id"

      val request1 = buildPendingAccessRequest(applicationId, 1, Some(1))
      val request2 = buildPendingAccessRequest(applicationId, 2, Some(1))
      val request3 = buildPendingAccessRequest(applicationId, 3, Some(2))

      val requests = Seq(request1, request2, request3)

      val accessRequestCancelRequest = AccessRequestCancelRequest("system")
      when(fixture.accessRequestsRepository.find(any(), any())).thenReturn(Future.successful(requests))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsEventService.cancelled(any())).thenReturn(Future.successful(()))

      val now = LocalDateTime.now(clock)

      val cancelled = AccessRequestCancelled(now, "system")

      val request1Cancelled = request1.cancel(cancelled)
      val request2Cancelled = request2.cancel(cancelled)

      fixture.accessRequestsService.cancelAccessRequests(applicationId, request1.apiId).map {
        result =>
          verify(fixture.accessRequestsRepository).find(eqTo(Some(applicationId)), eqTo(Some(Pending)))

          verify(fixture.accessRequestsRepository).update(eqTo(request1Cancelled))
          verify(fixture.accessRequestsRepository).update(eqTo(request2Cancelled))
          verifyNoMoreInteractions(fixture.accessRequestsRepository)
          verify(fixture.accessRequestsEventService).cancelled(request1Cancelled)
          verify(fixture.accessRequestsEventService).cancelled(request2Cancelled)
          verifyNoMoreInteractions(fixture.accessRequestsEventService)
          result.value mustBe ()
      }
    }

    "must cancel all pending access requests for an application and record appropriate events" in {
      val fixture = buildFixture()

      val applicationId = "test-application-id"

      val pending1 = buildPendingAccessRequest(applicationId, 1)
      val pending2 = buildPendingAccessRequest(applicationId, 2)
      val accessRequestCancelRequest = AccessRequestCancelRequest("system")

      when(fixture.accessRequestsRepository.find(any(), any())).thenReturn(Future.successful(Seq(pending1, pending2)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.accessRequestsEventService.cancelled(any())).thenReturn(Future.successful(()))

      val now = LocalDateTime.now(fixture.clock)

      val cancelled = AccessRequestCancelled(now, "system")
      val request1Cancelled = pending1.cancel(cancelled)
      val request2Cancelled = pending2.cancel(cancelled)

      fixture.accessRequestsService.cancelAccessRequests(applicationId).map {
        result =>
          verify(fixture.accessRequestsRepository).find(eqTo(Some(applicationId)), eqTo(Some(Pending)))
          verify(fixture.accessRequestsRepository).update(eqTo(request1Cancelled))
          verify(fixture.accessRequestsRepository).update(eqTo(request2Cancelled))
          verifyNoMoreInteractions(fixture.accessRequestsRepository)
          verify(fixture.accessRequestsEventService).cancelled(request1Cancelled)
          verify(fixture.accessRequestsEventService).cancelled(request2Cancelled)
          verifyNoMoreInteractions(fixture.accessRequestsEventService)
          result.value mustBe ()
      }
    }
  }

  private case class Fixture(
    clock: Clock,
    accessRequestsRepository: AccessRequestsRepository,
    searchService: ApplicationsSearchService,
    accessRequestsService: AccessRequestsService,
    emailConnector: EmailConnector,
    scopeFixer: ScopeFixer,
    accessRequestsEventService: AccessRequestsEventService
  )

  private def buildFixture(): Fixture = {
    val accessRequestsRepository = mock[AccessRequestsRepository]
    val searchService = mock[ApplicationsSearchService]
    val emailConnector = mock[EmailConnector]
    val scopeFixer = mock[ScopeFixer]
    val accessRequestsEventService = mock[AccessRequestsEventService]
    val accessRequestsService = new AccessRequestsService(accessRequestsRepository, searchService, clock, emailConnector, scopeFixer, FakeHipEnvironments, accessRequestsEventService)
    Fixture(clock, accessRequestsRepository, searchService, accessRequestsService, emailConnector, scopeFixer, accessRequestsEventService)
  }

}

object AccessRequestsServiceSpec {

  private val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
  private implicit val headerCarrier: HeaderCarrier = HeaderCarrier()

  private def buildPendingAccessRequest(applicationId: String, accessRequestId: Int, apiId: Option[Int] = None): AccessRequest = {
    AccessRequest(
      id = Some(s"test-access-request-id-$accessRequestId"),
      applicationId = applicationId,
      apiId = s"test-api-id-${apiId.getOrElse(accessRequestId)}",
      apiName = "test-api-name",
      status = Pending,
      endpoints = Seq.empty,
      supportingInformation = "test-supporting-information",
      requested = LocalDateTime.now(),
      requestedBy = "test-requested-by",
      decision = None,
      cancelled = None,
      environmentId = "test"
    )
  }

  private def buildApprovedAccessRequest(applicationId: String, accessRequestId: Int, apiId: Option[Int] = None): AccessRequest = {
    buildPendingAccessRequest(applicationId, accessRequestId, apiId)
      .setDecision(
        AccessRequestDecision(
          decided = LocalDateTime.now(clock),
          decidedBy = "test-decided-by",
          rejectedReason = None
        )
      )
      .setStatus(Approved)
  }

  private def buildApplication(applicationId: String): Application = {
    Application(
      id = Some(applicationId),
      name = "test-app-name",
      created = LocalDateTime.now(clock),
      createdBy = Creator("createdby-email"),
      lastUpdated = LocalDateTime.now(clock),
      teamMembers = Seq(TeamMember(email = "team-email")),
      credentials = Set.empty
    )
  }

}
