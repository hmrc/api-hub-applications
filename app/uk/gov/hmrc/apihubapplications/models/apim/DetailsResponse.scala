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

case class DetailsResponse(
  description: String,
  status: String,
  domain: String,
  subdomain: String,
  backends: Seq[String],
  egressPrefix: Option[String],
  prefixesToRemove: Option[Seq[String]]
) {

  def toDeploymentDetails: DeploymentDetails = {
    DeploymentDetails(
      description = description,
      status = status,
      domain = domain,
      subDomain = subdomain,
      hods = backends,
      egressPrefix = mapEgressPrefix,
      prefixesToRemove = mapPrefixesToRemove
    )
  }

  private def mapEgressPrefix: Option[String] = {
    egressPrefix.map(_.trim) match {
      case Some(s) if s.nonEmpty => Some(s)
      case _ => None
    }
  }

  private def mapPrefixesToRemove: Seq[String] = {
    prefixesToRemove.getOrElse(Seq.empty)
  }

}

object DetailsResponse {

  implicit val formatDetailsResponse: Format[DetailsResponse] = Json.format[DetailsResponse]

}
