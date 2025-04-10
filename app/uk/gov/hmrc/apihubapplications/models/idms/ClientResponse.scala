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

package uk.gov.hmrc.apihubapplications.models.idms

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.apihubapplications.config.HipEnvironment
import uk.gov.hmrc.apihubapplications.models.application.Credential

import java.time.{Clock, LocalDateTime}

case class ClientResponse(clientId: String, secret: String) {

  def asNewCredential(clock: Clock, hipEnvironment: HipEnvironment): Credential = {
    Credential(
      clientId = clientId,
      created = LocalDateTime.now(clock),
      clientSecret = Some(secret),
      secretFragment = Some(secret.takeRight(4)),
      environmentId = hipEnvironment.id
    )
  }
}

object ClientResponse {

  implicit val formatClientResponse: Format[ClientResponse] = Json.format[ClientResponse]

}
