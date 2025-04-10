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

package uk.gov.hmrc.apihubapplications.services

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.connectors.EmailConnector
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.models.requests.TeamMemberRequest
import uk.gov.hmrc.apihubapplications.models.team.TeamLenses.*
import uk.gov.hmrc.apihubapplications.models.team.{AddEgressesRequest, NewTeam, RenameTeamRequest, Team}
import uk.gov.hmrc.apihubapplications.repositories.TeamsRepository
import uk.gov.hmrc.http.HeaderCarrier

import java.time.Clock
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TeamsService @Inject()(
  repository: TeamsRepository,
  clock: Clock,
  emailConnector: EmailConnector
)(implicit ec: ExecutionContext) extends Logging with ExceptionRaising {

  def create(newTeam: NewTeam)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Team]] = {
    repository.insert(newTeam.toTeam(clock)).flatMap {
      case Right(team) =>
        emailConnector.sendTeamMemberAddedEmailToTeamMembers(team.teamMembers, team) flatMap {
          _ => Future.successful(Right(team))
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

  def findAll(teamMember: Option[String]): Future[Seq[Team]] = {
    repository.findAll(teamMember)
  }

  def findById(id: String): Future[Either[ApplicationsException, Team]] = {
    repository.findById(id)
  }

  def findByName(name: String): Future[Option[Team]] = {
    repository.findByName(name)
  }

  def addTeamMember(id: String, request: TeamMemberRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    repository.findById(id).flatMap {
      case Right(team) if !team.hasTeamMember(request.email) =>
        repository.update(team.addTeamMember(request.toTeamMember)) flatMap {
          case Right(_) =>
            emailConnector.sendTeamMemberAddedEmailToTeamMembers(Seq(request.toTeamMember), team).flatMap {
              _ => Future.successful(Right(()))
            }
          case Left(exception) => Future.successful(Left(exception))
        }
      case Right(team) =>
        val future = Future.successful(Left(raiseTeamMemberExistsException.forTeam(team)))
        future
      case Left(e) =>
        Future.successful(Left(e))
    }
  }

  def removeTeamMember(id: String, email: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] =
    repository.findById(id).flatMap {
      case Right(team) if !team.hasTeamMember(email) =>
        Future.successful(Left(raiseTeamMemberDoesNotExistException.forTeam(team)))
      case Right(team) if team.teamMembers.size < 2 =>
        Future.successful(Left(raiseLastTeamMemberException.forTeam(team)))
      case Right(team) =>
        repository.update(team.removeTeamMember(email)) flatMap {
          case Right(_) =>
            emailConnector.sendRemoveTeamMemberFromTeamEmail(email, team).flatMap {
              _ => Future.successful(Right(()))
            }
          case Left(exception) => Future.successful(Left(exception))
        }
      case Left(e) =>
        Future.successful(Left(e))
    }

  def renameTeam(id: String, request: RenameTeamRequest): Future[Either[ApplicationsException, Unit]] = {
    repository.findById(id).flatMap {
      case Right(team) =>
        repository.update(team.setName(request.name))
      case Left(e) =>
        Future.successful(Left(e))
    }
  }

  def addEgressesToTeam(id: String, assignEgressesRequest: AddEgressesRequest): Future[Either[ApplicationsException, Unit]] = {
    repository.findById(id).flatMap {
      case Right(team) =>
        repository.update(team.addEgresses(assignEgressesRequest.egresses))
      case Left(e) =>
        Future.successful(Left(e))
    }
  }


}
