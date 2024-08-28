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

import org.mockito.Mockito.when
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.config.DatabaseStatisticsMetricOrchestratorProvider.DatabaseStatisticsMetricSource
import uk.gov.hmrc.apihubapplications.repositories.{AccessRequestsRepository, ApplicationsRepository}

import scala.concurrent.Future

class DatabaseStatisticMetricSourceSpec extends AsyncFreeSpec with Matchers with MockitoSugar {

  "metrics" - {
    "must return the correct database statistics" in {
      val applicationsRepository = mock[ApplicationsRepository]
      val accessRequestsRepository = mock[AccessRequestsRepository]

      when(applicationsRepository.countOfAllApplications()).thenReturn(Future.successful(42L))
      when(accessRequestsRepository.countOfPendingApprovals()).thenReturn(Future.successful(13L))

      val metricSource = new DatabaseStatisticsMetricSource(applicationsRepository, accessRequestsRepository)

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
