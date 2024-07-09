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

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.connectors.EmailConnector
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses._
import uk.gov.hmrc.apihubapplications.models.accessRequest._
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.repositories.AccessRequestsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.useFirstApplicationsException
import uk.gov.hmrc.http.HeaderCarrier

import java.time.Clock
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AccessRequestsService @Inject()(
                                       accessRequestsRepository: AccessRequestsRepository,
                                       applicationsSearchService: ApplicationsSearchService,
                                       applicationsCredentialsService: ApplicationsCredentialsService,
                                       clock: Clock,
                                       emailConnector: EmailConnector
                                     )(implicit ec: ExecutionContext) extends Logging with ExceptionRaising {

  def createAccessRequest(request: AccessRequestRequest)(implicit hc: HeaderCarrier): Future[Seq[AccessRequest]] = {
    accessRequestsRepository.insert(request.toAccessRequests(clock)).flatMap {
      requests => sendAccessRequestSubmittedEmails(request) map {
        _ => requests
      }
    }
  }

  private def sendAccessRequestSubmittedEmails(accessRequest: AccessRequestRequest)(implicit hc: HeaderCarrier) = {
    applicationsSearchService.findById(accessRequest.applicationId, false).flatMap {
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

  private def sendAccessApprovedEmails(accessRequest: AccessRequest)(implicit hc: HeaderCarrier) = {
    applicationsSearchService.findById(accessRequest.applicationId, false).flatMap {
      case Right(application) => emailConnector.sendAccessApprovedEmailToTeam(application, accessRequest)
      case Left(exception) => Future.successful(Left(exception))
    }
  }

  private def sendAccessRejectedEmails(accessRequest: AccessRequest)(implicit hc: HeaderCarrier) = {
    applicationsSearchService.findById(accessRequest.applicationId, false).flatMap {
      case Right(application) => emailConnector.sendAccessRejectedEmailToTeam(application, accessRequest)
      case Left(exception) => Future.successful(Left(exception))
    }
  }

  def approveAccessRequest(id: String, decisionRequest: AccessRequestDecisionRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    accessRequestsRepository.findById(id).flatMap {
      case Some(accessRequest) if accessRequest.status == Pending =>
        applicationsCredentialsService.addPrimaryAccess(accessRequest).flatMap(
          _ => {
            accessRequestsRepository.update(
              accessRequest
                .setStatus(Approved)
                .setDecision(decisionRequest.copy(rejectedReason = None), clock)
            ) flatMap {
              case Right(_) =>
                sendAccessApprovedEmails(accessRequest).flatMap {
                  _ => Future.successful(Right(()))
                }
              case Left(exception) => Future.successful(Left(exception))
            }
          }

        )
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
            sendAccessRejectedEmails(accessRequest).flatMap {
              _ => Future.successful(Right(()))
            }
          case Left(exception) => Future.successful(Left(exception))
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
            .setStatus(Cancelled)
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
                  accessRequestsRepository.update(accessRequest.setStatus(Cancelled))
              )
            )
            .map(useFirstApplicationsException)
            .map(_.map(_ => ()))
      )
  }

}
