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

package uk.gov.hmrc.apihubapplications.repositories.models.application.encrypted

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.apihubapplications.models.application.Deleted
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.crypto.json.JsonEncryption
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, Sensitive}

import java.time.LocalDateTime

case class SensitiveDeleted(deleted: LocalDateTime, deletedBy: SensitiveString) extends Sensitive[Deleted] {

  override def decryptedValue: Deleted = Deleted(deleted = deleted, deletedBy = deletedBy.decryptedValue)

}

object SensitiveDeleted {

  def apply(deleted: Deleted): SensitiveDeleted = {
    SensitiveDeleted(deleted.deleted, SensitiveString(deleted.deletedBy))
  }

  implicit def formatSensitiveDeleted(implicit crypto: Encrypter & Decrypter): Format[SensitiveDeleted] = {
    implicit val sensitiveStringFormat: Format[SensitiveString] = JsonEncryption.sensitiveEncrypterDecrypter(SensitiveString.apply)
    Json.format[SensitiveDeleted]
  }

}
