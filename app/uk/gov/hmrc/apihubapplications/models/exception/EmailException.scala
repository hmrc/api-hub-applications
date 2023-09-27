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

import uk.gov.hmrc.apihubapplications.models.exception.EmailException.EmailIssue
import uk.gov.hmrc.http.UpstreamErrorResponse

case class EmailException(message: String, cause: Throwable, issue: EmailIssue) extends ApplicationsException(message, cause)

object EmailException {

  sealed trait EmailIssue

  case object MissingConfig extends EmailIssue
  case object UnexpectedResponse extends EmailIssue

  case object MissingRecipient extends EmailIssue

  case object CallError extends EmailIssue

  def missingConfig(configPath: String): EmailException = {
    EmailException(s"Missing configuration value $configPath", null, MissingConfig)
  }

  def unexpectedResponse(statusCode: Int): EmailException = {
    EmailException(s"Unexpected response $statusCode returned from Email API", null, UnexpectedResponse)
  }

  def unexpectedResponse(response: UpstreamErrorResponse): EmailException = {
    unexpectedResponse(response.statusCode)
  }

  def error(throwable: Throwable): EmailException = {
    EmailException("Error calling Email API", throwable, CallError)
  }

  def missingRecipient(): EmailException = {
    EmailException("No recipients for email.", null, MissingRecipient)
  }

}
