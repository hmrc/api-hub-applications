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
import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.connectors.EmailConnector
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses.*
import uk.gov.hmrc.apihubapplications.models.accessRequest.*
import uk.gov.hmrc.apihubapplications.models.application.Application
import uk.gov.hmrc.apihubapplications.models.event.EventType.descriptionOf
import uk.gov.hmrc.apihubapplications.models.event.{AccessRequestApproved, AccessRequestCanceled, AccessRequestCreated, AccessRequestRejected, EntityType, Event, EventType, Application as ApplicationEvent}
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
                                       eventsService: EventsService
                                     )(implicit ec: ExecutionContext) extends Logging with ExceptionRaising {

  def createAccessRequest(request: AccessRequestRequest)(implicit hc: HeaderCarrier): Future[Seq[AccessRequest]] = {
    accessRequestsRepository.insert(request.toAccessRequests(clock)).flatMap {
      requests => for {
        _ <- sendAccessRequestSubmittedEmails(request)
        _ <- eventsService.log(asEvent(request, requests, AccessRequestCreated))
        } yield requests
    }
  }

  private def asEvent(accessRequestRequest: AccessRequestRequest, accessRequests: Seq[AccessRequest], eventType: EventType): Event = {
    Event(id = None,
      entityId = accessRequestRequest.applicationId,
      entityType = ApplicationEvent,
      eventType = eventType,
      user = accessRequestRequest.requestedBy,
      timestamp = LocalDateTime.now(clock),
      description = descriptionOf(eventType),
      detail = s"Access request ids: ${accessRequests.flatMap(_.id).mkString(",")}",
      parameters = Json.toJson(accessRequestRequest)
    )
  }

  private def asEvent(accessRequestDecisionRequest: AccessRequestDecisionRequest, accessRequestId: String, applicationId: String, eventType: EventType): Event = {
    Event(id = None,
      entityId = applicationId,
      entityType = ApplicationEvent,
      eventType = eventType,
      user = accessRequestDecisionRequest.decidedBy,
      timestamp = LocalDateTime.now(clock),
      description = descriptionOf(eventType),
      detail = s"Access request id: $accessRequestId",
      parameters = Json.toJson(accessRequestDecisionRequest)
    )
  }

  private def asEvent(accessRequestCancelRequest: AccessRequestCancelRequest, accessRequestId: String, applicationId: String, eventType: EventType): Event = {
    Event(id = None,
      entityId = applicationId,
      entityType = ApplicationEvent,
      eventType = eventType,
      user = accessRequestCancelRequest.cancelledBy,
      timestamp = LocalDateTime.now(clock),
      description = descriptionOf(eventType),
      detail = s"Access request id: $accessRequestId",
      parameters = Json.toJson(accessRequestCancelRequest)
    )
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
        val approved = accessRequest
          .setStatus(Approved)
          .setDecision(decisionRequest.copy(rejectedReason = None), clock)

        (for {
          application <- EitherT(searchService.findById(accessRequest.applicationId))
          _ <- EitherT(accessRequestsRepository.update(approved))
          _ <- EitherT(sendAccessApprovedEmails(accessRequest, application)).orElse(EitherT.rightT(())) // ignore email errors
          _ <- EitherT.right(eventsService.log(asEvent(decisionRequest, id, application.id.orNull, AccessRequestApproved))) // ignore events logging errors. Application must have an id to get this far.
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
        accessRequestsRepository.update(
          accessRequest
            .setStatus(Rejected)
            .setDecision(decisionRequest, clock)
        ) flatMap {
          case Right(_) =>
            for {
              _ <- sendAccessRejectedEmails(accessRequest)
              _ <- eventsService.log(asEvent(decisionRequest, id, accessRequest.applicationId, AccessRequestRejected))
            } yield Right(())
          case Left(exception) => Future.successful(Left(exception))
        }
      case Some(accessRequest) =>
        Future.successful(Left(raiseAccessRequestStatusInvalidException.forAccessRequest(accessRequest)))
      case _ =>
        Future.successful(Left(raiseAccessRequestNotFoundException.forId(id)))
    }
  }

  def cancelAccessRequest(id: String, cancelRequest: AccessRequestCancelRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    accessRequestsRepository.findById(id).flatMap {
      case Some(accessRequest) if accessRequest.status == Pending =>
        accessRequestsRepository.update(
          accessRequest
            .cancel(cancelRequest.toCancelled(clock))
        ).map {
          case Right(_) => Right(eventsService.log(asEvent(cancelRequest, id, accessRequest.applicationId, AccessRequestCanceled)))
          case Left(e: ApplicationsException) => Left(e)
        }
      case Some(accessRequest) =>
        Future.successful(Left(raiseAccessRequestStatusInvalidException.forAccessRequest(accessRequest)))
      case _ =>
        Future.successful(Left(raiseAccessRequestNotFoundException.forId(id)))
    }
  }

  def cancelAccessRequests(applicationId: String): Future[Either[ApplicationsException, Unit]] = {
    getAccessRequests(Some(applicationId), Some(Pending)).flatMap(
      accessRequests => {
        Future.sequence(accessRequests.map(pendingAccessRequest => accessRequestsRepository.update(
          pendingAccessRequest
            .cancel(cancelled = LocalDateTime.now(clock), cancelledBy = "system")
        ))).map(useFirstApplicationsException).map {
          case Right(_) => Right(())
          case Left(e) => Left(e)
        }
      })
  }

  def cancelAccessRequests(applicationId: String, apiId: String): Future[Either[ApplicationsException, Unit]] = {
    getAccessRequests(Some(applicationId), Some(Pending))
      .map(_.filter(_.apiId.equals(apiId)))
      .flatMap(
        accessRequests =>
          Future
            .sequence(
              accessRequests.map(
                accessRequest =>
                  accessRequestsRepository.update(
                    accessRequest.cancel(cancelled = LocalDateTime.now(clock), cancelledBy = "system")
                  )
              )
            )
            .map(useFirstApplicationsException)
            .map(_.map(_ => ()))
      )
  }

}
