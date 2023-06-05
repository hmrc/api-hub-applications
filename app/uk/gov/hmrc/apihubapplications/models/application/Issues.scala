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

package uk.gov.hmrc.apihubapplications.models.application

import uk.gov.hmrc.apihubapplications.models.exception.IdmsException

object Issues {

  def primaryScopesNotFound(idmsException: IdmsException): String = {
    s"Primary scopes not found. ${idmsException.message}"
  }

  def secondaryCredentialNotFound(idmsException: IdmsException): String = {
    s"Secondary credential not found. ${idmsException.message}"
  }

  def secondaryScopesNotFound(idmsException: IdmsException): String = {
    s"Secondary scopes not found. ${idmsException.message}"
  }

}
