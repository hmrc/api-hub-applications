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
import uk.gov.hmrc.apihubapplications.connectors.EmailConnector
import uk.gov.hmrc.apihubapplications.models.application.{Api, Application}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationNotFoundException, ApplicationsException, ExceptionRaising, TeamNotFoundException}
import uk.gov.hmrc.apihubapplications.models.requests.AddApiRequest
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.ScopeFixer
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}

trait ApplicationsApiService {

  def addApi(applicationId: String, newApi: AddApiRequest, userEmail: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

  def removeApi(applicationId: String, apiId: String, userEmail: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

  def changeOwningTeam(applicationId: String, apiId: String, userEmail: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

  def removeOwningTeamFromApplication(applicationId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

  def fixScopes(applicationId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

}

@Singleton
class ApplicationsApiServiceImpl @Inject()(
  searchService: ApplicationsSearchService,
  accessRequestsService: AccessRequestsService,
  teamsService: TeamsService,
  repository: ApplicationsRepository,
  emailConnector: EmailConnector,
  scopeFixer: ScopeFixer,
  clock: Clock,
  eventService: ApplicationsEventService
)(implicit ec: ExecutionContext) extends ApplicationsApiService with Logging with ExceptionRaising {

  override def addApi(applicationId: String, addApiRequest: AddApiRequest, userEmail: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {

    searchService.findById(applicationId).flatMap {
      case Right(application) =>
        val api = Api(addApiRequest.id, addApiRequest.title, addApiRequest.endpoints)
        val timestamp = LocalDateTime.now(clock)

        val updated = application
          .replaceApi(api)
          .updated(timestamp)

        (for {
          _ <- EitherT(repository.update(updated))
          _ <- EitherT.right(eventService.addApi(updated, api, userEmail, timestamp))
          accessRequests <- EitherT.right(accessRequestsService.getAccessRequests(Some(applicationId), None))
          _ <- EitherT(scopeFixer.fix(updated, accessRequests))
        } yield ()).value
      case Left(e) => Future.successful(Left(e))
    }

  }

  override def removeApi(applicationId: String, apiId: String, userEmail: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    searchService.findById(applicationId).flatMap {
      case Right(application) =>
        application.api(apiId) match {
          case Some(api) => removeApi(application, api, userEmail)
          case _ => Future.successful(Left(raiseApiNotFoundException.forApplication(applicationId, apiId)))
        }
      case Left(_: ApplicationNotFoundException) => Future.successful(Left(raiseApplicationNotFoundException.forId(applicationId)))
      case Left(e) => Future.successful(Left(e))
    }
  }

  private def removeApi(application: Application, api: Api, userEmail: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    val timestamp = LocalDateTime.now(clock)

    val updated = application
      .removeApi(api.id)
      .updated(timestamp)

    (for {
      _ <- EitherT(accessRequestsService.cancelAccessRequests(application.safeId, api.id))
      _ <- EitherT(repository.update(updated))
      _ <- EitherT.right(eventService.removeApi(application, api, userEmail, timestamp))
      accessRequests <- EitherT.right(accessRequestsService.getAccessRequests(Some(application.safeId), None))
      fixed <- EitherT(scopeFixer.fix(updated, accessRequests))
    } yield ()).value
  }

  override def changeOwningTeam(applicationId: String, teamId: String, userEmail: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    searchService.findById(applicationId, includeDeleted = true).flatMap {
      case Right(application) => changeOwningTeam(application, teamId, userEmail)
      case Left(_: ApplicationNotFoundException) => Future.successful(Left(raiseApplicationNotFoundException.forId(applicationId)))
      case Left(e) => Future.successful(Left(e))
    }
  }

  private def changeOwningTeam(application: Application, teamId: String, userEmail: String)(implicit hc: HeaderCarrier) = {
    val timestamp = LocalDateTime.now(clock)

    val oldTeamId = application.teamId

    (for {
      newTeam <- EitherT(teamsService.findById(teamId))
      oldTeam <- EitherT(fetchOldTeam(oldTeamId))
      updated = application
        .setTeamId(teamId)
        .updated(timestamp)
      updateResult <- EitherT(repository.update(updated))
      _ <- EitherT.right(eventService.changeTeam(updated, newTeam, oldTeam, userEmail, timestamp))
      _ <- EitherT(sendNotificationOnOwningTeamChange(updated, oldTeam, newTeam))
    } yield updateResult).value
  }

  private def fetchOldTeam(teamId: Option[String]): Future[Either[ApplicationsException, Option[Team]]] = {
    teamId match {
      case Some(teamId) =>
        teamsService.findById(teamId).map {
          case Right(team) => Right(Some(team))
          case Left(_: TeamNotFoundException) => Right(None)
          case Left(e) => Left(e)
        }
      case _ => Future.successful(Right(None))
    }
  }

  override def removeOwningTeamFromApplication(applicationId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    searchService.findById(applicationId, includeDeleted = true).flatMap {
      case Right(application) if application.teamId.isDefined => repository.update(application.removeTeam(clock))
      case Right(application) => Future.successful(Right(()))
      case Left(_: ApplicationNotFoundException) => Future.successful(Left(raiseApplicationNotFoundException.forId(applicationId)))
      case Left(e) => Future.successful(Left(e))
    }
  }

  private def sendNotificationOnOwningTeamChange(
                                                  application: Application,
                                                  oldTeam: Option[Team],
                                                  newTeam: Team,
                                                )(implicit hc: HeaderCarrier) = {
    oldTeam match {
      case Some(oldTeam) =>
        (for {
          _ <- EitherT(emailConnector.sendApplicationOwnershipChangedEmailToOldTeamMembers(oldTeam, newTeam, application))
          _ <- EitherT(emailConnector.sendApplicationOwnershipChangedEmailToNewTeamMembers(newTeam, application))
        } yield ()).value
      case _ => Future.successful(Right(()))
    }
  }

  override def fixScopes(applicationId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    (for {
      application <- EitherT(searchService.findById(applicationId))
      accessRequests <- EitherT.right(accessRequestsService.getAccessRequests(Some(applicationId), None))
      _ <- EitherT(scopeFixer.fix(application, accessRequests))
    } yield ()).value
  }

}
