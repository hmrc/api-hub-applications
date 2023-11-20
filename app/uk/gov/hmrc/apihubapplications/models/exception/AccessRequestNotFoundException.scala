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

import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequest

case class AccessRequestNotFoundException(message: String) extends ApplicationsException(message, null)

object AccessRequestNotFoundException {

  def forId(id: String): AccessRequestNotFoundException = {
    AccessRequestNotFoundException(s"Cannot find access request with id $id")
  }

  def forAccessRequest(accessRequest: AccessRequest): AccessRequestNotFoundException = {
    val id = accessRequest.id.getOrElse("<none")
    AccessRequestNotFoundException(s"Cannot find access request with id $id")
  }

}
