/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.apihubapplications.config

import com.kenshoo.play.metrics.Metrics
import play.api.Logging
import uk.gov.hmrc.apihubapplications.config.MetricOrchestratorProvider.DatabaseStatisticMetricSource
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}
import uk.gov.hmrc.mongo.metrix.{MetricOrchestrator, MetricRepository, MetricSource}

import javax.inject.{Inject, Provider, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MetricOrchestratorProvider @Inject()(
  lockRepository: MongoLockRepository,
  metricRepository: MetricRepository,
  metrics: Metrics,
  appConfig: AppConfig,
  repository: ApplicationsRepository
) extends Provider[MetricOrchestrator] with Logging {

  override def get(): MetricOrchestrator = {
    val lockService = LockService(
      lockRepository,
      lockId = "metrix-orchestrator",
      ttl = appConfig.metricOrchestratorTaskLockTtl
    )

    new MetricOrchestrator(
      metricSources    = List(new DatabaseStatisticMetricSource(repository)),
      lockService      = lockService,
      metricRepository = metricRepository,
      metricRegistry   = metrics.defaultRegistry
    )
  }

}

object MetricOrchestratorProvider {

  class DatabaseStatisticMetricSource(repository: ApplicationsRepository) extends MetricSource with Logging {

    override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
      for {
        countOfAllApplications <- repository.countOfAllApplications()
        countOfPendingApprovals <- repository.countOfPendingApprovals()
      } yield {
        logger.info(s"Database metrics: countOfAllApplications=$countOfAllApplications countOfPendingApprovals=$countOfPendingApprovals")
        Map(
          "applications.total.count" -> countOfAllApplications.intValue,
          "applications.pending-approval.count" -> countOfPendingApprovals
        )
      }
    }

  }

}
