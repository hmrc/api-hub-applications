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

package uk.gov.hmrc.apihubapplications.repositories.models.application.unencrypted

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.apihubapplications.models.application.Credential

import java.time.LocalDateTime

case class DbCredential(
  clientId: String,
  created: Option[LocalDateTime],
  secretFragment: Option[String]
) extends DbModel[Credential] {

  override def toModel(dbApplication: DbApplication): Credential =
    Credential(
      clientId = clientId,
      created = created.getOrElse(dbApplication.created),
      clientSecret = None,
      secretFragment = secretFragment
    )

}

object DbCredential {

  def apply(credential: Credential): DbCredential =
    DbCredential(
      clientId = credential.clientId,
      created = Some(credential.created),
      secretFragment = credential.secretFragment
    )

  implicit val formatDbCredential: Format[DbCredential] = Json.format[DbCredential]

}
