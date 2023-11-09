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
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestDecision
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, Sensitive}
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.crypto.json.JsonEncryption

import java.time.LocalDateTime

case class SensitiveAccessRequestDecision(decided: LocalDateTime, decidedBy: SensitiveString, rejectedReason: Option[String]) extends Sensitive[AccessRequestDecision] {

  override def decryptedValue: AccessRequestDecision =
    AccessRequestDecision(
      decided = decided,
      decidedBy = decidedBy.decryptedValue,
      rejectedReason = rejectedReason
    )

}

object SensitiveAccessRequestDecision {

  def apply(decision: AccessRequestDecision): SensitiveAccessRequestDecision =
    SensitiveAccessRequestDecision(
      decided = decision.decided,
      decidedBy = SensitiveString(decision.decidedBy),
      rejectedReason = decision.rejectedReason
    )

  implicit def formatSensitiveAccessRequestDecision(implicit crypto: Encrypter with Decrypter): Format[SensitiveAccessRequestDecision] = {
    implicit val sensitiveStringFormat: Format[SensitiveString] = JsonEncryption.sensitiveEncrypterDecrypter(SensitiveString.apply)
    Json.format[SensitiveAccessRequestDecision]
  }

}
