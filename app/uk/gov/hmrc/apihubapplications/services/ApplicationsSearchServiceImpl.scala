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
import uk.gov.hmrc.apihubapplications.models.application.Application
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses._
import uk.gov.hmrc.apihubapplications.models.exception.ApplicationsException
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.ApplicationEnrichers
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.useFirstApplicationsException
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

trait ApplicationsSearchService {

  def findAll(teamMemberEmail: Option[String], includeDeleted: Boolean): Future[Either[ApplicationsException, Seq[Application]]]

  def findAllUsingApi(apiId: String, includeDeleted: Boolean): Future[Either[ApplicationsException, Seq[Application]]]

  def findById(id: String, enrich: Boolean)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]]

}

@Singleton
class ApplicationsSearchServiceImpl @Inject()(
  repository: ApplicationsRepository,
  idmsConnector: IdmsConnector,
  teamsService: TeamsService
)(implicit ec: ExecutionContext) extends ApplicationsSearchService {

  override def findAll(teamMemberEmail: Option[String], includeDeleted: Boolean): Future[Either[ApplicationsException, Seq[Application]]] = {
    addTeams(
      repository.findAll(teamMemberEmail, includeDeleted).flatMap {
        applications =>
          teamMemberEmail match {
            case Some(email) => teamsService.findAll(Some(email)).flatMap {
              case teams if teams.nonEmpty => repository.findForTeams(teams, includeDeleted).map(moreApplications => applications ++ moreApplications)
              case _ => Future.successful(applications)
            }
            case None => Future.successful(applications)
          }
      }
    )
  }

  override def findAllUsingApi(apiId: String, includeDeleted: Boolean): Future[Either[ApplicationsException, Seq[Application]]] = {
    addTeams(repository.findAllUsingApi(apiId, includeDeleted))
  }

  override def findById(id: String, enrich: Boolean)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    repository.findById(id).flatMap {
      case Right(application) =>
        (if (enrich) {
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
        }).flatMap {
          case Right(application) => addTeam(application)
          case Left(e) => Future.successful(Left(e))
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

  private def addTeam(application: Application): Future[Either[ApplicationsException, Application]] = {
    application.teamId match {
      case Some(teamId) =>
        teamsService.findById(teamId).map(_.map(
          team =>
            application.setTeamMembers(team.teamMembers)
        ))
      case None =>
        Future.successful(Right(application))
    }
  }

  private def addTeams(applications: Future[Seq[Application]]): Future[Either[ApplicationsException, Seq[Application]]] = {
    applications
      .flatMap(
        applications =>
          Future.sequence(
            applications.map(addTeam)
          ).map(useFirstApplicationsException)
      )
  }

}
