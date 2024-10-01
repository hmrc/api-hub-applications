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

package uk.gov.hmrc.apihubapplications.models.accessRequest

import uk.gov.hmrc.apihubapplications.models.Lens

import java.time.{Clock, LocalDateTime}

object AccessRequestLenses {

  val accessRequestId: Lens[AccessRequest, Option[String]] =
    Lens[AccessRequest, Option[String]](
      get = _.id,
      set = (accessRequest, id) => accessRequest.copy(id = id)
    )

  val accessRequestEndpoints: Lens[AccessRequest, Seq[AccessRequestEndpoint]] =
    Lens[AccessRequest, Seq[AccessRequestEndpoint]](
      get = _.endpoints,
      set = (accessRequest, endpoints) => accessRequest.copy(endpoints = endpoints)
    )

  val accessRequestDecision: Lens[AccessRequest, Option[AccessRequestDecision]] =
    Lens[AccessRequest, Option[AccessRequestDecision]](
      get = _.decision,
      set = (accessRequest, decision) => accessRequest.copy(decision = decision)
    )

  val accessRequestCancelled: Lens[AccessRequest, Option[AccessRequestCancelled]] =
    Lens[AccessRequest, Option[AccessRequestCancelled]](
      get = _.cancelled,
      set = (accessRequest, cancelled) => accessRequest.copy(cancelled = cancelled)
    )

  val accessRequestStatus: Lens[AccessRequest, AccessRequestStatus] =
    Lens[AccessRequest, AccessRequestStatus](
      get = _.status,
      set = (accessRequest, status) => accessRequest.copy(status = status)
    )

  implicit class AccessRequestLensOps(accessRequest: AccessRequest) {

    def setId(id: Option[String]): AccessRequest = {
      accessRequestId.set(accessRequest, id)
    }

    def setId(id: String): AccessRequest = {
      setId(Some(id))
    }

    def setEndpoints(endpoints: Seq[AccessRequestEndpoint]): AccessRequest = {
      accessRequestEndpoints.set(accessRequest, endpoints)
    }

    def addEndpoint(endpoint: AccessRequestEndpoint): AccessRequest = {
      accessRequestEndpoints.set(
        accessRequest,
        accessRequest.endpoints :+ endpoint
      )
    }

    def addEndpoint(httpMethod: String, path: String, scopes: Seq[String]): AccessRequest = {
      addEndpoint(AccessRequestEndpoint(httpMethod, path, scopes))
    }

    def setDecision(decision: Option[AccessRequestDecision]): AccessRequest = {
      accessRequestDecision.set(accessRequest, decision)
    }

    def setDecision(decision: AccessRequestDecision): AccessRequest = {
      setDecision(Some(decision))
    }

    def setDecision(decided: LocalDateTime, decidedBy: String): AccessRequest = {
      setDecision(AccessRequestDecision(decided, decidedBy, None))
    }

    def setDecision(decided: LocalDateTime, decidedBy: String, rejectedReason: String): AccessRequest = {
      setDecision(AccessRequestDecision(decided, decidedBy, Some(rejectedReason)))
    }

    def setDecision(decisionRequest: AccessRequestDecisionRequest, clock: Clock): AccessRequest = {
      setDecision(
        AccessRequestDecision(
          decided = LocalDateTime.now(clock),
          decidedBy = decisionRequest.decidedBy,
          rejectedReason = decisionRequest.rejectedReason
        )
      )
    }

    def cancel(cancelled: AccessRequestCancelled): AccessRequest = {
      accessRequestCancelled
        .set(accessRequest, Some(cancelled))
        .setStatus(Cancelled)
    }

    def cancel(cancelled: LocalDateTime, cancelledBy: String): AccessRequest = {
      cancel(AccessRequestCancelled(cancelled, cancelledBy))
    }

    def setStatus(status: AccessRequestStatus): AccessRequest = {
      accessRequestStatus.set(accessRequest, status)
    }

  }

}
