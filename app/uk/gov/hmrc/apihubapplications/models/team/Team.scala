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

package uk.gov.hmrc.apihubapplications.models.team

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.apihubapplications.models.application.TeamMember
import uk.gov.hmrc.apihubapplications.models.team.TeamType.ConsumerTeam

import java.time.{Clock, LocalDateTime}

case class Team(
  id: Option[String],
  name: String,
  created: LocalDateTime,
  teamMembers: Seq[TeamMember],
  teamType: TeamType,
  egresses: Seq[String]
)

object Team {

  def apply(id: Option[String], name: String, created: LocalDateTime, teamMembers: Seq[TeamMember]): Team = {
    Team(id, name, created, teamMembers, ConsumerTeam, Seq.empty)
  }

  def apply(name: String, created: LocalDateTime, teamMembers: Seq[TeamMember]): Team = {
    Team(None, name, created, teamMembers)
  }

  def apply(name: String, teamMembers: Seq[TeamMember], clock: Clock): Team = {
    Team(name, LocalDateTime.now(clock), teamMembers)
  }

  def apply(name: String, teamMembers: Seq[TeamMember], teamType: TeamType, clock: Clock): Team = {
    Team(None, name, LocalDateTime.now(clock), teamMembers, teamType, Seq.empty)
  }

  implicit val formatTeam: Format[Team] = Json.format[Team]

}
