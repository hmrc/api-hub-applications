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

import cats.data.EitherT
import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.connectors.EmailConnector
import uk.gov.hmrc.apihubapplications.models.accessRequest.*
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses.*
import uk.gov.hmrc.apihubapplications.models.application.Application
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.repositories.AccessRequestsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.useFirstApplicationsException
import uk.gov.hmrc.apihubapplications.services.helpers.ScopeFixer
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AccessRequestsService @Inject()(
                                       accessRequestsRepository: AccessRequestsRepository,
                                       searchService: ApplicationsSearchService,
                                       clock: Clock,
                                       emailConnector: EmailConnector,
                                       scopeFixer: ScopeFixer,
                                       hipEnvironments: HipEnvironments,
                                       accessRequestsEventService: AccessRequestsEventService
                                     )(implicit ec: ExecutionContext) extends Logging with ExceptionRaising {

  private val system = "system"

  def createAccessRequest(request: AccessRequestRequest)(implicit hc: HeaderCarrier): Future[Seq[AccessRequest]] = {
    accessRequestsRepository.insert(request.toAccessRequests(clock)).flatMap {
      requests =>
        val emails = sendAccessRequestSubmittedEmails(request)
        val events = accessRequestsEventService.create(request, requests)
        for {
          _ <- emails
          _ <- events
        } yield requests
    }
  }

  private def sendAccessRequestSubmittedEmails(accessRequest: AccessRequestRequest)(implicit hc: HeaderCarrier) = {
    searchService.findById(accessRequest.applicationId).flatMap {
      case Right(application) =>
        for {
          _ <- emailConnector.sendAccessRequestSubmittedEmailToRequester(application, accessRequest)
          _ <- emailConnector.sendNewAccessRequestEmailToApprovers(application, accessRequest)
        } yield Future.successful(Right(()))
      case Left(exception) => Future.successful(Left(exception))
    }
  }

  def getAccessRequests(applicationId: Option[String], status: Option[AccessRequestStatus]): Future[Seq[AccessRequest]] = {
    accessRequestsRepository.find(applicationId, status)
  }

  def getAccessRequest(id: String): Future[Option[AccessRequest]] = {
    accessRequestsRepository.findById(id)
  }

  private def sendAccessApprovedEmails(accessRequest: AccessRequest, application: Application)(implicit hc: HeaderCarrier) = {
    emailConnector.sendAccessApprovedEmailToTeam(application, accessRequest)
  }

  private def sendAccessRejectedEmails(accessRequest: AccessRequest)(implicit hc: HeaderCarrier) = {
    searchService.findById(accessRequest.applicationId).flatMap {
      case Right(application) => emailConnector.sendAccessRejectedEmailToTeam(application, accessRequest)
      case Left(exception) => Future.successful(Left(exception))
    }
  }

  def approveAccessRequest(id: String, decisionRequest: AccessRequestDecisionRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    accessRequestsRepository.findById(id).flatMap {
      case Some(accessRequest) if accessRequest.status == Pending =>
        
        val approvedRequest = accessRequest
          .setStatus(Approved)
          .setDecision(decisionRequest.copy(rejectedReason = None), clock)

        (for {
          application <- EitherT(searchService.findById(accessRequest.applicationId))
          _ <- EitherT(accessRequestsRepository.update(approvedRequest))
          _ <- EitherT.right(accessRequestsEventService.approve(decisionRequest, approvedRequest))
          _ <- EitherT(sendAccessApprovedEmails(approvedRequest, application)).orElse(EitherT.rightT(())) // ignore email errors
          accessRequests <- EitherT.right(getAccessRequests(Some(accessRequest.applicationId), None))
          _ <- EitherT(scopeFixer.fix(application, accessRequests, hipEnvironments.forId(accessRequest.environmentId)))
        } yield ()).value

      case Some(accessRequest) =>
        Future.successful(Left(raiseAccessRequestStatusInvalidException.forAccessRequest(accessRequest)))
      case _ =>
        Future.successful(Left(raiseAccessRequestNotFoundException.forId(id)))
    }
  }

  def rejectAccessRequest(id: String, decisionRequest: AccessRequestDecisionRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    accessRequestsRepository.findById(id).flatMap {
      case Some(accessRequest) if accessRequest.status == Pending =>

        val rejectedRequest = accessRequest
          .setStatus(Rejected)
          .setDecision(decisionRequest, clock)

        (for {
          application <- EitherT(searchService.findById(accessRequest.applicationId))
          _ <- EitherT(accessRequestsRepository.update(rejectedRequest))
          _ <- EitherT.right(accessRequestsEventService.reject(decisionRequest, rejectedRequest))
          _ <- EitherT(sendAccessRejectedEmails(rejectedRequest)).orElse(EitherT.rightT(())) // ignore email errors
        } yield ()).value
      case Some(accessRequest) =>
        Future.successful(Left(raiseAccessRequestStatusInvalidException.forAccessRequest(accessRequest)))
      case _ =>
        Future.successful(Left(raiseAccessRequestNotFoundException.forId(id)))
    }
  }

  def cancelAccessRequest(id: String, cancelRequest: AccessRequestCancelRequest)(implicit hc: HeaderCarrier) = {
    accessRequestsRepository.findById(id).flatMap {
      case Some(accessRequest) if accessRequest.status == Pending =>
        
        val cancelledRequest = accessRequest
          .cancel(cancelRequest.toCancelled(clock))
        
        (for {
          _ <- EitherT(accessRequestsRepository.update(cancelledRequest))
          _ <- EitherT.right(accessRequestsEventService.cancel(cancelRequest, cancelledRequest))
        } yield ()).value
      case Some(accessRequest) =>
        Future.successful(Left(raiseAccessRequestStatusInvalidException.forAccessRequest(accessRequest)))
      case _ =>
        Future.successful(Left(raiseAccessRequestNotFoundException.forId(id)))
    }
  }

  def cancelAccessRequests(applicationId: String): Future[Either[ApplicationsException, Unit]] = {
    getAccessRequests(Some(applicationId), Some(Pending)).flatMap(
      accessRequests => {
        Future.sequence(accessRequests.map(pendingAccessRequest => {
            val now = LocalDateTime.now(clock)
            val cancelledRequest = pendingAccessRequest
              .cancel(cancelled = now, cancelledBy = system)
            val accessRequestAccessRequest: AccessRequestCancelRequest = AccessRequestCancelRequest(system)
            (for {
              _ <- EitherT(accessRequestsRepository.update(cancelledRequest))
              _ <- EitherT.right(accessRequestsEventService.cancel(accessRequestAccessRequest, cancelledRequest))
            } yield ()).value
          }))
          .map(useFirstApplicationsException).map {
            case Right(_) => Right(())
            case Left(e) => Left(e)
          }
      })
  }

  def cancelAccessRequests(applicationId: String, apiId: String): Future[Either[ApplicationsException, Unit]] = {
    getAccessRequests(Some(applicationId), Some(Pending))
      .map(_.filter(_.apiId.equals(apiId)))
      .flatMap(
        accessRequests => {
          Future.sequence(accessRequests.map(pendingAccessRequest => {
              val now = LocalDateTime.now(clock)
              
              val cancelledRequest = pendingAccessRequest
                .cancel(cancelled = now, cancelledBy = system)
            
              val accessRequestAccessRequest: AccessRequestCancelRequest = AccessRequestCancelRequest(system)
              (for {
                _ <- EitherT(accessRequestsRepository.update(cancelledRequest))
                _ <- EitherT.right(accessRequestsEventService.cancel(accessRequestAccessRequest, cancelledRequest))
              } yield ()).value
            }))
            .map(useFirstApplicationsException).map {
              case Right(_) => Right(())
              case Left(e) => Left(e)
            }
        }
      )
  }

}
