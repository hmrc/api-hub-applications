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
import play.api.Configuration
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, ExceptionRaising, IdmsException}
import uk.gov.hmrc.apihubapplications.services.MetricsService
import uk.gov.hmrc.http.{GatewayTimeoutException, HeaderCarrier}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class ApimSyntheticMonitoringScheduler @Inject()(
                                                  apimConnector: APIMConnector,
                                                  idmsConnector: IdmsConnector,
                                                  metricsService: MetricsService,
                                                  configuration: Configuration,
                                                  hipEnvironments: HipEnvironments,
                             )(implicit ec: ExecutionContext) extends BaseScheduler {

  private val schedulerConfig: SchedulerConfig = SchedulerConfig(configuration, "apimSyntheticMonitoringScheduler")
  private val additionalConfiguration = schedulerConfig.additionalConfiguration
  private val publisherReference: String = additionalConfiguration.get[String]("publisherReference")

  override protected[scheduler] def run()(implicit hc: HeaderCarrier): Future[Unit] =
    Future.sequence(
      apimChecks().map(_.apply())
    ).map(_ => ())

  private def checkAndMeter(operation: String, hipEnvironment: HipEnvironment, f: Future[Either[Option[Throwable], Unit]]): Future[Unit] =
    f.recover {
        case NonFatal(ex) => Left(Some(ex))
      }.map {
        case Right(_) => metricsService.apimSyntheticCheck(hipEnvironment.id, operation, "success")
        case Left(Some(_: GatewayTimeoutException)) => metricsService.apimSyntheticCheck(hipEnvironment.id, operation, "timeout")
        case _ => metricsService.apimSyntheticCheck(hipEnvironment.id, operation, "error")
      }

  private def apimChecks()(implicit hc: HeaderCarrier): Seq[() => Future[Unit]] =
    hipEnvironments.environments.flatMap(hipEnvironment =>
      Seq(
        () => checkAndMeter("getDeployment", hipEnvironment, apimConnector.getDeployment(publisherReference, hipEnvironment).map(transform)),
        () => checkAndMeter("listEgressGateways", hipEnvironment, apimConnector.listEgressGateways(hipEnvironment).map(transform)),
        () => checkAndMeter("fetchClientScopes", hipEnvironment, idmsConnector.fetchClientScopes(hipEnvironment, hipEnvironment.clientId).map(transform))
      ),
  ) :+ (() => checkAndMeter("getDeploymentDetails", hipEnvironments.deployTo, apimConnector.getDeploymentDetails(publisherReference).map(transform)))

  private def transform(result: Either[ApimException | IdmsException, ?]): Either[Option[Throwable], Unit] = result match {
    case Left(ApimException(_, cause, _)) => Left(Option(cause))
    case Left(IdmsException(_, cause, _)) => Left(Option(cause))
    case Right(_) => Right(())
  }

}
