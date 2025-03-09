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

import com.typesafe.config.ConfigFactory
import org.mockito.Mockito.{verify, verifyNoMoreInteractions, when}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.models.apim.DeploymentDetails
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, IdmsException}
import uk.gov.hmrc.apihubapplications.services.MetricsService
import uk.gov.hmrc.apihubapplications.services.ApplicationsService
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ApimSyntheticMonitoringSchedulerSpec extends AnyFreeSpec
  with Matchers
  with MockitoSugar
  with ScalaFutures {

  private val publisherReference = "test-reference"
  private val environment = "test"
  private implicit val hc: HeaderCarrier = HeaderCarrier()

  "ApimSyntheticMonitoringScheduler should" - {
    "record metrics on successful APIM checks" in {
      val fixture = buildFixture()

      when(fixture.apimConnector.getDeployment(publisherReference, FakeHipEnvironments.testEnvironment)(hc))
        .thenReturn(Future.successful(Right(None)))
      when(fixture.apimConnector.getDeploymentDetails(publisherReference)(hc))
        .thenReturn(Future.successful(Right(DeploymentDetails(None, None, None, None, None, None, Seq.empty, None))))
      when(fixture.apimConnector.listEgressGateways(FakeHipEnvironments.testEnvironment)(hc))
        .thenReturn(Future.successful(Right(Seq.empty)))
      when(fixture.idmsConnector.fetchClientScopes(FakeHipEnvironments.testEnvironment, FakeHipEnvironments.testEnvironment.clientId)(hc))
        .thenReturn(Future.successful(Right(Seq.empty)))

      fixture.apimSyntheticMonitoringScheduler.run().futureValue

      verify(fixture.apimConnector).getDeployment(publisherReference, FakeHipEnvironments.testEnvironment)(hc)
      verify(fixture.apimConnector).getDeploymentDetails(publisherReference)(hc)
      verify(fixture.apimConnector).listEgressGateways(FakeHipEnvironments.testEnvironment)(hc)
      verifyNoMoreInteractions(fixture.apimConnector)
      verify(fixture.idmsConnector).fetchClientScopes(FakeHipEnvironments.testEnvironment, FakeHipEnvironments.testEnvironment.clientId)(hc)
      verifyNoMoreInteractions(fixture.idmsConnector)

      verify(fixture.metricsService).apimSyntheticCheck(environment, "getDeployment", "success")
      verify(fixture.metricsService).apimSyntheticCheck(environment, "getDeploymentDetails", "success")
      verify(fixture.metricsService).apimSyntheticCheck(environment, "listEgressGateways", "success")
      verify(fixture.metricsService).apimSyntheticCheck(environment, "fetchClientScopes", "success")
      verifyNoMoreInteractions(fixture.metricsService)
    }

    "record metrics on unsuccessful APIM checks" in {
      val fixture = buildFixture()

      when(fixture.apimConnector.getDeployment(publisherReference, FakeHipEnvironments.testEnvironment)(hc))
        .thenReturn(Future.successful(Left(ApimException("message", new RuntimeException, ApimException.UnexpectedResponse))))
      when(fixture.apimConnector.getDeploymentDetails(publisherReference)(hc))
        .thenReturn(Future.successful(Left(ApimException("message", new RuntimeException, ApimException.InvalidResponse))))
      when(fixture.apimConnector.listEgressGateways(FakeHipEnvironments.testEnvironment)(hc))
        .thenReturn(Future.successful(Left(ApimException("message", new RuntimeException, ApimException.ServiceNotFound))))
      when(fixture.idmsConnector.fetchClientScopes(FakeHipEnvironments.testEnvironment, FakeHipEnvironments.testEnvironment.clientId)(hc))
        .thenReturn(Future.successful(Left(IdmsException("message", new RuntimeException, IdmsException.ClientNotFound))))

      fixture.apimSyntheticMonitoringScheduler.run().futureValue

      verify(fixture.apimConnector).getDeployment(publisherReference, FakeHipEnvironments.testEnvironment)(hc)
      verify(fixture.apimConnector).getDeploymentDetails(publisherReference)(hc)
      verify(fixture.apimConnector).listEgressGateways(FakeHipEnvironments.testEnvironment)(hc)
      verifyNoMoreInteractions(fixture.apimConnector)
      verify(fixture.idmsConnector).fetchClientScopes(FakeHipEnvironments.testEnvironment, FakeHipEnvironments.testEnvironment.clientId)(hc)
      verifyNoMoreInteractions(fixture.idmsConnector)

      verify(fixture.metricsService).apimSyntheticCheck(environment, "getDeployment", "unexpectedresponse")
      verify(fixture.metricsService).apimSyntheticCheck(environment, "getDeploymentDetails", "invalidresponse")
      verify(fixture.metricsService).apimSyntheticCheck(environment, "listEgressGateways", "servicenotfound")
      verify(fixture.metricsService).apimSyntheticCheck(environment, "fetchClientScopes", "clientnotfound")
      verifyNoMoreInteractions(fixture.metricsService)
    }
  }

  private case class Fixture(
                              apimSyntheticMonitoringScheduler: ApimSyntheticMonitoringScheduler,
                              apimConnector: APIMConnector,
                              idmsConnector: IdmsConnector,
                              metricsService: MetricsService,
                              schedulerConfigs: SchedulerConfigs,
                            )

  private def buildFixture(): Fixture = {
    val apimConnector = mock[APIMConnector]
    val idmsConnector = mock[IdmsConnector]
    val metricsService = mock[MetricsService]
    val schedulerConfigs = SchedulerConfigs(
      Configuration(ConfigFactory.parseString(
        s"""
           |apimSyntheticMonitoringScheduler {
           |  enabled      = true
           |  interval     = 1.minutes
           |  initialDelay = 0.second
           |  additionalConfiguration {
           |    hipEnvironment = "$environment"
           |    publisherReference = "$publisherReference"
           |  }
           |}
           |""".stripMargin))
    )
    val hipEnvironments = FakeHipEnvironments
    val apimSyntheticMonitoringScheduler = ApimSyntheticMonitoringScheduler(
      apimConnector,
      idmsConnector,
      metricsService,
      schedulerConfigs,
      hipEnvironments,
    )
    Fixture(
      apimSyntheticMonitoringScheduler,
      apimConnector,
      idmsConnector,
      metricsService,
      schedulerConfigs,
    )
  }
}
