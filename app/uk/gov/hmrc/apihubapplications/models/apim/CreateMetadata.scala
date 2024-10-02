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

case class CreateMetadata(
  lineOfBusiness: String,
  name: String,
  description: String,
  egress: String,
  passthrough: Boolean,
  status: Option[String],
  domain: String,
  subdomain: String,
  backends: Seq[String],
  prefixesToRemove: Seq[String],
  egressMappings: Option[Seq[EgressMapping]]
)

object CreateMetadata {

  def apply(request: DeploymentsRequest): CreateMetadata = {
    CreateMetadata(
      lineOfBusiness = request.lineOfBusiness,
      name = request.name,
      description = request.description,
      egress = request.egress,
      passthrough = request.passthrough,
      status = Option.apply(request.status),
      domain = request.domain,
      subdomain = request.subDomain,
      backends = request.hods,
      prefixesToRemove = request.prefixesToRemove,
      egressMappings = request.egressMappings
    )
  }

  implicit val formatCreateMetadata: Format[CreateMetadata] = Json.format[CreateMetadata]

}
