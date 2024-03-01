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
import uk.gov.hmrc.apihubapplications.models.exception.SimpleApiDeploymentException.SimpleApiDeploymentIssue
import uk.gov.hmrc.http.UpstreamErrorResponse

case class SimpleApiDeploymentException(message: String, cause: Throwable, issue: SimpleApiDeploymentIssue) extends ApplicationsException(message, cause)

object SimpleApiDeploymentException {

  sealed trait SimpleApiDeploymentIssue

  case object UnexpectedResponse extends SimpleApiDeploymentIssue
  case object InvalidResponse extends SimpleApiDeploymentIssue

  def apply(message: String, issue: SimpleApiDeploymentIssue): SimpleApiDeploymentException = {
    SimpleApiDeploymentException(message, null, issue)
  }

  def unexpectedResponse(statusCode: Int): SimpleApiDeploymentException = {
    SimpleApiDeploymentException(s"Unexpected response $statusCode returned from Simple API Deployment service", UnexpectedResponse)
  }

  def unexpectedResponse(response: UpstreamErrorResponse): SimpleApiDeploymentException = {
    unexpectedResponse(response.statusCode)
  }

  def invalidResponse(errors: collection.Seq[(JsPath, collection.Seq[JsonValidationError])]): SimpleApiDeploymentException = {
    SimpleApiDeploymentException(s"Invalid response from Simple API Deployment service${System.lineSeparator()}${JsError.toJson(errors).toString()}", InvalidResponse)
  }

}
