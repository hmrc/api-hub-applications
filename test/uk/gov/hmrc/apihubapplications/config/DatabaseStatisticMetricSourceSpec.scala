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

import org.mockito.MockitoSugar
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.config.DatabaseStatisticsMetricOrchestratorProvider.DatabaseStatisticsMetricSource
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository

import scala.concurrent.Future

class DatabaseStatisticMetricSourceSpec extends AsyncFreeSpec with Matchers with MockitoSugar {

  "metrics" - {
    "must return the correct database statistics" in {
      val repository = mock[ApplicationsRepository]
      when(repository.countOfAllApplications()).thenReturn(Future.successful(42))
      when(repository.countOfPendingApprovals()).thenReturn(Future.successful(13))

      val metricSource = new DatabaseStatisticsMetricSource(repository)

      val expected = Map(
        "applications.total.count" -> 42,
        "applications.pending-approval.count" -> 13
      )

      metricSource.metrics.map(
        actual =>
          actual mustBe expected
      )
    }
  }

}
