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
import org.mockito.{ArgumentMatchers, MockitoSugar}
import org.scalatest.OptionValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import uk.gov.hmrc.apihubapplications.connectors.EmailConnector
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses.AccessRequestLensOps
import uk.gov.hmrc.apihubapplications.models.accessRequest._
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator, Environments, TeamMember}
import uk.gov.hmrc.apihubapplications.models.exception._
import uk.gov.hmrc.apihubapplications.repositories.{AccessRequestsRepository, ApplicationsRepository}
import uk.gov.hmrc.apihubapplications.testhelpers.AccessRequestGenerator
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future

class AccessRequestsServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar with AccessRequestGenerator with TableDrivenPropertyChecks with OptionValues {

  "createAccessRequest" - {
    "must pass the correct requests to the accessRequestsRepository" in {
      val fixture = buildFixture()

      val request = sampleAccessRequestRequest()
      val expected = sampleAccessRequests()

      when(fixture.accessRequestsRepository.insert(any())).thenReturn(Future.successful(expected))

      fixture.accessRequestsService.createAccessRequest(request).map {
        result =>
          verify(fixture.accessRequestsRepository).insert(ArgumentMatchers.eq(request.toAccessRequests(fixture.clock)))
          result mustBe expected
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
            verify(fixture.accessRequestsRepository).find(ArgumentMatchers.eq(applicationIdFilter), ArgumentMatchers.eq(statusFilter))
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
            verify(fixture.accessRequestsRepository).findById(ArgumentMatchers.eq(id))
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
        decision = None
      )

      val updated = accessRequest
        .setStatus(Approved)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.applicationsService.addPrimaryAccess(any())(any())).thenReturn(Future.successful(Right(())))
      when(fixture.applicationsRepository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Left(ApplicationNotFoundException(applicationId))))
      fixture.accessRequestsService.approveAccessRequest(id, decisionRequest, fixture.applicationsService)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsRepository).findById(ArgumentMatchers.eq(id))
          verify(fixture.accessRequestsRepository).update(ArgumentMatchers.eq(updated))
          verify(fixture.applicationsService).addPrimaryAccess(ArgumentMatchers.eq(accessRequest))(any())
          result mustBe Right(())
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
        decision = None
      )

      val app = Application(
        id = Some(applicationId),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        environments = Environments()
      )

      val updated = accessRequest
        .setStatus(Approved)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.applicationsService.addPrimaryAccess(any())(any())).thenReturn(Future.successful(Right(())))
      when(fixture.applicationsRepository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(app)))

      when(fixture.emailConnector.sendAccessApprovedEmailToTeam(ArgumentMatchers.eq(app), ArgumentMatchers.eq(accessRequest))(any())).thenReturn(Future.successful(Right(())))
      fixture.accessRequestsService.approveAccessRequest(id, decisionRequest, fixture.applicationsService)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsRepository).update(ArgumentMatchers.eq(updated))
          verify(fixture.emailConnector).sendAccessApprovedEmailToTeam(ArgumentMatchers.eq(app), ArgumentMatchers.eq(accessRequest))(any())
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
        decision = None
      )

      val app = Application(
        id = Some(applicationId),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        environments = Environments()
      )

      val updated = accessRequest
        .setStatus(Approved)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.applicationsService.addPrimaryAccess(any())(any())).thenReturn(Future.successful(Right(())))
      when(fixture.applicationsRepository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(app)))
      when(fixture.emailConnector.sendAccessApprovedEmailToTeam(ArgumentMatchers.eq(app), ArgumentMatchers.eq(accessRequest))(any())).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))
      fixture.accessRequestsService.approveAccessRequest(id, decisionRequest, fixture.applicationsService)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsRepository).update(ArgumentMatchers.eq(updated))
          verify(fixture.emailConnector).sendAccessApprovedEmailToTeam(ArgumentMatchers.eq(app), ArgumentMatchers.eq(accessRequest))(any())
          result mustBe Right(())
      }
    }

    "must not send emails if access request repository does not update successfully" in {
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
        decision = None
      )

      val app = Application(
        id = Some(applicationId),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        environments = Environments()
      )

      val updated = accessRequest
        .setStatus(Approved)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      val exception = NotUpdatedException.forAccessRequest(accessRequest)
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Left(exception)))
      when(fixture.applicationsService.addPrimaryAccess(any())(any())).thenReturn(Future.successful(Right(())))
      when(fixture.applicationsRepository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(app)))

      when(fixture.emailConnector.sendAccessApprovedEmailToTeam(ArgumentMatchers.eq(app), ArgumentMatchers.eq(accessRequest))(any())).thenReturn(Future.successful(Right(())))
      fixture.accessRequestsService.approveAccessRequest(id, decisionRequest, fixture.applicationsService)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsRepository).update(ArgumentMatchers.eq(updated))
          verifyZeroInteractions(fixture.emailConnector)
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
        decision = None
      )

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))

      fixture.accessRequestsService.approveAccessRequest(id, decisionRequest, fixture.applicationsService)(HeaderCarrier()).map {
        result =>
          result mustBe Left(AccessRequestStatusInvalidException.forAccessRequest(accessRequest))
      }
    }

    "must return AccessRequestNotFoundException if the access request does not exist" in {
      val fixture = buildFixture()
      val id = "test-id"
      val decisionRequest = AccessRequestDecisionRequest("test-decided-by", None)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(None))

      fixture.accessRequestsService.approveAccessRequest(id, decisionRequest, fixture.applicationsService)(HeaderCarrier()).map {
        result =>
          result mustBe Left(AccessRequestNotFoundException.forId(id))
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
        decision = None
      )

      val app = Application(
        id = Some(accessRequest.applicationId),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        environments = Environments()
      )

      val updated = accessRequest
        .setStatus(Rejected)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy, decisionRequest.rejectedReason.value)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.applicationsRepository.findById(accessRequest.applicationId)).thenReturn(Future.successful(Right(app)))
      when(fixture.emailConnector.sendAccessRejectedEmailToTeam(any(), any())(any())).thenReturn(Future.successful(Right(())))
      fixture.accessRequestsService.rejectAccessRequest(id, decisionRequest)(new HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsRepository).findById(ArgumentMatchers.eq(id))
          verify(fixture.accessRequestsRepository).update(ArgumentMatchers.eq(updated))
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
        decision = None
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
        decision = None
      )

      val app = Application(
        id = Some(applicationId),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        environments = Environments()
      )

      val updated = accessRequest
        .setStatus(Rejected)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.applicationsService.addPrimaryAccess(any())(any())).thenReturn(Future.successful(Right(())))
      when(fixture.applicationsRepository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(app)))

      when(fixture.emailConnector.sendAccessRejectedEmailToTeam(ArgumentMatchers.eq(app), ArgumentMatchers.eq(accessRequest))(any())).thenReturn(Future.successful(Right(())))
      fixture.accessRequestsService.rejectAccessRequest(id, decisionRequest)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsRepository).update(ArgumentMatchers.eq(updated))
          verify(fixture.emailConnector).sendAccessRejectedEmailToTeam(ArgumentMatchers.eq(app), ArgumentMatchers.eq(accessRequest))(any())
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
        decision = None
      )

      val app = Application(
        id = Some(applicationId),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        environments = Environments()
      )

      val updated = accessRequest
        .setStatus(Rejected)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Right(())))
      when(fixture.applicationsService.addPrimaryAccess(any())(any())).thenReturn(Future.successful(Right(())))
      when(fixture.applicationsRepository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(app)))
      when(fixture.emailConnector.sendAccessRejectedEmailToTeam(ArgumentMatchers.eq(app), ArgumentMatchers.eq(accessRequest))(any())).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))
      fixture.accessRequestsService.rejectAccessRequest(id, decisionRequest)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsRepository).update(ArgumentMatchers.eq(updated))
          verify(fixture.emailConnector).sendAccessRejectedEmailToTeam(ArgumentMatchers.eq(app), ArgumentMatchers.eq(accessRequest))(any())
          result mustBe Right(())
      }
    }

    "must not send emails if access request repository does not update successfully" in {
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
        decision = None
      )

      val app = Application(
        id = Some(applicationId),
        name = "test-app-name",
        created = LocalDateTime.now(fixture.clock),
        createdBy = Creator("createdby-email"),
        lastUpdated = LocalDateTime.now(fixture.clock),
        teamMembers = Seq(TeamMember(email = "team-email")),
        environments = Environments()
      )

      val updated = accessRequest
        .setStatus(Approved)
        .setDecision(LocalDateTime.now(fixture.clock), decisionRequest.decidedBy)

      when(fixture.accessRequestsRepository.findById(any())).thenReturn(Future.successful(Some(accessRequest)))
      val exception = NotUpdatedException.forAccessRequest(accessRequest)
      when(fixture.accessRequestsRepository.update(any())).thenReturn(Future.successful(Left(exception)))
      when(fixture.applicationsService.addPrimaryAccess(any())(any())).thenReturn(Future.successful(Right(())))
      when(fixture.applicationsRepository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(app)))

      when(fixture.emailConnector.sendAccessRejectedEmailToTeam(ArgumentMatchers.eq(app), ArgumentMatchers.eq(accessRequest))(any())).thenReturn(Future.successful(Right(())))
      fixture.accessRequestsService.approveAccessRequest(id, decisionRequest, fixture.applicationsService)(HeaderCarrier()).map {
        result =>
          verify(fixture.accessRequestsRepository).update(ArgumentMatchers.eq(updated))
          verifyZeroInteractions(fixture.emailConnector)
          result mustBe Left(exception)
      }
    }


  }

  private case class Fixture(
      clock: Clock,
      accessRequestsRepository: AccessRequestsRepository,
      applicationsRepository: ApplicationsRepository,
      applicationsService: ApplicationsService,
      accessRequestsService: AccessRequestsService,
      emailConnector: EmailConnector
  )

  private def buildFixture(): Fixture = {
    val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
    val accessRequestsRepository = mock[AccessRequestsRepository]
    val applicationsRepository = mock[ApplicationsRepository]
    val applicationsService = mock[ApplicationsService]
    val emailConnector = mock[EmailConnector]
    val accessRequestsService = new AccessRequestsService(accessRequestsRepository, applicationsRepository, clock, emailConnector)
    Fixture(clock, accessRequestsRepository, applicationsRepository, applicationsService, accessRequestsService, emailConnector)
  }

}
