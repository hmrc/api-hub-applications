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
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, EmailConnector, IntegrationCatalogueConnector}
import uk.gov.hmrc.apihubapplications.models.api.{ApiDetail, ApiTeam}
import uk.gov.hmrc.apihubapplications.models.apim._
import uk.gov.hmrc.apihubapplications.models.application.EnvironmentName
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, ApplicationsException}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentsService @Inject()(
                                    apimConnector: APIMConnector,
                                    integrationCatalogueConnector: IntegrationCatalogueConnector,
                                    emailConnector: EmailConnector,
                                    teamsService: TeamsService
                                  )(implicit ec: ExecutionContext) {

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

  def getDeployment(
                     publisherRef: String,
                     environmentName: EnvironmentName
                   )(implicit hc: HeaderCarrier): Future[Either[ApimException, Option[DeploymentResponse]]] = {
    apimConnector.getDeployment(publisherRef, environmentName)
  }

  def promoteToProduction(
                           publisherRef: String
                         )(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentsResponse]] = {
    apimConnector.promoteToProduction(publisherRef)
  }

  def updateApiTeam(apiId: String, teamId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, ApiDetail]] = {
    integrationCatalogueConnector.findById(apiId) flatMap {
      case Right(apiDetail) =>
        for {
          maybeCurrentTeam <- getCurrentTeamForApi(apiDetail)
          updatedApiDetail <- integrationCatalogueConnector.updateApiTeam(apiId, teamId)
          maybeNewTeam <- getTeam(teamId)
        } yield (maybeCurrentTeam, maybeNewTeam, updatedApiDetail) match {
          case (_, _, Right(updatedApiDetail)) =>
            sendApiOwnershipChangedEmailToOldTeam(apiDetail, maybeCurrentTeam)
            sendApiOwnershipChangedEmailToNewTeam(apiDetail, maybeNewTeam)
            Right(updatedApiDetail)
          case (_, _, Left(e)) => Left(e)
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

  private def sendApiOwnershipChangedEmailToOldTeam(apiDetail: ApiDetail, maybeCurrentTeam: Option[Team])(implicit hc: HeaderCarrier) = {
    if (maybeCurrentTeam.isDefined) {
      emailConnector.sendApiOwnershipChangedEmailToOldTeamMembers(maybeCurrentTeam.get, apiDetail) flatMap {
        case Right(()) => Future.successful(())
        case Left(e) => throw e
      }
    } else {
      Future.successful(())
    }
  }

  private def sendApiOwnershipChangedEmailToNewTeam(apiDetail: ApiDetail, maybeNewTeam: Option[Team])(implicit hc: HeaderCarrier) = {
    if (maybeNewTeam.isDefined) {
      emailConnector.sendApiOwnershipChangedEmailToNewTeamMembers(maybeNewTeam.get, apiDetail) flatMap {
        case Right(()) => Future.successful(())
        case Left(e) => throw e
      }
    } else {
      Future.successful(())
    }
  }

  private def getTeam(teamId: String)(implicit hc: HeaderCarrier) = {
    teamsService.findById(teamId) flatMap {
      case Right(team) => Future.successful(Some(team))
      case _ => Future.successful(None)
    }
  }

  private def getCurrentTeamForApi(apiDetail: ApiDetail)(implicit hc: HeaderCarrier) = {
    if (apiDetail.teamId.isDefined) {
      getTeam(apiDetail.teamId.get)
    }
    else {
      Future.successful(None)
    }
  }
}
