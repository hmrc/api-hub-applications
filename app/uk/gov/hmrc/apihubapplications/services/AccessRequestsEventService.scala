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
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.models.accessRequest.*
import uk.gov.hmrc.apihubapplications.models.event.{AccessRequestApproved, AccessRequestCreated, AccessRequestRejected, Application, Event, Parameter, AccessRequestCancelled as AccessRequestCancelledEvent}

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}


@Singleton
class AccessRequestsEventService @Inject()(eventService: EventsService,
                                           clock: Clock,
                                           hipEnvironments: HipEnvironments)(implicit ec: ExecutionContext) extends Logging {

  private def asParameters(accessRequest: AccessRequest) = {
    Seq(
      Parameter("accessRequestId", accessRequest.id.get),
      Parameter("apiId", accessRequest.apiId),
      Parameter("apiTitle", accessRequest.apiName),
      Parameter("environmentId", accessRequest.environmentId)
    )
  }

  def approve(accessRequestDecisionRequest: AccessRequestDecisionRequest, accessRequest: AccessRequest, timestamp: LocalDateTime): Future[Unit] = {
    eventService.log(Event.newEvent(
    entityId = accessRequest.applicationId,
    entityType = Application,
    eventType = AccessRequestApproved,
    user = accessRequestDecisionRequest.decidedBy,
    timestamp = timestamp,
    description = accessRequest.apiName,
    detail = s"This request for access to ${accessRequest.apiName} was approved and scopes were added to the application's credentials in the ${hipEnvironments.forId(accessRequest.environmentId).name} environment.",
    parameters = asParameters(accessRequest) *
    ))
  }


  def cancel(accessRequestCancelRequest: AccessRequestCancelRequest, accessRequest: AccessRequest, timestamp: LocalDateTime): Future[Unit] = {
    eventService.log(Event.newEvent(
      entityId = accessRequest.applicationId,
      entityType = Application,
      eventType = AccessRequestCancelledEvent,
      user = accessRequestCancelRequest.cancelledBy,
      timestamp = timestamp,
      description = s"Cancelled for ${accessRequest.apiName}",
      detail = s"This request for access to ${accessRequest.apiName} in the ${hipEnvironments.forId(accessRequest.environmentId).name} environment was cancelled.",
      parameters = asParameters(accessRequest) *
    ))
  }

  def reject(accessRequestDecisionRequest: AccessRequestDecisionRequest, accessRequest: AccessRequest, timestamp: LocalDateTime): Future[Unit] = {
    eventService.log(Event.newEvent(
      entityId = accessRequest.applicationId,
      entityType = Application,
      eventType = AccessRequestRejected,
      user = accessRequestDecisionRequest.decidedBy,
      timestamp = timestamp,
      description = s"Rejected for ${accessRequest.apiName}",
      detail = s"This request for access to ${accessRequest.apiName} in the ${hipEnvironments.forId(accessRequest.environmentId).name} environment was rejected.",
      parameters = asParameters(accessRequest) *

    ))
  }

  def create(accessRequestRequest: AccessRequestRequest, accessRequests: Seq[AccessRequest]): Future[Unit] = {

    val timestamp = accessRequests.headOption.map(_.requested).getOrElse(LocalDateTime.now(clock))

    val eventFutures = accessRequests.map(accessRequest =>
      eventService.log(Event.newEvent(
        entityId = accessRequestRequest.applicationId,
        entityType = Application,
        eventType = AccessRequestCreated,
        user = accessRequestRequest.requestedBy,
        timestamp = timestamp,
        description = s"${accessRequest.apiName}",
        detail = s"This access request was created for the ${hipEnvironments.forId(accessRequest.environmentId).name} environment requesting access to ${accessRequest.apiName}.",
        parameters = asParameters(accessRequest) *
      )))

    Future.sequence(eventFutures).map(_ => Future.successful(()))
  }

}
