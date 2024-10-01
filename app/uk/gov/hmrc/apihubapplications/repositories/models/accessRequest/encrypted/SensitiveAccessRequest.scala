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

package uk.gov.hmrc.apihubapplications.repositories.models.accessRequest.encrypted

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, AccessRequestEndpoint, AccessRequestStatus}
import uk.gov.hmrc.apihubapplications.repositories.models.MongoIdentifier
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, Sensitive}
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.crypto.json.JsonEncryption

import java.time.LocalDateTime

case class SensitiveAccessRequest(
  id: Option[String],
  applicationId: String,
  apiId: String,
  apiName: String,
  status: AccessRequestStatus,
  endpoints: Seq[AccessRequestEndpoint],
  supportingInformation: String,
  requested: LocalDateTime,
  requestedBy: SensitiveString,
  decision: Option[SensitiveAccessRequestDecision],
  cancelled: Option[SensitiveAccessRequestCancelled]
) extends Sensitive[AccessRequest] with MongoIdentifier {

  override def decryptedValue: AccessRequest =
    AccessRequest(
      id = id,
      applicationId = applicationId,
      apiId = apiId,
      apiName = apiName,
      status = status,
      endpoints = endpoints,
      supportingInformation = supportingInformation,
      requested = requested,
      requestedBy = requestedBy.decryptedValue,
      decision = decision.map(_.decryptedValue),
      cancelled = cancelled.map(_.decryptedValue)
    )

}

object SensitiveAccessRequest {

  def apply(accessRequest: AccessRequest): SensitiveAccessRequest =
    SensitiveAccessRequest(
      id = accessRequest.id,
      applicationId = accessRequest.applicationId,
      apiId = accessRequest.apiId,
      apiName = accessRequest.apiName,
      status = accessRequest.status,
      endpoints = accessRequest.endpoints,
      supportingInformation = accessRequest.supportingInformation,
      requested = accessRequest.requested,
      requestedBy = SensitiveString(accessRequest.requestedBy),
      decision = accessRequest.decision.map(SensitiveAccessRequestDecision(_)),
      cancelled = accessRequest.cancelled.map(SensitiveAccessRequestCancelled(_))
    )

  implicit def formatSensitiveAccessRequest(implicit crypto: Encrypter & Decrypter): Format[SensitiveAccessRequest] = {
    implicit val sensitiveStringFormat: Format[SensitiveString] = JsonEncryption.sensitiveEncrypterDecrypter(SensitiveString.apply)
    Json.format[SensitiveAccessRequest]
  }

}
