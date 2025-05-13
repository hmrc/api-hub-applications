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

import cats.data.EitherT
import com.google.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.json.JsValue
import play.api.mvc.Request
import uk.gov.hmrc.apihubapplications.connectors.EmailConnector
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, EgressNotFoundException, ExceptionRaising}
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
  emailConnector: EmailConnector,
  eventService: TeamsEventService
)(implicit ec: ExecutionContext) extends Logging with ExceptionRaising {

  def create(newTeam: NewTeam, requestingUser: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Team]] = {
    repository.insert(newTeam.toTeam(clock)).flatMap {
      case Right(team) => for {
          _ <- emailConnector.sendTeamMemberAddedEmailToTeamMembers(team.teamMembers, team)
          _ <- eventService.create(team, requestingUser)
        } yield {
          Right(team)
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

  def addTeamMember(id: String, request: TeamMemberRequest, requestingUser: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    repository.findById(id).flatMap {
      case Right(team) if !team.hasTeamMember(request.email) =>
        (for {
          result <- EitherT(repository.update(team.addTeamMember(request.toTeamMember)))
          _ <- EitherT.right(emailConnector.sendTeamMemberAddedEmailToTeamMembers(Seq(request.toTeamMember), team))
          _ <- EitherT.right(eventService.addMember(team, requestingUser, request.email))
        } yield result).value
      case Right(team) =>
        val future = Future.successful(Left(raiseTeamMemberExistsException.forTeam(team)))
        future
      case Left(e) =>
        Future.successful(Left(e))
    }
  }

  def removeTeamMember(id: String, email: String, requestingUser: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] =
    repository.findById(id).flatMap {
      case Right(team) if !team.hasTeamMember(email) =>
        Future.successful(Left(raiseTeamMemberDoesNotExistException.forTeam(team)))
      case Right(team) if team.teamMembers.size < 2 =>
        Future.successful(Left(raiseLastTeamMemberException.forTeam(team)))
      case Right(team) =>
        (for {
          result <- EitherT(repository.update(team.removeTeamMember(email)))
          _ <- EitherT.right(emailConnector.sendRemoveTeamMemberFromTeamEmail(email, team))
          _ <- EitherT.right(eventService.removeMember(team, requestingUser, email))
        } yield result).value
      case Left(e) =>
        Future.successful(Left(e))
    }

  def renameTeam(id: String, request: RenameTeamRequest, requestingUser: String): Future[Either[ApplicationsException, Unit]] = {
    repository.findById(id).flatMap {
      case Right(team) =>
        val renamedTeam = team.setName(request.name)
        (for {
          result <- EitherT(repository.update(renamedTeam))
          _ <- EitherT.right(eventService.rename(renamedTeam, requestingUser, team.name))
        } yield result).value
      case Left(e) =>
        Future.successful(Left(e))
    }
  }

  def addEgressesToTeam(id: String, assignEgressesRequest: AddEgressesRequest, requestingUser: String): Future[Either[ApplicationsException, Unit]] = {
    repository.findById(id).flatMap {
      case Right(team) =>
        (for {
          result <- EitherT(repository.update(team.addEgresses(assignEgressesRequest.egresses)))
          _ <- EitherT.right(eventService.addEgresses(team, requestingUser, assignEgressesRequest.egresses))
        } yield result).value
      case Left(e) =>
        Future.successful(Left(e))
    }
  }

  def removeEgressFromTeam(id: String, egressId: String, requestingUser: String): Future[Either[ApplicationsException, Unit]] = {
    repository.findById(id).flatMap {
      case Right(team) =>
        if (team.hasEgress(egressId)) {
          (for {
            result <- EitherT(repository.update(team.removeEgress(egressId)))
            _ <- EitherT.right(eventService.removeEgress(team, requestingUser, egressId))
          } yield result).value
        } else {
          Future.successful(Left(raiseEgressNotFoundException.forTeamAndEgress(team, egressId)))
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

}
