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

package uk.gov.hmrc.apihubapplications.repositories.models

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.apihubapplications.models.application.TeamMember
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, Sensitive}
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.crypto.json.JsonEncryption

case class SensitiveTeamMember(email: SensitiveString) extends Sensitive[TeamMember] {

  override def decryptedValue: TeamMember = TeamMember(email = email.decryptedValue)

}

object SensitiveTeamMember {

  def apply(teamMember: TeamMember): SensitiveTeamMember = {
    SensitiveTeamMember(email = SensitiveString(teamMember.email))
  }

  implicit def formatSensitiveTeamMember(implicit crypto: Encrypter with Decrypter): Format[SensitiveTeamMember] = {
    implicit val sensitiveStringFormat: Format[SensitiveString] = JsonEncryption.sensitiveEncrypterDecrypter(SensitiveString.apply)
    Json.format[SensitiveTeamMember]
  }

}
