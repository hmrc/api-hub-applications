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

import play.api.libs.json.*
import uk.gov.hmrc.apihubapplications.repositories.models.MongoIdentifier
import uk.gov.hmrc.apihubapplications.repositories.models.application.unencrypted.{DbApi, DbApplication, DbEnvironments}
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, Sensitive}

import java.time.LocalDateTime

case class SensitiveApplication(
  id: Option[String],
  name: String,
  created: LocalDateTime,
  createdBy: SensitiveCreator,
  lastUpdated: LocalDateTime,
  teamId: Option[String],
  teamMembers: Seq[SensitiveTeamMember],
  environments: DbEnvironments,
  apis: Option[Seq[DbApi]],
  deleted: Option[SensitiveDeleted],
) extends Sensitive[DbApplication] with MongoIdentifier {

  override def decryptedValue: DbApplication = {
    DbApplication(
      id = id,
      name = name,
      created = created,
      createdBy = createdBy.decryptedValue,
      lastUpdated = lastUpdated,
      teamId = teamId,
      teamMembers = teamMembers.map(_.decryptedValue),
      environments = environments,
      apis = apis,
      deleted = deleted.map(_.decryptedValue),
    )
  }

}

object SensitiveApplication {

  def apply(dbApplication: DbApplication): SensitiveApplication = {
    SensitiveApplication(
      id = dbApplication.id,
      name = dbApplication.name,
      created = dbApplication.created,
      createdBy = SensitiveCreator(dbApplication.createdBy),
      lastUpdated = dbApplication.lastUpdated,
      teamId = dbApplication.teamId,
      teamMembers = dbApplication.teamMembers.map(SensitiveTeamMember(_)),
      environments = dbApplication.environments,
      apis = dbApplication.apis,
      deleted = dbApplication.deleted.map(SensitiveDeleted(_)),
    )
  }

  implicit def formatSensitiveApplication(implicit crypto: Encrypter & Decrypter): Format[SensitiveApplication] = {
    Json.format[SensitiveApplication]
  }

}
