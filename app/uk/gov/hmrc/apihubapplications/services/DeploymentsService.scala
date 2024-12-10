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
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, EmailConnector, IntegrationCatalogueConnector}
import uk.gov.hmrc.apihubapplications.models.api.{ApiDetail, ApiTeam}
import uk.gov.hmrc.apihubapplications.models.apim._
import uk.gov.hmrc.apihubapplications.models.application.{EnvironmentName, Primary, Secondary}
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, ApplicationsException, TeamNotFoundException}
import uk.gov.hmrc.apihubapplications.models.requests.DeploymentStatus
import uk.gov.hmrc.apihubapplications.models.requests.DeploymentStatus.{Deployed, NotDeployed, Unknown}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentsService @Inject()(
                                    apimConnector: APIMConnector,
                                    integrationCatalogueConnector: IntegrationCatalogueConnector,
                                    emailConnector: EmailConnector,
                                    teamsService: TeamsService,
                                    metricsService: MetricsService,
                                    hipEnvironments: HipEnvironments,
                                  )(implicit ec: ExecutionContext) extends Logging {
  private[services] val customUnknownDeploymentStatusMessage = "UNKNOWN_APIM_DEPLOYMENT_STATUS"

  def deployToSecondary(
                         request: DeploymentsRequest
                       )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, DeploymentsResponse]] = {
    for {
      deploymentsResponse <- apimConnector.deployToSecondary(request)
      linkApiToTeamResponse <- linkApiToTeam(deploymentsResponse, request.teamId)
    } yield linkApiToTeamResponse
  }

  def redeployToSecondary(
                           publisherRef: String,
                           request: RedeploymentRequest
                         )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, DeploymentsResponse]] = {
    apimConnector.redeployToSecondary(publisherRef, request)
  }

  private def linkApiToTeam(
                             deploymentsResponse: Either[ApimException, DeploymentsResponse],
                             teamId: String
                           )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, DeploymentsResponse]] = {
    deploymentsResponse match {
      case Right(response: SuccessfulDeploymentsResponse) =>
        integrationCatalogueConnector.linkApiToTeam(ApiTeam(response.id, teamId)).map {
          case Right(_) => Right(response)
          case Left(e) => Left(e)
        }
      case Right(response) => Future.successful(Right(response))
      case Left(e) => Future.successful(Left(e))
    }
  }

  def getDeployments(
                     publisherRef: String,
                   )(implicit hc: HeaderCarrier): Future[Seq[DeploymentStatus]] = {
    Future.sequence(hipEnvironments.environments.map(hipEnvironment =>
        apimConnector.getDeployment(publisherRef, hipEnvironment.environmentName)
          .map {
            case Right(Some(SuccessfulDeploymentResponse(_, version))) => Deployed(hipEnvironment.id, version)
            case Right(None) => NotDeployed(hipEnvironment.id)
            case Left(exception) =>
              logger.warn(customUnknownDeploymentStatusMessage, exception)
              metricsService.apimUnknownFailure()
              Unknown(hipEnvironment.id)
          }
      ))
  }

  def getDeploymentDetails(publisherRef: String)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentDetails]] = {
    apimConnector.getDeploymentDetails(publisherRef)
  }

  def promoteToProduction(
                           publisherRef: String
                         )(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentsResponse]] = {
    apimConnector.promoteToProduction(publisherRef)
  }

  def updateApiTeam(apiId: String, teamId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    integrationCatalogueConnector.findById(apiId) flatMap {
      case Right(apiDetail) =>
        resolveCurrentAndNewTeams(apiDetail, teamId) flatMap {
          case Right(owningTeams) =>
            integrationCatalogueConnector.updateApiTeam(apiId, teamId) flatMap {
              case Right(()) => for {
                  _ <- sendApiOwnershipChangedEmailToOldTeam(apiDetail, owningTeams.currentTeam, owningTeams.newTeam)
                  _ <- sendApiOwnershipChangedEmailToNewTeam(apiDetail, owningTeams.newTeam)
                } yield (()) match {
                  case _ => Right(())
                }
              case Left(e) => Future.successful(Left(e))
            }
          case Left(e) => Future.successful(Left(e))
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

  private case class OwningTeams(currentTeam: Option[Team], newTeam: Team)

  private def resolveCurrentAndNewTeams(apiDetail: ApiDetail, teamId: String): Future[Either[ApplicationsException, OwningTeams]] = {
    val eventualMaybeCurrentTeam = getTeam(apiDetail.teamId)
    val eventualMaybeNewTeam = getTeam(Some(teamId))
    for {
      maybeCurrentTeam <- eventualMaybeCurrentTeam
      maybeNewTeam <- eventualMaybeNewTeam
    } yield (maybeCurrentTeam, maybeNewTeam) match {
      case (_, None) => Left(TeamNotFoundException.forId(teamId))
      case (_, Some(newTeam)) => Right(OwningTeams(maybeCurrentTeam, newTeam))
    }
  }

  private def sendApiOwnershipChangedEmailToOldTeam(apiDetail: ApiDetail, maybeCurrentTeam: Option[Team], newTeam: Team)(implicit hc: HeaderCarrier) = {
    if (maybeCurrentTeam.isDefined) {
      emailConnector.sendApiOwnershipChangedEmailToOldTeamMembers(maybeCurrentTeam.get, newTeam, apiDetail) flatMap {
        case _ => Future.successful(())
      }
    } else {
      Future.successful(())
    }
  }

  def removeOwningTeamFromApi(apiId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    integrationCatalogueConnector.removeApiTeam(apiId) flatMap {
      case Right(()) => Future.successful(Right(()))
      case Left(e) => Future.successful(Left(e))
    }
  }

  private def sendApiOwnershipChangedEmailToNewTeam(apiDetail: ApiDetail, newTeam: Team)(implicit hc: HeaderCarrier) =
    emailConnector.sendApiOwnershipChangedEmailToNewTeamMembers(newTeam, apiDetail) flatMap (_ => Future.successful(()))

  private def getTeam(maybeTeamId: Option[String]) = {
    maybeTeamId match {
      case Some(teamId) => teamsService.findById(teamId) map {
        case Right(team) => Some(team)
        case Left(_) => None
      }
      case None => Future.successful(None)
    }

  }
}
