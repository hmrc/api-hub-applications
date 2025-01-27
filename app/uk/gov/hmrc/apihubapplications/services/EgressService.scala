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

package uk.gov.hmrc.apihubapplications.services

import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.apihubapplications.config.HipEnvironment
import uk.gov.hmrc.apihubapplications.connectors.APIMConnector
import uk.gov.hmrc.apihubapplications.models.api.EgressGateway
import uk.gov.hmrc.apihubapplications.models.exception.ApimException
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future

@Singleton
class EgressService @Inject()(apimConnector: APIMConnector) {
  def listEgressGateways(hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApimException, Seq[EgressGateway]]] =
    apimConnector.listEgressGateways(hipEnvironment)
}
