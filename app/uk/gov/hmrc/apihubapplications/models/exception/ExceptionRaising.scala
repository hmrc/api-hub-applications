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

trait ExceptionRaising {
  self: Logging =>

  object applicationBadException {
    def apply(message: String): ApplicationBadException = {
      log(ApplicationBadException(message))
    }
  }

  object applicationNotFoundException {
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

  object idmsException {
    def apply(message: String, cause: Throwable): IdmsException = {
      log(IdmsException(message, cause))
    }

    def apply(message: String): IdmsException = {
      log(IdmsException(message))
    }
  }

  object notUpdatedException {
    def apply(message: String): NotUpdatedException = {
      log(NotUpdatedException(message))
    }

    def forId(id: String): NotUpdatedException = {
      log(NotUpdatedException.forId(id))
    }

    def forApplication(application: Application): NotUpdatedException = {
      log(NotUpdatedException.forApplication(application))
    }
  }

  private def log[T <: ApplicationsException](e: T): T = {
    logger.warn("Raised application exception", e)
    e
  }

}
