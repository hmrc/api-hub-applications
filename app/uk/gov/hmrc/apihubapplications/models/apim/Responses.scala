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

package uk.gov.hmrc.apihubapplications.models.apim

import play.api.libs.json.{Format, Json}

sealed trait ValidateResponse

case object SuccessfulValidateResponse extends ValidateResponse

sealed trait DeploymentsResponse

case class SuccessfulDeploymentsResponse(id: String, version: String, mergeRequestIid: Int, uri: String) extends DeploymentsResponse

object SuccessfulDeploymentsResponse {

  implicit val formatSuccessfulDeploymentsResponse: Format[SuccessfulDeploymentsResponse] = Json.format[SuccessfulDeploymentsResponse]

}

case class InvalidOasResponse(failure: FailuresResponse) extends ValidateResponse with DeploymentsResponse

object InvalidOasResponse {

  implicit val formatInvalidOasResponse: Format[InvalidOasResponse] = Json.format[InvalidOasResponse]

}

sealed trait DeploymentResponse

case class SuccessfulDeploymentResponse(id: String) extends DeploymentResponse

object SuccessfulDeploymentResponse {

  implicit val formatDeploymentResponse: Format[SuccessfulDeploymentResponse] = Json.format[SuccessfulDeploymentResponse]

}