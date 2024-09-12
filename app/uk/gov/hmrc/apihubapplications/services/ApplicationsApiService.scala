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
import uk.gov.hmrc.apihubapplications.models.application.{Api, Application, Secondary}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationNotFoundException, ApplicationsException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.models.requests.AddApiRequest
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.{ApplicationEnrichers, ScopeFixer}
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}

trait ApplicationsApiService {

  def addApi(applicationId: String, newApi: AddApiRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

  def removeApi(applicationId: String, apiId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

  def changeOwningTeam(applicationId: String, apiId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

  def removeOwningTeamFromApplication(applicationId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

}

@Singleton
class ApplicationsApiServiceImpl @Inject()(
  searchService: ApplicationsSearchService,
  accessRequestsService: AccessRequestsService,
  teamsService: TeamsService,
  repository: ApplicationsRepository,
  emailConnector: EmailConnector,
  scopeFixer: ScopeFixer,
  clock: Clock
)(implicit ec: ExecutionContext) extends ApplicationsApiService with Logging with ExceptionRaising {

  override def addApi(applicationId: String, addApiRequest: AddApiRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {

    searchService.findById(applicationId, enrich = true).flatMap {
      case Right(application) =>
        val updated = application.replaceApi(Api(addApiRequest.id, addApiRequest.title, addApiRequest.endpoints)).updated(clock)
        (for {
          fixedApplication <- EitherT(scopeFixer.fix(updated))
          _ <- EitherT(repository.update(fixedApplication))
        } yield ()).value
      case Left(e) => Future.successful(Left(e))
    }

  }

  override def removeApi(applicationId: String, apiId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    searchService.findById(applicationId, enrich = true).flatMap {
      case Right(application) if application.hasApi(apiId) => removeApi(application, apiId)
      case Right(_) => Future.successful(Left(raiseApiNotFoundException.forApplication(applicationId, apiId)))
      case Left(_: ApplicationNotFoundException) => Future.successful(Left(raiseApplicationNotFoundException.forId(applicationId)))
      case Left(e) => Future.successful(Left(e))
    }
  }

  private def removeApi(application: Application, apiId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    val updated = application
      .removeApi(apiId)
      .updated(clock)

    scopeFixer.fix(updated).flatMap {
      case Right(fixed) =>
        accessRequestsService.cancelAccessRequests(fixed.safeId, apiId).flatMap {
          case Right(_) => repository.update(fixed)
          case Left(e) => Future.successful(Left(e))
        }
      case Left(e) =>
        Future.successful(Left(e))
    }
  }

  override def changeOwningTeam(applicationId: String, teamId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    searchService.findById(applicationId, enrich = true, includeDeleted = true).flatMap {
      case Right(application) => changeOwningTeam(application, teamId)
      case Left(_: ApplicationNotFoundException) => Future.successful(Left(raiseApplicationNotFoundException.forId(applicationId)))
      case Left(e) => Future.successful(Left(e))
    }
  }


  private def changeOwningTeam(application: Application, teamId: String)(implicit hc: HeaderCarrier) = {
    val updated = application
      .setTeamId(teamId)
      .updated(clock)

    teamsService.findById(teamId).flatMap {
      case Right(team) =>
        for {
          updateResult <- repository.update(updated)
          _ <- sendNotificationOnOwningTeamChange(application, updated, team)
        } yield updateResult
      case _ => Future.successful(Left(raiseTeamNotFoundException.forId(teamId)))
    }
  }

  override def removeOwningTeamFromApplication(applicationId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    searchService.findById(applicationId, enrich = false, includeDeleted = true).flatMap {
      case Right(application) if application.teamId.isDefined => repository.update(application.removeTeam(clock))
      case Right(application) => Future.successful(Right(()))
      case Left(_: ApplicationNotFoundException) => Future.successful(Left(raiseApplicationNotFoundException.forId(applicationId)))
      case Left(e) => Future.successful(Left(e))
    }
  }

  private def sendNotificationOnOwningTeamChange(
                                                  application: Application,
                                                  updated: Application,
                                                  newTeam: Team,
                                                )(implicit hc: HeaderCarrier) =
    (application.teamId, updated.teamId) match {
      case (Some(oldTeamId), Some(newTeamId)) if oldTeamId != newTeamId =>
        (for {
          oldTeam <- EitherT(teamsService.findById(oldTeamId))
          _ <- EitherT(emailConnector.sendApplicationOwnershipChangedEmailToOldTeamMembers(oldTeam, newTeam, application))
          _ <- EitherT(emailConnector.sendApplicationOwnershipChangedEmailToNewTeamMembers(newTeam, application))
        } yield ()).value
      case _ => Future.successful(Right(()))
    }

}