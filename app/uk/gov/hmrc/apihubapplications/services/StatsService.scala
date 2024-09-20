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

import cats.data.EitherT
import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, IntegrationCatalogueConnector}
import uk.gov.hmrc.apihubapplications.models.api.ApiDetail
import uk.gov.hmrc.apihubapplications.models.apim.ApiDeployment
import uk.gov.hmrc.apihubapplications.models.application.Primary
import uk.gov.hmrc.apihubapplications.models.exception.ApplicationsException
import uk.gov.hmrc.apihubapplications.models.stats.ApisInProductionStatistic
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class StatsService @Inject()(
  apimConnector: APIMConnector,
  integrationCatalogueConnector: IntegrationCatalogueConnector
)(implicit ec: ExecutionContext) {

  def apisInProduction()(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, ApisInProductionStatistic]] = {
    val futureDeployments = apimConnector.getDeployments(Primary)
    val futureApis = integrationCatalogueConnector.findHipApis()

    (for {
      deployments <- EitherT(futureDeployments)
      apis <- EitherT(integrationCatalogueConnector.findHipApis())
    } yield buildApisInProductionStatistic(deployments, apis)).value
  }

  private def buildApisInProductionStatistic(deployments: Seq[ApiDeployment], apis: Seq[ApiDetail]): ApisInProductionStatistic = {
    val ids = deployments.map(_.id).toSet
    val publisherRefs = apis.map(_.publisherReference).toSet
    val totalInProduction = ids.intersect(publisherRefs).size

    ApisInProductionStatistic(apis.size, totalInProduction)
  }

}
