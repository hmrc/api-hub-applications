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

package uk.gov.hmrc.apihubapplications.models.accessRequest

import play.api.libs.json.{Format, Json}

import java.time.{Clock, LocalDateTime}

case class AccessRequestRequest(
  applicationId: String,
  supportingInformation: String,
  requestedBy: String,
  apis: Seq[AccessRequestApi]
) {

  def toAccessRequests(clock: Clock): Seq[AccessRequest] = {
    apis.map(
      api =>
        AccessRequest(
          applicationId = applicationId,
          apiId = api.apiId,
          apiName = api.apiName,
          status = Pending,
          endpoints = api.endpoints,
          supportingInformation = supportingInformation,
          requested = LocalDateTime.now(clock),
          requestedBy = requestedBy
        )
    )
  }

}

object AccessRequestRequest {

  implicit val formatAccessRequestRequest: Format[AccessRequestRequest] = Json.format[AccessRequestRequest]

}
