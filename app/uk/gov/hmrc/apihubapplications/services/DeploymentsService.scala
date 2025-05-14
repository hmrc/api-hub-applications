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
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, AutopublishConnector, EmailConnector, IntegrationCatalogueConnector}
import uk.gov.hmrc.apihubapplications.models.api.{ApiDetail, ApiTeam}
import uk.gov.hmrc.apihubapplications.models.apim.*
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, ApplicationsException, TeamNotFoundException}
import uk.gov.hmrc.apihubapplications.models.requests.DeploymentStatus
import uk.gov.hmrc.apihubapplications.models.requests.DeploymentStatus.{Deployed, NotDeployed, Unknown}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.utility.OasHelpers
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentsService @Inject()(
                                    apimConnector: APIMConnector,
                                    integrationCatalogueConnector: IntegrationCatalogueConnector,
                                    emailConnector: EmailConnector,
                                    teamsService: TeamsService,
                                    metricsService: MetricsService,
                                    hipEnvironments: HipEnvironments,
                                    autopublishConnector: AutopublishConnector,
                                    eventService: ApiEventService,
                                    clock: Clock
                                  )(implicit ec: ExecutionContext) extends Logging with OasHelpers {
  private[services] val customUnknownDeploymentStatusMessage = "UNKNOWN_APIM_DEPLOYMENT_STATUS"

  def createApi(
                         request: DeploymentsRequest
                       )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, DeploymentsResponse]] = {
    for {
      deploymentsResponse <- apimConnector.createApi(request, hipEnvironments.deployTo)
      linkApiToTeamResponse <- linkApiToTeam(deploymentsResponse, request.teamId)
    } yield linkApiToTeamResponse
  }

  def updateApi(
    publisherRef: String,
    request: RedeploymentRequest,
    userEmail: String
  )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, DeploymentsResponse]] = {
    (for {
      apiDetail <- EitherT(integrationCatalogueConnector.findByPublisherRef(publisherRef))
      response <- EitherT(apimConnector.updateApi(publisherRef, request, hipEnvironments.deployTo))
      _ <- EitherT.right(logUpdateApiEvent(apiDetail, request, response, userEmail)
      )
    } yield response).value
  }

  private def logUpdateApiEvent(
    apiDetail: ApiDetail, 
    request: RedeploymentRequest, 
    response: DeploymentsResponse, 
    userEmail: String
  ): Future[Unit] = {
    response match {
      case success: SuccessfulDeploymentsResponse =>
        eventService.update(
          apiId = apiDetail.id,
          hipEnvironment = hipEnvironments.deployTo,
          oasVersion = oasVersion(request.oas).getOrElse("none"),
          request = request,
          response = success,
          userEmail = userEmail,
          timestamp = LocalDateTime.now(clock)
        )
      case _ => Future.successful(())
    }
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
    Future.sequence(
      hipEnvironments.environments.map(
        hipEnvironment =>
          getDeployment(hipEnvironment, publisherRef)
      )
    )
  }

  def getDeployment(
    hipEnvironment: HipEnvironment,
    publisherRef: String
  )(implicit hc: HeaderCarrier): Future[DeploymentStatus] = {
    apimConnector.getDeployment(publisherRef, hipEnvironment).map {
      case Right(Some(SuccessfulDeploymentResponse(_, _, _, version, _))) => Deployed(hipEnvironment.id, version)
      case Right(None) => NotDeployed(hipEnvironment.id)
      case Left(exception) =>
        logger.warn(customUnknownDeploymentStatusMessage, exception)
        metricsService.apimUnknownFailure()
        Unknown(hipEnvironment.id)
    }
  }

  def getDeploymentDetails(publisherRef: String, hipEnvironment: HipEnvironment = hipEnvironments.deployTo)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentDetails]] = {
    apimConnector.getDeploymentDetails(publisherRef, hipEnvironment)
  }

  def promoteAPI(
    publisherRef: String,
    environmentFrom: HipEnvironment,
    environmentTo: HipEnvironment,
    egress: String,
    userEmail: String
  )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, DeploymentsResponse]] = {
    (for {
      apiDetail <- EitherT(integrationCatalogueConnector.findByPublisherRef(publisherRef))
      deployment <- EitherT.right(getDeployment(environmentFrom, publisherRef))
      response <- EitherT(apimConnector.promoteAPI(publisherRef, environmentFrom, environmentTo, egress))
      _ <- EitherT.right(
        logPromoteApiEvent(
          apiDetail = apiDetail,
          environmentFrom = environmentFrom,
          environmentTo = environmentTo,
          egress = egress,
          deployment = deployment,
          response = response,
          userEmail = userEmail
        )
      )
    } yield response).value
  }

  private def logPromoteApiEvent(
    apiDetail: ApiDetail,
    environmentFrom: HipEnvironment,
    environmentTo: HipEnvironment,
    egress: String,
    deployment: DeploymentStatus,
    response: DeploymentsResponse,
    userEmail: String
  ): Future[Unit] = {
    response match {
      case success: SuccessfulDeploymentsResponse =>
        eventService.promote(
          apiId = apiDetail.id,
          fromEnvironment = environmentFrom,
          toEnvironment = environmentTo,
          oasVersion = deployment match {
            case Deployed(_, oasVersion) => oasVersion
            case NotDeployed(_) => "not deployed"
            case Unknown(_) => "unknown"
          },
          egress = egress,
          response = success,
          userEmail = userEmail,
          timestamp = LocalDateTime.now(clock)
        )
      case _ => Future.successful(())
    }
  }

  def updateApiTeam(apiId: String, teamId: String, userEmail: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    (for {
      apiDetail <- EitherT(integrationCatalogueConnector.findById(apiId))
      owningTeams <- EitherT(resolveCurrentAndNewTeams(apiDetail, teamId))
      _ <- EitherT(integrationCatalogueConnector.updateApiTeam(apiId, teamId))
      _ <- EitherT.right(
        eventService.changeTeam(
          apiId = apiDetail.id,
          newTeam = owningTeams.newTeam,
          oldTeam = owningTeams.currentTeam,
          userEmail = userEmail,
          timestamp = LocalDateTime.now(clock)
        )
      )
      _ <- EitherT.right(sendApiOwnershipChangedEmailToOldTeam(apiDetail, owningTeams.currentTeam, owningTeams.newTeam))
      _ <- EitherT.right(sendApiOwnershipChangedEmailToNewTeam(apiDetail, owningTeams.newTeam))

    } yield ()).value
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
      emailConnector.sendApiOwnershipChangedEmailToOldTeamMembers(maybeCurrentTeam.get, newTeam, apiDetail) flatMap (_ => Future.successful(()))
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

  def forcePublish(publisherReference: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    autopublishConnector.forcePublish(publisherReference)
  }

}
