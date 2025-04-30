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

package uk.gov.hmrc.apihubapplications.models.event

import play.api.mvc.QueryStringBindable
import uk.gov.hmrc.apihubapplications.models.{Enumerable, WithName}

sealed trait EventType

case object ApiAdded extends WithName("API_ADDED") with EventType
case object EgressAdded extends WithName("EGRESS_ADDED") with EventType
case object MemberAdded extends WithName("MEMBER_ADDED") with EventType
case object Approved extends WithName("APPROVED") with EventType
case object Canceled extends WithName("CANCELED") with EventType
case object TeamChanged extends WithName("TEAM_CHANGED") with EventType
case object Created extends WithName("CREATED") with EventType
case object CredentialCreated extends WithName("CREDENTIAL_CREATED") with EventType
case object Deleted extends WithName("DELETED") with EventType
case object ScopesFixed extends WithName("SCOPES_FIXED") with EventType
case object Promoted extends WithName("PROMOTED") with EventType
case object Registered extends WithName("REGISTERED") with EventType
case object Rejected extends WithName("REJECTED") with EventType
case object ApiRemoved extends WithName("API_REMOVED") with EventType
case object EgressRemoved extends WithName("EGRESS_REMOVED") with EventType
case object MemberRemoved extends WithName("MEMBER_REMOVED") with EventType
case object Renamed extends WithName("RENAMED") with EventType
case object CredentialRevoked extends WithName("CREDENTIAL_REVOKED") with EventType
case object Updated extends WithName("UPDATED") with EventType

object EventType extends Enumerable.Implicits {

  val values: Seq[EventType] = Seq(ApiAdded, EgressAdded, MemberAdded, Approved, Canceled, TeamChanged, Created,
    CredentialCreated, Deleted, ScopesFixed, Promoted, Registered, Rejected, ApiRemoved, EgressRemoved, MemberRemoved,
    Renamed, CredentialRevoked, Updated
  )

  implicit val enumerable: Enumerable[EventType] =
    Enumerable(values.map(value => value.toString -> value)*)

}
