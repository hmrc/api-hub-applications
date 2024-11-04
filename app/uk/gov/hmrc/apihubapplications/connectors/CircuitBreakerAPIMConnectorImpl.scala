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

package uk.gov.hmrc.apihubapplications.connectors

import com.google.inject.Inject
import org.apache.pekko.actor.ActorSystem
import play.api.Logging
import uk.gov.hmrc.apihubapplications.circuitbreakers.CircuitBreakers
import uk.gov.hmrc.apihubapplications.models.api.EgressGateway
import uk.gov.hmrc.apihubapplications.models.apim.{ApiDeployment, DeploymentDetails, DeploymentResponse, DeploymentsRequest, DeploymentsResponse, RedeploymentRequest, ValidateResponse}
import uk.gov.hmrc.apihubapplications.models.application.{EnvironmentName, Primary, Secondary}
import uk.gov.hmrc.apihubapplications.models.exception.ApimException
import uk.gov.hmrc.apihubapplications.models.exception.ApimException.*
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.apihubapplications.services.MetricsService

import scala.concurrent.{ExecutionContext, Future}

class CircuitBreakerAPIMConnectorImpl @Inject()(
                                                 val servicesConfig: ServicesConfig,
                                                 httpClient: HttpClientV2,
                                                 circuitBreakers: CircuitBreakers,
)(implicit ec: ExecutionContext) extends APIMConnectorImpl(servicesConfig, httpClient) {

  import circuitBreakers.{withCircuitBreaker, given}

  override def validateInPrimary(oas: String)(implicit hc: HeaderCarrier): Future[Either[ApimException, ValidateResponse]] =
    withCircuitBreaker(Primary, super.validateInPrimary(oas))

  override def deployToSecondary(request: DeploymentsRequest)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentsResponse]] =
    withCircuitBreaker(Secondary, super.deployToSecondary(request))

  override def redeployToSecondary(publisherReference: String, request: RedeploymentRequest)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentsResponse]] =
    withCircuitBreaker(Secondary, super.redeployToSecondary(publisherReference, request))

  override def getDeployment(publisherReference: String, environment: EnvironmentName)(implicit hc: HeaderCarrier): Future[Either[ApimException, Option[DeploymentResponse]]] =
    withCircuitBreaker(environment, super.getDeployment(publisherReference, environment))

  override def getDeploymentDetails(publisherReference: String)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentDetails]] =
    withCircuitBreaker(Secondary, super.getDeploymentDetails(publisherReference))

  override def promoteToProduction(publisherReference: String)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentsResponse]] =
    withCircuitBreaker(Primary, super.promoteToProduction(publisherReference))

  override def getDeployments(environment: EnvironmentName)(implicit hc: HeaderCarrier): Future[Either[ApimException, Seq[ApiDeployment]]] =
    withCircuitBreaker(environment, super.getDeployments(environment))

  override def listEgressGateways()(implicit hc: HeaderCarrier): Future[Either[ApimException, Seq[EgressGateway]]] =
    withCircuitBreaker(Secondary, super.listEgressGateways())

}
