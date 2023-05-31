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

import uk.gov.hmrc.http.UpstreamErrorResponse

sealed trait IdmsIssue

case object ClientNotFound extends IdmsIssue
case object UnexpectedResponse extends IdmsIssue
case object CallError extends IdmsIssue

case class IdmsException(message: String, cause: Throwable, issue: IdmsIssue) extends ApplicationsException(message, cause)

object IdmsException {

  def apply(message: String, issue: IdmsIssue): IdmsException = {
    IdmsException(message, null, issue)
  }

  def clientNotFound(clientId: String): IdmsException = {
    IdmsException(s"Client not found: clientId=$clientId", ClientNotFound)
  }

  def unexpectedResponse(statusCode: Int): IdmsException = {
    IdmsException(s"Unexpected response $statusCode returned from IDMS", UnexpectedResponse)
  }

  def unexpectedResponse(response: UpstreamErrorResponse): IdmsException = {
    unexpectedResponse(response.statusCode)
  }

  def error(throwable: Throwable): IdmsException = {
    IdmsException("Error calling IDMS", throwable, CallError)
  }

}
