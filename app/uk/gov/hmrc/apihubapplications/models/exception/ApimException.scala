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

import play.api.libs.json.{JsError, JsPath, JsonValidationError}
import uk.gov.hmrc.apihubapplications.models.exception.ApimException.ApimIssue
import uk.gov.hmrc.http.UpstreamErrorResponse

case class ApimException(message: String, cause: Throwable, issue: ApimIssue) extends ApplicationsException(message, cause)

object ApimException {

  sealed trait ApimIssue

  case object UnexpectedResponse extends ApimIssue
  case object InvalidResponse extends ApimIssue
  case object ServiceNotFound extends ApimIssue

  def apply(message: String, issue: ApimIssue): ApimException = {
    ApimException(message, null, issue)
  }

  def unexpectedResponse(statusCode: Int): ApimException = {
    unexpectedResponse(statusCode, Seq.empty)
  }

  def unexpectedResponse(statusCode: Int, context: Seq[(String, AnyRef)]): ApimException = {
    ApimException(
      ApplicationsException.addContext(s"Unexpected response $statusCode returned from APIM", context),
      UnexpectedResponse
    )
  }

  def unexpectedResponse(response: UpstreamErrorResponse): ApimException = {
    unexpectedResponse(response.statusCode, Seq.empty)
  }

  def unexpectedResponse(response: UpstreamErrorResponse, context: Seq[(String, AnyRef)]): ApimException = {
    unexpectedResponse(response.statusCode, context)
  }

  def invalidResponse(errors: collection.Seq[(JsPath, collection.Seq[JsonValidationError])]): ApimException = {
    ApimException(s"Invalid response from APIM: ${System.lineSeparator()}${JsError.toJson(errors).toString()}", InvalidResponse)
  }

  def serviceNotFound(serviceId: String): ApimException = {
    ApimException(s"Cannot find service with serviceId: $serviceId", ServiceNotFound)
  }

}
