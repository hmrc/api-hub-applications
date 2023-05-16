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
import uk.gov.hmrc.apihubapplications.models.application.Application
import uk.gov.hmrc.http.UpstreamErrorResponse

trait ExceptionRaising {
  self: Logging =>

  object raiseApplicationDataIssueException {
    def apply(message: String): ApplicationDataIssueException = {
      logError(ApplicationDataIssueException(message))
    }

    def forApplication(application: Application, dataIssue: DataIssue): ApplicationDataIssueException = {
      logError(ApplicationDataIssueException.forApplication(application, dataIssue))
    }
  }

  object raiseApplicationNotFoundException {
    def apply(message: String): ApplicationNotFoundException = {
      logWarn(ApplicationNotFoundException(message))
    }

    def forId(id: String): ApplicationNotFoundException = {
      logWarn(ApplicationNotFoundException.forId(id))
    }

    def forApplication(application: Application): ApplicationNotFoundException = {
      logWarn(ApplicationNotFoundException.forApplication(application))
    }
  }

  object raiseIdmsException {
    def apply(message: String, cause: Throwable): IdmsException = {
      logError(IdmsException(message, cause))
    }

    def apply(message: String): IdmsException = {
      logError(IdmsException(message))
    }

    def clientNotFound(clientId: String): IdmsException = {
      logError(IdmsException.clientNotFound(clientId))
    }

    def unexpectedResponse(response: UpstreamErrorResponse): IdmsException = {
      logError(IdmsException.unexpectedResponse(response))
    }

    def error(throwable: Throwable): IdmsException = {
      logError(IdmsException.error(throwable))
    }
  }

  object raiseNotUpdatedException {
    def apply(message: String): NotUpdatedException = {
      logError(NotUpdatedException(message))
    }

    def forId(id: String): NotUpdatedException = {
      logError(NotUpdatedException.forId(id))
    }

    def forApplication(application: Application): NotUpdatedException = {
      logError(NotUpdatedException.forApplication(application))
    }
  }

  private def logError[T <: ApplicationsException](e: T): T = {
    logger.error("Raised application exception", e)
    e
  }

  private def logWarn[T <: ApplicationsException](e: T): T = {
    logger.warn("Raised application exception", e)
    e
  }

}
