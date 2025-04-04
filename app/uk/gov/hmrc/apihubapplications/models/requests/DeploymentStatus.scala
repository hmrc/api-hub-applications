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

package uk.gov.hmrc.apihubapplications.models.requests

import play.api.libs.json.{Format, Json, JsonConfiguration, JsonNaming}

sealed trait DeploymentStatus {
  def environmentId: String
}

object DeploymentStatus {

  case class Deployed(override val environmentId: String, version: String) extends DeploymentStatus

  case class NotDeployed(override val environmentId: String) extends DeploymentStatus

  case class Unknown(override val environmentId: String) extends DeploymentStatus

  private implicit val jsonConfiguration: JsonConfiguration =
    JsonConfiguration(typeNaming = JsonNaming { fullName => fullName.split('.').last })

  implicit val formatDeployed: Format[Deployed] = Json.format[Deployed]
  implicit val formatNotDeployed: Format[NotDeployed] = Json.format[NotDeployed]
  implicit val formatUnknown: Format[Unknown] = Json.format[Unknown]
  implicit val formatDeploymentStatus: Format[DeploymentStatus] = Json.format[DeploymentStatus]

}

case class DeploymentStatuses(statuses: Seq[DeploymentStatus])

object DeploymentStatuses {
  implicit val formatDeploymentStatuses: Format[DeploymentStatuses] = Json.format[DeploymentStatuses]
}
