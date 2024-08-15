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
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.{Application, Issues}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses._
import uk.gov.hmrc.apihubapplications.models.exception.ApplicationsException
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.ApplicationEnrichers
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

trait ApplicationsSearchService {

  def findAll(includeDeleted: Boolean): Future[Seq[Application]] = {
    findAll(None, includeDeleted)
  }

  def findAll(teamMemberEmail: Option[String], includeDeleted: Boolean): Future[Seq[Application]]

  def findAllUsingApi(apiId: String, includeDeleted: Boolean): Future[Seq[Application]]

  def findById(id: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    findById(id, false)
  }

  def findById(id: String, enrich: Boolean)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    findById(id, enrich, false)
  }

  def findById(id: String, enrich: Boolean, includeDeleted: Boolean)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]]

}

@Singleton
class ApplicationsSearchServiceImpl @Inject()(
  teamsService: TeamsService,
  repository: ApplicationsRepository,
  idmsConnector: IdmsConnector
)(implicit ec: ExecutionContext) extends ApplicationsSearchService {

  override def findAll(teamMemberEmail: Option[String], includeDeleted: Boolean): Future[Seq[Application]] = {
    val applications = for {
      teams <- fetchUserTeams(teamMemberEmail)
      applications <- repository.findAll(teamMemberEmail, teams, includeDeleted)
    } yield applications

    applications.flatMap(addTeams)
  }

  private def fetchUserTeams(teamMemberEmail: Option[String]): Future[Seq[Team]] = {
    teamMemberEmail match {
      case Some(_) => teamsService.findAll(teamMemberEmail)
      case _ => Future.successful(Seq.empty)
    }
  }

  override def findAllUsingApi(apiId: String, includeDeleted: Boolean): Future[Seq[Application]] = {
    repository
      .findAllUsingApi(apiId, includeDeleted)
      .flatMap(addTeams)
  }

  override def findById(id: String, enrich: Boolean, includeDeleted: Boolean)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    repository.findById(id, includeDeleted).flatMap {
      case Right(application) =>
        (if (enrich && application.deleted.isEmpty) {
          ApplicationEnrichers.process(
            application,
            Seq(
              ApplicationEnrichers.secondaryCredentialApplicationEnricher(application, idmsConnector),
              ApplicationEnrichers.secondaryScopeApplicationEnricher(application, idmsConnector),
              ApplicationEnrichers.primaryScopeApplicationEnricher(application, idmsConnector)
            )
          )
        } else {
          Future.successful(Right(application))
        }).flatMap{
          case Right(application) => addTeam(application).map(Right(_))
          case Left(e) => Future.successful(Left(e))
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

  private[services] def addTeam(application: Application): Future[Application] = {
    application.teamId match {
      case Some(teamId) =>
        teamsService.findById(teamId).map {
          case Right(team) => application.setTeamMembers(team.teamMembers).setTeamName(team.name)
          case Left(e) => application.addIssue(Issues.teamNotFound(teamId, e))
        }
      case None =>
        Future.successful(application)
    }
  }

  private def addTeams(applications: Seq[Application]): Future[Seq[Application]] = {
    Future.sequence(
      applications.map(addTeam)
    )
  }

}
