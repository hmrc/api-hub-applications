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
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses._
import uk.gov.hmrc.apihubapplications.models.accessRequest._
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.repositories.AccessRequestsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.{useFirstApplicationsException, useFirstException}
import uk.gov.hmrc.http.HeaderCarrier

import java.time.Clock
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AccessRequestsService @Inject()(
                                       repository: AccessRequestsRepository,
                                       clock: Clock,
                                       applicationsService: ApplicationsService
                                     )(implicit ec: ExecutionContext) extends Logging with ExceptionRaising {

  def createAccessRequest(request: AccessRequestRequest): Future[Seq[AccessRequest]] = {
    repository.insert(request.toAccessRequests(clock))
  }

  def getAccessRequests(applicationId: Option[String], status: Option[AccessRequestStatus]): Future[Seq[AccessRequest]] = {
    repository.find(applicationId, status)
  }

  def getAccessRequest(id: String): Future[Option[AccessRequest]] = {
    repository.findById(id)
  }

  def approveAccessRequest(id: String, decisionRequest: AccessRequestDecisionRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    repository.findById(id).flatMap {
      case Some(accessRequest) if accessRequest.status == Pending =>
        applicationsService.addPrimaryAccess(accessRequest).flatMap(
          _ =>
            repository.update(
              accessRequest
                .setStatus(Approved)
                .setDecision(decisionRequest.copy(rejectedReason = None), clock)
            )
        )
      case Some(accessRequest) =>
        Future.successful(Left(raiseAccessRequestStatusInvalidException.forAccessRequest(accessRequest)))
      case _ =>
        Future.successful(Left(raiseAccessRequestNotFoundException.forId(id)))
    }
  }

  def rejectAccessRequest(id: String, decisionRequest: AccessRequestDecisionRequest): Future[Either[ApplicationsException, Unit]] = {
    repository.findById(id).flatMap {
      case Some(accessRequest) if accessRequest.status == Pending =>
        repository.update(
          accessRequest
            .setStatus(Rejected)
            .setDecision(decisionRequest, clock)
        )
      case Some(accessRequest) =>
        Future.successful(Left(raiseAccessRequestStatusInvalidException.forAccessRequest(accessRequest)))
      case _ =>
        Future.successful(Left(raiseAccessRequestNotFoundException.forId(id)))
    }
  }

  def cancelAccessRequests(applicationId: String) = {
    getAccessRequests(Some(applicationId), Some(Pending)).flatMap(
      accessRequests => {
        Future.sequence(accessRequests.map(pendingAccessRequest => repository.update(
          pendingAccessRequest
            .setStatus(Cancelled)
        ))).map(useFirstApplicationsException).map {
          case Right(_) => Right(())
          case Left(e) => Left(e)
        }
      })
  }

}
