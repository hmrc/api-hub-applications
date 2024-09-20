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

package uk.gov.hmrc.apihubapplications.connectors

import uk.gov.hmrc.apihubapplications.models.api.{ApiDetail, ApiTeam}
import uk.gov.hmrc.apihubapplications.models.exception.ApplicationsException
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future

trait IntegrationCatalogueConnector {

  def linkApiToTeam(apiTeam: ApiTeam)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

  def findById(apiId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, ApiDetail]]

  def updateApiTeam(apiId: String, teamId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

  def removeApiTeam(apiId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

  def findApis(queryParams: Seq[(String,String)])(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Seq[ApiDetail]]]

  def findApis(platformFilter: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Seq[ApiDetail]]] = {
    findApis(Seq(("platformFilter", platformFilter)))
  }

  def findHipApis()(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Seq[ApiDetail]]] = {
    findApis("HIP")
  }

}
