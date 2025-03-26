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
import org.apache.pekko.actor.ActorSystem
import play.api.{Configuration, Environment, Play}
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, ExceptionRaising, IdmsException}
import uk.gov.hmrc.apihubapplications.services.MetricsService
import uk.gov.hmrc.http.{GatewayTimeoutException, HeaderCarrier}
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, ScheduledLockService}
import uk.gov.hmrc.mongo.TimestampSupport

import java.io.InputStream
import java.time.{Clock, DayOfWeek, LocalTime, ZonedDateTime}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class ApimSyntheticMonitoringScheduler @Inject()(
                                                  apimConnector: APIMConnector,
                                                  idmsConnector: IdmsConnector,
                                                  metricsService: MetricsService,
                                                  configuration: Configuration,
                                                  hipEnvironments: HipEnvironments,
                                                  mongoLockRepository : MongoLockRepository,
                                                  timestampSupport    : TimestampSupport,
                                                  clock: Clock,
                             )(implicit as: ActorSystem, alc: ApplicationLifecycle, ec: ExecutionContext) extends BaseScheduler {

  private val schedulerConfig: SchedulerConfig = SchedulerConfig(configuration, "apimSyntheticMonitoringScheduler")
  private val additionalConfiguration = schedulerConfig.additionalConfiguration
  private val publisherReference: String = additionalConfiguration.get[String]("publisherReference")
  private given HeaderCarrier = HeaderCarrier()

  private val weekendDays = Seq(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
  private val dateTimeRangeApimServicesAreOn = (
    LocalTime.of(7, 35),
    LocalTime.of(18, 55),
  )

  private lazy val oas = String(ClassLoader.getSystemResourceAsStream("public/exemplar.yaml").readAllBytes())

  private def canMonitor: Boolean = {
    val (fromTime, toTime) = dateTimeRangeApimServicesAreOn
    val now = ZonedDateTime.now(clock)
    val currentTime = now.toLocalTime
    !weekendDays.contains(now.getDayOfWeek) && currentTime.isAfter(fromTime) && currentTime.isBefore(toTime)
  }

  override protected[scheduler] def run()(implicit hc: HeaderCarrier): Future[Unit] =
    if canMonitor then
      Future.sequence(
        apimChecks().map(_.apply())
      ).map(_ => ())
    else Future.unit

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
        () => checkAndMeter("fetchClientScopes", hipEnvironment, idmsConnector.fetchClientScopes(hipEnvironment, hipEnvironment.clientId).map(transform)),
        () => checkAndMeter("getDeploymentDetails", hipEnvironment, apimConnector.getDeploymentDetails(publisherReference, hipEnvironment).map(transform)),
        () => checkAndMeter("validateOas", hipEnvironment, apimConnector.validateOas(oas, hipEnvironment).map(transform))
      )
    )

  private def transform(result: Either[ApimException | IdmsException, ?]): Either[Option[Throwable], Unit] = result match {
    case Left(ApimException(_, cause, _)) => Left(Option(cause))
    case Left(IdmsException(_, cause, _)) => Left(Option(cause))
    case Right(_) => Right(())
  }

  scheduleWithTimePeriodLock(
    label           = "ApimSyntheticMonitoringScheduler",
    schedulerConfig = schedulerConfig,
    lock            = ScheduledLockService(mongoLockRepository, "apim-synthetic-monitoring-scheduler", timestampSupport, schedulerConfig.interval)
  )

}
