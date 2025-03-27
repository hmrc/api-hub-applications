/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.apihubapplications.repositories.models.team.encrypted

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.apihubapplications.models.team.TeamType.ConsumerTeam
import uk.gov.hmrc.apihubapplications.models.team.{Team, TeamType}
import uk.gov.hmrc.apihubapplications.repositories.models.MongoIdentifier
import uk.gov.hmrc.apihubapplications.repositories.models.application.encrypted.SensitiveTeamMember
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, Sensitive}

import java.time.LocalDateTime

case class SensitiveTeam(
  id: Option[String],
  name: String,
  created: LocalDateTime,
  teamMembers: Seq[SensitiveTeamMember],
  teamType: TeamType = ConsumerTeam,
  egresses: Seq[String] = Seq.empty
) extends Sensitive[Team] with MongoIdentifier {

  override def decryptedValue: Team = {
    Team(
      id = id,
      name = name,
      created = created,
      teamMembers = teamMembers.map(_.decryptedValue),
      teamType = teamType,
      egresses = egresses
    )
  }

}

object SensitiveTeam {

  def apply(team: Team): SensitiveTeam = {
    SensitiveTeam(
      id = team.id,
      name = team.name,
      created = team.created,
      teamMembers = team.teamMembers.map(SensitiveTeamMember(_)),
      teamType = team.teamType,
      egresses = team.egresses
    )
  }

  implicit def formatSensitiveTeam(implicit crypto: Encrypter & Decrypter): Format[SensitiveTeam] = {
    Json.using[Json.WithDefaultValues].format[SensitiveTeam]
  }

}
