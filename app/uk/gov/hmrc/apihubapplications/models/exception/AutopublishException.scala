/*
 * Copyright 2025 HM Revenue & Customs
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

import uk.gov.hmrc.apihubapplications.models.exception.AutopublishException.AutopublishIssue

case class AutopublishException(message: String, issue: AutopublishIssue) extends ApplicationsException(message, null)

object AutopublishException {

  sealed trait AutopublishIssue

  case object DeploymentNotFound extends AutopublishIssue
  case object UnexpectedResponse extends AutopublishIssue

  def deploymentNotFound(publisherReference: String): AutopublishException = {
    AutopublishException(
      message = s"Cannot find a deployment for service $publisherReference",
      issue = DeploymentNotFound
    )
  }

  def unexpectedResponse(statusCode: Int): AutopublishException = {
    AutopublishException(
      message = s"Unexpected response $statusCode returned from auto-publish",
      issue = UnexpectedResponse
    )
  }

}
