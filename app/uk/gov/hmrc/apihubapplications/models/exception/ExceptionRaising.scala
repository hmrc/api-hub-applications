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

package uk.gov.hmrc.apihubapplications.models.exception

import play.api.Logging
import play.api.libs.json.{JsPath, JsonValidationError}
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequest
import uk.gov.hmrc.apihubapplications.models.application.{Application, EnvironmentName}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.http.UpstreamErrorResponse

trait ExceptionRaising {
  self: Logging =>

  object raiseApplicationDataIssueException {
    def forApplication(application: Application, dataIssue: DataIssue): ApplicationDataIssueException = {
      log(ApplicationDataIssueException.forApplication(application, dataIssue))
    }
  }

  object raiseApplicationNotFoundException {
    def apply(message: String): ApplicationNotFoundException = {
      log(ApplicationNotFoundException(message))
    }

    def forId(id: String): ApplicationNotFoundException = {
      log(ApplicationNotFoundException.forId(id))
    }

    def forApplication(application: Application): ApplicationNotFoundException = {
      log(ApplicationNotFoundException.forApplication(application))
    }
  }

  object raiseApplicationTeamMigratedException {
    def forId(id: String): ApplicationTeamMigratedException = {
      log(ApplicationTeamMigratedException.forId(id))
    }
  }

  object raiseApiNotFoundException {
    def forId(apiId: String): ApiNotFoundException = {
      log(ApiNotFoundException.forId(apiId))
    }

    def forApplication(applicationId: String, apiId: String): ApiNotFoundException = {
      log(ApiNotFoundException.forApplication(applicationId, apiId))
    }
  }

  object raiseIdmsException {
    def clientNotFound(clientId: String): IdmsException = {
      log(IdmsException.clientNotFound(clientId))
    }

    def unexpectedResponse(response: UpstreamErrorResponse): IdmsException = {
      log(IdmsException.unexpectedResponse(response))
    }

    def error(throwable: Throwable): IdmsException = {
      log(IdmsException.error(throwable))
    }
  }

  object raiseEmailException {
    def missingConfig(configPath: String): EmailException = {
      log(EmailException.missingConfig(configPath))
    }

    def unexpectedResponse(response: UpstreamErrorResponse): EmailException = {
      log(EmailException.unexpectedResponse(response))
    }

    def error(throwable: Throwable): EmailException = {
      log(EmailException.error(throwable))
    }
  }

  object raiseNotUpdatedException {
    def apply(message: String): NotUpdatedException = {
      log(NotUpdatedException(message))
    }

    def forId(id: String): NotUpdatedException = {
      log(NotUpdatedException.forId(id))
    }

    def forApplication(application: Application): NotUpdatedException = {
      log(NotUpdatedException.forApplication(application))
    }

    def forAccessRequest(accessRequest: AccessRequest): NotUpdatedException = {
      log(NotUpdatedException.forAccessRequest(accessRequest))
    }

    def forTeam(team: Team): NotUpdatedException = {
      log(NotUpdatedException.forTeam(team))
    }
  }

  object raiseAccessRequestNotFoundException {
    def forId(id: String): AccessRequestNotFoundException = {
      log(AccessRequestNotFoundException.forId(id))
    }

    def forAccessRequest(accessRequest: AccessRequest): AccessRequestNotFoundException = {
      log(AccessRequestNotFoundException.forAccessRequest(accessRequest))
    }
  }

  object raiseAccessRequestStatusInvalidException {
    def forAccessRequest(accessRequest: AccessRequest): AccessRequestStatusInvalidException = {
      log(AccessRequestStatusInvalidException.forAccessRequest(accessRequest))
    }
  }

  object raiseCredentialNotFoundException {
    def forClientId(clientId: String): CredentialNotFoundException = {
      log(CredentialNotFoundException.forClientId(clientId))
    }
  }

  object raiseApplicationCredentialLimitException {
    def forApplication(application: Application, environmentName: EnvironmentName): ApplicationCredentialLimitException = {
      log(ApplicationCredentialLimitException.forApplication(application, environmentName))
    }
  }

  object raiseTeamMemberExistsException {
    def forId(id: String): TeamMemberExistsException = {
      log(TeamMemberExistsException.forId(id))
    }

    def forApplication(application: Application): TeamMemberExistsException = {
      log(TeamMemberExistsException.forApplication(application))
    }

    def forTeam(team: Team): TeamMemberExistsException = {
      log(TeamMemberExistsException.forTeam(team))
    }
  }

  object raiseApimException {
    def unexpectedResponse(statusCode: Int): ApimException = {
      log(ApimException.unexpectedResponse(statusCode))
    }

    def invalidResponse(errors: collection.Seq[(JsPath, collection.Seq[JsonValidationError])]): ApimException = {
      log(ApimException.invalidResponse(errors))
    }

    def serviceNotFound(serviceId: String): ApimException = {
      log(ApimException.serviceNotFound(serviceId))
    }
  }

  object raiseTeamNotFoundException {
    def forId(id: String): TeamNotFoundException = {
      log(TeamNotFoundException.forId(id))
    }

    def forTeam(team: Team): TeamNotFoundException = {
      log(TeamNotFoundException.forTeam(team))
    }
  }

  object raiseIntegrationCatalogueException {
    def unexpectedResponse(statusCode: Int): IntegrationCatalogueException = {
      log(IntegrationCatalogueException.unexpectedResponse(statusCode))
    }
  }

  object raiseTeamNameNotUniqueException {
    def forName(name: String): TeamNameNotUniqueException = {
      log(TeamNameNotUniqueException.forName(name))
    }
  }

  private def log[T <: ApplicationsException](e: T): T = {
    e match {
      case emailException: EmailException => logger.error("Raised EmailException:", emailException)
      case _ => logger.warn("Raised application exception", e)
    }
    e
  }

}
