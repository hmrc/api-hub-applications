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

package uk.gov.hmrc.apihubapplications.schedulers

import org.apache.pekko.actor.ActorSystem
import play.api.{Configuration, Logging}
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.apihubapplications.circuitbreakers.CircuitBreakers
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, CircuitBreakerAPIMConnectorImpl, IdmsConnector}
import uk.gov.hmrc.apihubapplications.models.application.EnvironmentName
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

@Singleton
class SyntheticMonitoringScheduler @Inject()(
                                              apimConnector: APIMConnector,
                                              idmsConnector: IdmsConnector,
                                              configuration: Configuration,
                                  )(using ActorSystem, ApplicationLifecycle, ExecutionContext)
  extends Scheduler with Logging:

  private val schedulerConfig =
    val enabledKey = "syntheticMonitoringScheduler.enabled"
    SchedulerConfig(
      enabledKey = enabledKey,
      enabled  = configuration.get[Boolean](enabledKey),
      interval = configuration.get[FiniteDuration]("syntheticMonitoringScheduler.interval"),
      initialDelay = configuration.get[FiniteDuration]("syntheticMonitoringScheduler.initialDelay")
    )
  private val idmsClientIds: Map[EnvironmentName, String] = EnvironmentName.values.map(environmentName =>
    environmentName -> configuration.get[String](s"microservice.services.idms-$environmentName.clientId")
  ).toMap

  given HeaderCarrier = HeaderCarrier()

  private def checkHipStatus(): Future[Unit] = {
    Future.sequence(
      EnvironmentName.values.map(environment =>
        Future.sequence(
          Seq(
            apimConnector.getDeployments(environment),
            idmsConnector.fetchClient(environment, idmsClientIds(environment)),
          )
        )
      )
    ).map(_ => ())
  }

  schedule(
    "Synthetic Monitoring Scheduler",
    schedulerConfig,
  ) {

    logger.info("Starting synthetic monitoring checks")

    for
      _ <- checkHipStatus()
      _ =  logger.info("Finished synthetic monitoring checks")
    yield ()
  }

