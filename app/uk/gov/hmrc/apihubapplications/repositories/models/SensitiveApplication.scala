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

import play.api.libs.json.{Format, JsObject, JsPath, Json, Reads, Writes, __}
import uk.gov.hmrc.apihubapplications.models.application.{Application, Environments}
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, Sensitive}

import java.time.LocalDateTime

case class SensitiveApplication(
  id: Option[String],
  name: String,
  created: LocalDateTime,
  createdBy: SensitiveCreator,
  lastUpdated: LocalDateTime,
  teamMembers: Seq[SensitiveTeamMember],
  environments: Environments
) extends Sensitive[Application] {

  override def decryptedValue: Application = {
    Application(
      id = id,
      name = name,
      created = created,
      createdBy = createdBy.decryptedValue,
      lastUpdated = lastUpdated,
      teamMembers = teamMembers.map(_.decryptedValue),
      environments = environments,
      issues = Seq.empty
    )
  }

}

object SensitiveApplication {

  def apply(application: Application): SensitiveApplication = {
    SensitiveApplication(
      id = application.id,
      name = application.name,
      created = application.created,
      createdBy = SensitiveCreator(application.createdBy),
      lastUpdated = application.lastUpdated,
      teamMembers = application.teamMembers.map(SensitiveTeamMember(_)),
      environments = application.environments
    )
  }

  private def defaultFormat(implicit crypto: Encrypter with Decrypter): Format[SensitiveApplication] = {
    Json.format[SensitiveApplication]
  }

  private def writesSensitiveApplicationWithId(implicit crypto: Encrypter with Decrypter): Writes[SensitiveApplication] = {
    SensitiveApplication.defaultFormat.transform(
      json => json.transform(
        JsPath.json.update((JsPath \ "_id" \ "$oid").json.copyFrom((JsPath \ "id").json.pick))
          andThen (JsPath \ "id").json.prune
      ).get
    )
  }

  private def writesSensitiveApplication(implicit crypto: Encrypter with Decrypter): Writes[SensitiveApplication] = {
    (application: SensitiveApplication) => {
      application.id match {
        case Some(_) => writesSensitiveApplicationWithId.writes(application)
        case _ => SensitiveApplication.defaultFormat.writes(application)
      }
    }
  }

  private def readsSensitiveApplication(implicit crypto: Encrypter with Decrypter): Reads[SensitiveApplication] = {
    JsPath.json.update((JsPath \ "id").json
      .copyFrom((JsPath \ "_id" \ "$oid").json.pick))
      .andThen(JsPath.json.update(__.read[JsObject].map(o => o ++ Json.obj("issues" -> Json.arr()))))
      .andThen(SensitiveApplication.defaultFormat)
  }

  implicit def formatSensitiveApplication(implicit crypto: Encrypter with Decrypter): Format[SensitiveApplication] = {
    Format(readsSensitiveApplication, writesSensitiveApplication)
  }

}
