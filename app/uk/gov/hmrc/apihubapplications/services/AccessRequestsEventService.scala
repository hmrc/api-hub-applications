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

package uk.gov.hmrc.apihubapplications.services

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.models.accessRequest.*
import uk.gov.hmrc.apihubapplications.models.event.*

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}


@Singleton
class AccessRequestsEventService @Inject()(eventService: EventsService, clock: Clock)(implicit ec: ExecutionContext) extends Logging {

  def approve(accessRequestDecisionRequest: AccessRequestDecisionRequest, accessRequestId: String, applicationId: String, timestamp: LocalDateTime): Future[Unit] = {
    eventService.log(Event.newEvent(
      entityId = applicationId,
      entityType = Application,
      eventType = AccessRequestApproved,
      user = accessRequestDecisionRequest.decidedBy,
      timestamp = timestamp,
      description = "Access request approved",
      detail = s"Access request id: $accessRequestId",
      parameters = Seq(Parameter("approvedBy", accessRequestDecisionRequest.decidedBy),
        Parameter("accessRequestId", accessRequestId)) *
    ))
  }

  def cancel(accessRequestCancelRequest: AccessRequestCancelRequest, accessRequestId: String, applicationId: String, timestamp: LocalDateTime): Future[Unit] = {
            eventService.log(Event.newEvent(
             entityId = applicationId,
             entityType = Application,
             eventType = AccessRequestCanceled,
             user = accessRequestCancelRequest.cancelledBy,
             timestamp = timestamp,
             description = "Access request cancelled",
             detail = s"Access request id: $accessRequestId",
             parameters = Seq(Parameter("cancelledBy", accessRequestCancelRequest.cancelledBy),
               Parameter("accessRequestId", accessRequestId)) *
            ))
  }

  def reject(accessRequestDecisionRequest: AccessRequestDecisionRequest, accessRequestId: String, applicationId: String, timestamp: LocalDateTime): Future[Unit] = {
    eventService.log(Event.newEvent(
      entityId = applicationId,
      entityType = Application,
      eventType = AccessRequestRejected,
      user = accessRequestDecisionRequest.decidedBy,
      timestamp = timestamp,
      description = "Access request rejected",
      detail = s"Access request id: $accessRequestId",
      parameters = Seq(
        Parameter("rejectedBy", accessRequestDecisionRequest.decidedBy),
        Parameter("rejectionReason", accessRequestDecisionRequest.decidedBy),
        Parameter("accessRequestId", accessRequestId)) *
    ))
  }

  def create(accessRequestRequest: AccessRequestRequest, accessRequests: Seq[AccessRequest]): Future[Unit] = {

    val loggableAccessRequests = accessRequests.map(accessRequest =>
      (("apiName", accessRequest.apiName),
        ("endpoints", accessRequest.endpoints),
        ("accessRequestId", accessRequest.id.get)))

    val timestamp = accessRequests.headOption.map(_.requested).getOrElse(LocalDateTime.now(clock))

    eventService.log(Event.newEvent(
      entityId = accessRequestRequest.applicationId,
      entityType = Application,
      eventType = AccessRequestCreated,
      user = accessRequestRequest.requestedBy,
      timestamp = timestamp,
      description = "Access request created",
      detail = s"Access request ids: ${accessRequests.flatMap(_.id).mkString(",")}",
      parameters = Seq(
        Parameter("applicationId", accessRequestRequest.applicationId),
        Parameter("accessRequests", loggableAccessRequests),
        Parameter("supportingInformation", accessRequestRequest.supportingInformation),
        Parameter("requested", timestamp),
        Parameter("requestedBy", accessRequestRequest.requestedBy),
        Parameter("environmentId", accessRequestRequest.environmentId)) *
    ))
  }

}
