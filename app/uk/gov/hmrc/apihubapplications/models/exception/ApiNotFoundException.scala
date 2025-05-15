/*
 * Copyright 2024 HM Revenue & Customs
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

case class ApiNotFoundException(message: String) extends ApplicationsException(message, null)

object ApiNotFoundException {

  def forId(apiId: String): ApiNotFoundException = {
    ApiNotFoundException(s"Cannot find API with Id $apiId")
  }

  def forPublisherRef(publisherRef: String): ApiNotFoundException = {
    ApiNotFoundException(s"Cannot find API with Publisher Ref $publisherRef")
  }

  def forApplication(applicationId: String, apiId: String): ApiNotFoundException = {
    ApiNotFoundException(s"Cannot find API $apiId linked to application $applicationId")
  }

}
