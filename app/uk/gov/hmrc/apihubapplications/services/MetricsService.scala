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

import com.codahale.metrics.{Meter, MetricRegistry}
import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.apihubapplications.services.MetricsService.MetricsKeys

@Singleton
class MetricsService @Inject()(metricRegistry: MetricRegistry) {

  private lazy val apimDeploymentStatusUnknownMetric: Meter = metricRegistry.meter(MetricsKeys.APIM.apimDeploymentStatusUnknown)

  private def apimCheckStatusMetric(environment: String, metricName: String, status: String): Meter =
    metricRegistry.meter(s"${MetricsKeys.APIM.apimSyntheticMonitoringMetric}.$environment.$metricName.$status")

  def apimUnknownFailure(): Unit = {
    apimDeploymentStatusUnknownMetric.mark()
  }

  def apimSyntheticCheck(environment: String, metricName: String, issue: String): Unit =
    apimCheckStatusMetric(environment, metricName, issue).mark()

}

object MetricsService {

  object MetricsKeys {

    object APIM {

      val apimDeploymentStatusUnknown: String = "apim-deployment-status-unknown"

      val apimSyntheticMonitoringMetric: String = "synthetic"

    }

  }

}

