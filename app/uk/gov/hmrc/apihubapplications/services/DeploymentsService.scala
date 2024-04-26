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
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, IntegrationCatalogueConnector}
import uk.gov.hmrc.apihubapplications.models.api.ApiTeam
import uk.gov.hmrc.apihubapplications.models.apim.{DeploymentResponse, DeploymentsRequest, DeploymentsResponse, SuccessfulDeploymentsResponse}
import uk.gov.hmrc.apihubapplications.models.application.EnvironmentName
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, ApplicationsException}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentsService @Inject()(
  apimConnector: APIMConnector,
  integrationCatalogueConnector: IntegrationCatalogueConnector
)(implicit ec: ExecutionContext) {

  def deployToSecondary(
    request: DeploymentsRequest
  )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, DeploymentsResponse]] = {
    for {
      deploymentsResponse <- apimConnector.deployToSecondary(request)
      linkApiToTeamResponse <- linkApiToTeam(deploymentsResponse, request.teamId)
    } yield linkApiToTeamResponse
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

}
