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

package uk.gov.hmrc.apihubapplications.testhelpers

import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.EnvironmentName
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, IdmsException, Secret}
import uk.gov.hmrc.apihubapplications.testhelpers.FakeIdmsConnector.{FakeClientResponse, fakeSecret}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future

class FakeIdmsConnector extends IdmsConnector {

  override def createClient(environmentName: EnvironmentName, client: Client)(implicit hc: HeaderCarrier): Future[Either[IdmsException, ClientResponse]] = {
    Future.successful(Right(FakeClientResponse))
  }

  override def fetchClient(environmentName: EnvironmentName, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, ClientResponse]] = {
    Future.successful(Right(ClientResponse(clientId, fakeSecret)))
  }

  override def newSecret(environmentName: EnvironmentName, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Secret]] = {
    Future.successful(Right(Secret(fakeSecret)))
  }

  override def addClientScope(environmentName: EnvironmentName, clientId: String, scopeId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Unit]] = {
    Future.successful(Right(()))
  }
}

object FakeIdmsConnector {

  val fakeClientId: String = "fake-client-id"
  val fakeSecret: String = "fake-secret-1234"
  val FakeClientResponse: ClientResponse = ClientResponse(fakeClientId, fakeSecret)

}
