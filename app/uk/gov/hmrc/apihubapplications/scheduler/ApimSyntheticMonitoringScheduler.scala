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

package uk.gov.hmrc.apihubapplications.scheduler

import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, IdmsException}
import uk.gov.hmrc.apihubapplications.services.MetricsService
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApimSyntheticMonitoringScheduler @Inject()(
                             apimConnector: APIMConnector,
                             idmsConnector: IdmsConnector,
                             metricsService: MetricsService,
                             schedulerConfigs: SchedulerConfigs,
                             hipEnvironments: HipEnvironments
                             )(implicit ec: ExecutionContext) extends BaseScheduler {

  override protected[scheduler] def run()(implicit hc: HeaderCarrier): Future[Unit] =
    Future.sequence(
      apimChecks().map(_.apply())
    ).map(_ => ())

  private val schedulerConfig = schedulerConfigs.apimMonitoringSchdulerConfig
  private val additionalConfiguration = schedulerConfig.additionalConfiguration
  private val hipEnvironment: HipEnvironment = hipEnvironments.forId(additionalConfiguration.get[String]("hipEnvironment"))
  private val publisherReference: String = additionalConfiguration.get[String]("publisherReference")

  private def checkAndMeter(operation: String, f: Future[Either[String, Unit]]): Future[Unit] =
    f.map {
      case Right(_) => metricsService.apimSyntheticCheck(hipEnvironment.id, operation, "success")
      case Left(issue) => metricsService.apimSyntheticCheck(hipEnvironment.id, operation, issue)
    }

  private def apimChecks()(implicit hc: HeaderCarrier): Seq[() => Future[Unit]] = Seq(
    () => checkAndMeter("getDeployment", apimConnector.getDeployment(publisherReference, hipEnvironment).map(transform)),
    () => checkAndMeter("getDeploymentDetails", apimConnector.getDeploymentDetails(publisherReference).map(transform)),
    () => checkAndMeter("listEgressGateways", apimConnector.listEgressGateways(hipEnvironment).map(transform)),
    () => checkAndMeter("fetchClientScopes", idmsConnector.fetchClientScopes(hipEnvironment, hipEnvironment.clientId).map(transform)),
  )

  private def transform(result: Either[ApimException | IdmsException, ?]) = result match {
    case Left(e: ApimException) => Left(e.issue.toString.toLowerCase)
    case Left(e: IdmsException) => Left(e.issue.toString.toLowerCase)
    case Right(_) => Right(())
  }

}
