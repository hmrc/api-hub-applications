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

import uk.gov.hmrc.apihubapplications.models.Lens
import uk.gov.hmrc.apihubapplications.models.application.TeamMember

object TeamLenses {

  val teamId: Lens[Team, Option[String]] =
    Lens[Team, Option[String]](
      get = _.id,
      set = (team, id) => team.copy(id = id)
    )

  val teamName: Lens[Team, String] =
    Lens[Team, String](
      get = _.name,
      set = (team, name) => team.copy(name = name)
    )

  val teamTeamMembers: Lens[Team, Seq[TeamMember]] =
    Lens[Team, Seq[TeamMember]](
      get = _.teamMembers,
      set = (team, teamMembers) => team.copy(teamMembers = teamMembers)
    )

  implicit class TeamLensOps(team: Team) {

    def setId(id: String): Team = {
      teamId.set(team, Some(id))
    }

    def setName(name: String): Team = {
      teamName.set(team, name)
    }

    def setTeamMembers(teamMembers: Seq[TeamMember]): Team = {
      teamTeamMembers.set(team, teamMembers)
    }

    def addTeamMember(teamMember: TeamMember): Team = {
      teamTeamMembers.set(
        team,
        team.teamMembers :+ teamMember
      )
    }

    def addTeamMember(email: String): Team = {
      addTeamMember(TeamMember(email))
    }

    def removeTeamMember(email: String): Team = {
      teamTeamMembers.set(
        team,
        team.teamMembers.filterNot(_.email.equalsIgnoreCase(email))
      )
    }

    def hasTeamMember(email: String): Boolean = {
      team.teamMembers.exists(_.email.equalsIgnoreCase(email))
    }

    def hasTeamMember(teamMember: TeamMember): Boolean = {
      hasTeamMember(teamMember.email)
    }

  }

}
