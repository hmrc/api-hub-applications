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

import uk.gov.hmrc.apihubapplications.models.WithName
import uk.gov.hmrc.apihubapplications.models.application.Application
import uk.gov.hmrc.apihubapplications.models.exception.ApplicationDataIssueException.buildMessage

sealed trait DataIssue

case object InvalidPrimaryCredentials extends WithName("Invalid primary credentials") with DataIssue
case object InvalidSecondaryCredentials extends WithName("Invalid secondary credentials") with DataIssue
case object InvalidPrimaryScope extends WithName("Invalid primary scope") with DataIssue

case object NoCredentialsFound extends WithName("No Credentials found") with DataIssue
case class ApplicationDataIssueException(
  applicationId: String,
  dataIssue: DataIssue
) extends ApplicationsException(buildMessage(applicationId, dataIssue), null)

object ApplicationDataIssueException {

  def forApplication(application: Application, dataIssue: DataIssue): ApplicationDataIssueException = {
    val id = application.id.getOrElse("<none>")
    ApplicationDataIssueException(id, dataIssue)
  }

  def buildMessage(applicationId: String, dataIssue: DataIssue): String = {
    s"Application with Id $applicationId has a data issue: ${dataIssue.toString}"
  }

}
