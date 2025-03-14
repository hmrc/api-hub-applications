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

import uk.gov.hmrc.apihubapplications.config.HipEnvironment
import uk.gov.hmrc.apihubapplications.models.api.EgressGateway
import uk.gov.hmrc.apihubapplications.models.apim.*
import uk.gov.hmrc.apihubapplications.models.exception.ApimException
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future

trait APIMConnector {

  def validateInPrimary(oas: String)(implicit hc: HeaderCarrier): Future[Either[ApimException, ValidateResponse]]

  def deployToSecondary(request: DeploymentsRequest)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentsResponse]]

  def redeployToSecondary(publisherReference: String, request: RedeploymentRequest)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentsResponse]]

  def getDeployment(publisherReference: String, hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApimException, Option[DeploymentResponse]]]

  def getDeploymentDetails(publisherReference: String, hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentDetails]]

  def promoteAPI(publisherReference: String, environmentFrom: HipEnvironment, environmentTo: HipEnvironment, egress: String)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentsResponse]]

  def getDeployments(hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApimException, Seq[ApiDeployment]]]

  def listEgressGateways(hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApimException, Seq[EgressGateway]]]

  def getOpenApiSpecification(publisherReference: String, hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApimException, String]]

  def getDeploymentStatus(publisherReference: String, mergeRequestIid: String, version: String, hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApimException, StatusResponse]]

}
