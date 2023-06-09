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

import org.mockito.ArgumentMatchers.{any, eq => mockitoEq}
import org.mockito.MockitoSugar
import org.mockito.stubbing.ScalaOngoingStubbing
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.{Application, EnvironmentName}
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, IdmsException}
import uk.gov.hmrc.apihubapplications.models.idms.{ClientResponse, ClientScope}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository

import scala.concurrent.Future

object ApiHubMockImplicits extends MockitoSugar {

  implicit class ApplicationsRepositoryMockOps(mockApplicationsRepository: ApplicationsRepository) {

    def mockFilter(
      teamMemberEmail: String,
      value: Seq[Application]
    ): ScalaOngoingStubbing[Future[Seq[Application]]] = {
      when(mockApplicationsRepository.filter(mockitoEq(teamMemberEmail)))
        .thenReturn(Future.successful(value))
    }

    def mockFindAll(value: Seq[Application]): ScalaOngoingStubbing[Future[Seq[Application]]] = {
      when(mockApplicationsRepository.findAll())
        .thenReturn(Future.successful(value))
    }

    def mockFindById(
      id: String,
      value: Either[ApplicationsException, Application]
    ): ScalaOngoingStubbing[Future[Either[ApplicationsException, Application]]] = {
      when(mockApplicationsRepository.findById(mockitoEq(id)))
        .thenReturn(Future.successful(value))
    }

    def mockUpdate(
      application: Application,
      value: Either[ApplicationsException, Unit]
    ): ScalaOngoingStubbing[Future[Either[ApplicationsException, Unit]]] = {
      when(mockApplicationsRepository.update(mockitoEq(application)))
        .thenReturn(Future.successful(value))
    }
  }

  implicit class IdmsConnectorMockOps(mockIdmsConnector: IdmsConnector) {

    def mockFetchClient(
      environmentName: EnvironmentName,
      clientId: String,
      value: Either[IdmsException, ClientResponse]
    ): ScalaOngoingStubbing[Future[Either[IdmsException, ClientResponse]]] = {
      when(mockIdmsConnector.fetchClient(mockitoEq(environmentName), mockitoEq(clientId))(any()))
        .thenReturn(Future.successful(value))
    }

    def mockFetchClientScopes(
      environmentName: EnvironmentName,
      clientId: String,
      value: Either[IdmsException, Seq[ClientScope]],
    ): ScalaOngoingStubbing[Future[Either[IdmsException, Seq[ClientScope]]]] = {
      when(mockIdmsConnector.fetchClientScopes(mockitoEq(environmentName), mockitoEq(clientId))(any()))
        .thenReturn(Future.successful(value))
    }

  }

}
