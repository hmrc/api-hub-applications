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

package uk.gov.hmrc.apihubapplications.services

import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.apihubapplications.models.event
import uk.gov.hmrc.apihubapplications.models.event.{Created, EgressAdded, EgressRemoved, Event, EventType, MemberAdded, MemberRemoved, Parameter, Renamed}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.models.team.TeamLenses.*

import java.time.{Clock, LocalDateTime}
import scala.concurrent.Future

@Singleton
class TeamsEventService @Inject(
  eventsService: EventsService,
  clock: Clock
) {
  def create(team: Team, requestingUser: String): Future[Unit] = {
    logEvent(team, Created, requestingUser, team.created,
      Parameter("teamName", team.name),
      Parameter("teamMembers", team.teamMembers.map(_.email)),
      Parameter("teamType", team.teamType.toString)
    )
  }

  def addMember(team: Team, requestingUser: String, newTeamMember: String, timestamp: LocalDateTime = LocalDateTime.now(clock)): Future[Unit] = {
    logEvent(team, MemberAdded, requestingUser, timestamp,
      Parameter("teamMember", newTeamMember)
    )
  }

  def removeMember(team: Team, requestingUser: String, removedTeamMember: String): Future[Unit] = {
    logEvent(team, MemberRemoved, requestingUser, LocalDateTime.now(clock),
      Parameter("teamMember", removedTeamMember)
    )
  }

  def rename(teamWithNewName: Team, requestingUser: String, oldTeamName: String): Future[Unit] = {
    logEvent(teamWithNewName, Renamed, requestingUser, LocalDateTime.now(clock),
      Parameter("oldName", oldTeamName),
      Parameter("newName", teamWithNewName.name)
    )
  }

  def addEgresses(team: Team, requestingUser: String, newEgresses: Seq[String], timestamp: LocalDateTime = LocalDateTime.now(clock)): Future[Unit] = {
    logEvent(team, EgressAdded, requestingUser, timestamp,
      Parameter("egresses", newEgresses)
    )
  }

  def removeEgress(team: Team, requestingUser: String, removedEgress: String, timestamp: LocalDateTime = LocalDateTime.now(clock)): Future[Unit] = {
    logEvent(team, EgressRemoved, requestingUser, timestamp,
      Parameter("egress", removedEgress)
    )
  }

  private def logEvent(team: Team, eventType: EventType, user: String, timestamp: LocalDateTime, parameters: Parameter *): Future[Unit] = {
    eventsService.log(Event.newEvent(
      entityId = team.safeId,
      entityType = event.Team,
      eventType = eventType,
      user = user,
      timestamp = timestamp,
      description = "",
      detail = "",
      parameters = parameters *
    ))
  }

}
