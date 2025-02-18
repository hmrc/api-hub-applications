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

import uk.gov.hmrc.apihubapplications.config.HipEnvironment
import uk.gov.hmrc.apihubapplications.models.application.Application
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, ClientScope, Secret}

import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future
trait IdmsConnector {

  def createClient(hipEnvironment: HipEnvironment, client: Client)(implicit hc: HeaderCarrier): Future[Either[IdmsException, ClientResponse]]

  def fetchClient(hipEnvironment: HipEnvironment, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, ClientResponse]]

  def deleteClient(hipEnvironment: HipEnvironment, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Unit]]

  def newSecret(hipEnvironment: HipEnvironment, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Secret]]

  def addClientScope(hipEnvironment: HipEnvironment, clientId: String, scopeId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Unit]]

  def deleteClientScope(hipEnvironment: HipEnvironment, clientId: String, scopeId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Unit]]

  def fetchClientScopes(hipEnvironment: HipEnvironment, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Seq[ClientScope]]]

}
