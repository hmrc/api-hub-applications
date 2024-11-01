/*
 * Copyright 2023 HM Revenue & Customs
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

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.Json
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import uk.gov.hmrc.apihubapplications.circuitbreakers.CircuitBreakers
import uk.gov.hmrc.apihubapplications.models.application.{Application, EnvironmentName, Primary, Secondary}
import uk.gov.hmrc.apihubapplications.models.exception.{ExceptionRaising, IdmsException}
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, ClientScope, Secret}
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.{ignoreClientNotFound, useFirstException}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CircuitBreakerIdmsConnectorImpl @Inject()(
  servicesConfig: ServicesConfig,
  httpClient: HttpClientV2,
  circuitBreakers: CircuitBreakers,
)(implicit ec: ExecutionContext) extends IdmsConnectorImpl(servicesConfig, httpClient) {

  import circuitBreakers.{withCircuitBreaker, given}

  override def createClient(environmentName: EnvironmentName, client: Client)(implicit hc: HeaderCarrier): Future[Either[IdmsException, ClientResponse]] =
    withCircuitBreaker(environmentName, super.createClient(environmentName, client))

  override def fetchClient(environmentName: EnvironmentName, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, ClientResponse]] =
    withCircuitBreaker(environmentName, super.fetchClient(environmentName, clientId))

  override def deleteClient(environmentName: EnvironmentName, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Unit]] =
    withCircuitBreaker(environmentName, super.deleteClient(environmentName, clientId))

  override def newSecret(environmentName: EnvironmentName, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Secret]] =
    withCircuitBreaker(environmentName, super.newSecret(environmentName, clientId))

  override def addClientScope(environmentName: EnvironmentName, clientId: String, scopeId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Unit]] =
    withCircuitBreaker(environmentName, super.addClientScope(environmentName, clientId, scopeId))

  override def deleteClientScope(
    environmentName: EnvironmentName,
    clientId: String,
    scopeId: String
  )(implicit hc: HeaderCarrier): Future[Either[IdmsException, Unit]] =
    withCircuitBreaker(environmentName, super.deleteClientScope(environmentName, clientId, scopeId))

  override def fetchClientScopes(
    environmentName: EnvironmentName,
    clientId: String
  )(implicit hc: HeaderCarrier): Future[Either[IdmsException, Seq[ClientScope]]] =
    withCircuitBreaker(environmentName, super.fetchClientScopes(environmentName, clientId))

}
