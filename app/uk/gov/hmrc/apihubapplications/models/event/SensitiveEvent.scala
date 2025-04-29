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

package uk.gov.hmrc.apihubapplications.models.event

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.apihubapplications.repositories.models.MongoIdentifier
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.crypto.json.JsonEncryption
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, Sensitive}

import java.time.LocalDateTime

case class SensitiveEvent(
  id: Option[String],
  entityId: String,
  entityType: EntityType,
  eventType: EventType,
  user: SensitiveString,
  timestamp: LocalDateTime,
  description: SensitiveString,
  detail: SensitiveString,
  parameters: SensitiveJsValue) extends Sensitive[Event] with MongoIdentifier {

  override def decryptedValue: Event = {
    Event(
      id = id,
      entityId = entityId,
      entityType = entityType,
      eventType = eventType,
      user = user.decryptedValue,
      timestamp = timestamp,
      description = description.decryptedValue,
      detail = detail.decryptedValue,
      parameters = parameters.decryptedValue
    )
  }
}

object SensitiveEvent {

  def apply(event: Event): SensitiveEvent = {
    SensitiveEvent(
      id = event.id,
      entityId = event.entityId,
      entityType = event.entityType,
      eventType = event.eventType,
      user = SensitiveString(event.user),
      timestamp = event.timestamp,
      description = SensitiveString(event.description),
      detail = SensitiveString(event.detail),
      parameters = SensitiveJsValue(event.parameters)
    )
  }

  implicit def formatSensitiveEvent(implicit crypto: Encrypter & Decrypter): Format[SensitiveEvent] = {
    implicit val sensitiveStringFormat: Format[SensitiveString] = JsonEncryption.sensitiveEncrypterDecrypter(SensitiveString.apply)
    implicit val sensitiveJsValueFormat: Format[SensitiveJsValue] = JsonEncryption.sensitiveEncrypterDecrypter(SensitiveJsValue.apply)

    Json.format[SensitiveEvent]
  }
}