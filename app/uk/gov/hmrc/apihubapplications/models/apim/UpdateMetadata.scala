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

case class UpdateMetadata(
  description: String,
  status: String,
  domain: String,
  subdomain: String,
  backends: Seq[String],
  prefixesToRemove: Seq[String],
  egressMappings: Option[Seq[EgressMapping]],
  egress: String,
)

object UpdateMetadata {

  def apply(request: RedeploymentRequest): UpdateMetadata = {
    UpdateMetadata(
      description = request.description,
      status = request.status,
      domain = request.domain,
      subdomain = request.subDomain,
      backends = request.hods,
      prefixesToRemove = request.prefixesToRemove,
      egressMappings = request.egressMappings,
      egress = request.egress.getOrElse(CreateMetadata.egressFallback)
    )
  }

  implicit val formatUpdateMetadata: Format[UpdateMetadata] = Json.format[UpdateMetadata]

}
