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

package uk.gov.hmrc.apihubapplications.services

import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.exception.*
import uk.gov.hmrc.apihubapplications.models.requests.AddApiRequest
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}

import scala.concurrent.Future

@Singleton
class ApplicationsService @Inject()(
  apiService: ApplicationsApiService,
  credentialsService: ApplicationsCredentialsService,
  lifecycleService: ApplicationsLifecycleService,
  searchService: ApplicationsSearchService
) extends ApplicationsApiService
  with ApplicationsCredentialsService
  with ApplicationsLifecycleService
  with ApplicationsSearchService {

  override def addApi(applicationId: String, newApi: AddApiRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    apiService.addApi(applicationId, newApi)
  }

  override def removeApi(applicationId: String, apiId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    apiService.removeApi(applicationId, apiId)
  }

  override def changeOwningTeam(applicationId: String, teamId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    apiService.changeOwningTeam(applicationId, teamId)
  }

  override def removeOwningTeamFromApplication(applicationId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    apiService.removeOwningTeamFromApplication(applicationId)
  }

  override def fixScopes(applicationId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    apiService.fixScopes(applicationId)
  }

  override def addCredential(applicationId: String, hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Credential]] = {
    credentialsService.addCredential(applicationId, hipEnvironment)
  }

  override def deleteCredential(applicationId: String, hipEnvironment: HipEnvironment, clientId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    credentialsService.deleteCredential(applicationId, hipEnvironment, clientId)
  }

  override def registerApplication(newApplication: NewApplication)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    lifecycleService.registerApplication(newApplication)
  }

  override def delete(applicationId: String, currentUser: String, hipEnvironments: HipEnvironments)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    lifecycleService.delete(applicationId, currentUser, hipEnvironments)
  }

  override def addTeamMember(applicationId: String, teamMember: TeamMember)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    lifecycleService.addTeamMember(applicationId, teamMember)
  }

  override def findAll(teamMemberEmail: Option[String], includeDeleted: Boolean): Future[Seq[Application]] = {
    searchService.findAll(teamMemberEmail, includeDeleted)
  }

  override def findAllUsingApi(apiId: String, includeDeleted: Boolean): Future[Seq[Application]] = {
    searchService.findAllUsingApi(apiId, includeDeleted)
  }

  override def findById(id: String, enrich: Boolean, includeDeleted: Boolean)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    searchService.findById(id, enrich, includeDeleted)
  }

  override def findByTeamId(id: String, includeDeleted: Boolean)(implicit hc: HeaderCarrier): Future[Seq[Application]] = {
    searchService.findByTeamId(id, includeDeleted)
  }

  override def fetchAllScopes(applicationId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Seq[CredentialScopes]]] = {
    credentialsService.fetchAllScopes(applicationId)
  }

}
