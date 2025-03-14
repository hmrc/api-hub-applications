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
import org.apache.pekko.actor.ActorSystem
import org.mockito.Mockito.{verify, verifyNoMoreInteractions, when}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import play.api.inject.{ApplicationLifecycle, DefaultApplicationLifecycle}
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.models.apim.DeploymentDetails
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, ExceptionRaising, IdmsException}
import uk.gov.hmrc.apihubapplications.services.MetricsService
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments
import uk.gov.hmrc.http.{GatewayTimeoutException, HeaderCarrier}
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.lock.MongoLockRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ApimSyntheticMonitoringSchedulerSpec extends AnyFreeSpec
  with Matchers
  with MockitoSugar
  with ScalaFutures
  with ExceptionRaising
  with play.api.Logging {

  private val publisherReference = "test-reference"
  private implicit val hc: HeaderCarrier = HeaderCarrier()

  "ApimSyntheticMonitoringScheduler should" - {
    "record metrics on successful APIM checks" in {
      val fixture = buildFixture()

      FakeHipEnvironments.environments.foreach(hipEnvironment =>
        when(fixture.apimConnector.getDeployment(publisherReference, hipEnvironment)(hc))
          .thenReturn(Future.successful(Right(None)))
        when(fixture.apimConnector.listEgressGateways(hipEnvironment)(hc))
          .thenReturn(Future.successful(Right(Seq.empty)))
        when(fixture.idmsConnector.fetchClientScopes(hipEnvironment, hipEnvironment.clientId)(hc))
          .thenReturn(Future.successful(Right(Seq.empty)))
        when(fixture.apimConnector.getDeploymentDetails(publisherReference, hipEnvironment)(hc))
          .thenReturn(Future.successful(Right(DeploymentDetails(None, None, None, None, None, None, Seq.empty, None))))
      )

      fixture.apimSyntheticMonitoringScheduler.run().futureValue

      FakeHipEnvironments.environments.foreach(hipEnvironment =>
        verify(fixture.apimConnector).getDeployment(publisherReference, hipEnvironment)(hc)
        verify(fixture.apimConnector).listEgressGateways(hipEnvironment)(hc)
        verify(fixture.idmsConnector).fetchClientScopes(hipEnvironment, hipEnvironment.clientId)(hc)
        verify(fixture.apimConnector).getDeploymentDetails(publisherReference, hipEnvironment)(hc)
      )
      verifyNoMoreInteractions(fixture.apimConnector)
      verifyNoMoreInteractions(fixture.idmsConnector)

      FakeHipEnvironments.environments.foreach(hipEnvironment =>
        verify(fixture.metricsService).apimSyntheticCheck(hipEnvironment.id, "getDeployment", "success")
        verify(fixture.metricsService).apimSyntheticCheck(hipEnvironment.id, "listEgressGateways", "success")
        verify(fixture.metricsService).apimSyntheticCheck(hipEnvironment.id, "fetchClientScopes", "success")
        verify(fixture.metricsService).apimSyntheticCheck(hipEnvironment.id, "getDeploymentDetails", "success")
      )
      verifyNoMoreInteractions(fixture.metricsService)
    }

    "record metrics on unsuccessful APIM checks" in {
      val fixture = buildFixture()

      FakeHipEnvironments.environments.foreach(hipEnvironment =>
        when(fixture.apimConnector.getDeployment(publisherReference, hipEnvironment)(hc))
          .thenReturn(Future.successful(Left(ApimException("message", new RuntimeException, ApimException.UnexpectedResponse))))
        when(fixture.apimConnector.listEgressGateways(hipEnvironment)(hc))
          .thenReturn(Future.failed(new RuntimeException))
        when(fixture.idmsConnector.fetchClientScopes(hipEnvironment, hipEnvironment.clientId)(hc))
          .thenReturn(Future.successful(Left(IdmsException("message", new RuntimeException, IdmsException.ClientNotFound))))
        when(fixture.apimConnector.getDeploymentDetails(publisherReference, hipEnvironment)(hc))
          .thenReturn(Future.successful(Left(ApimException("message", new RuntimeException, ApimException.InvalidResponse))))
      )

      fixture.apimSyntheticMonitoringScheduler.run().futureValue

      FakeHipEnvironments.environments.foreach(hipEnvironment =>
        verify(fixture.apimConnector).getDeployment(publisherReference, hipEnvironment)(hc)
        verify(fixture.apimConnector).listEgressGateways(hipEnvironment)(hc)
        verify(fixture.idmsConnector).fetchClientScopes(hipEnvironment, hipEnvironment.clientId)(hc)
        verify(fixture.apimConnector).getDeploymentDetails(publisherReference, hipEnvironment)(hc)
      )
      verifyNoMoreInteractions(fixture.apimConnector)
      verifyNoMoreInteractions(fixture.idmsConnector)

      FakeHipEnvironments.environments.foreach(hipEnvironment =>
        verify(fixture.metricsService).apimSyntheticCheck(hipEnvironment.id, "getDeployment", "error")
        verify(fixture.metricsService).apimSyntheticCheck(hipEnvironment.id, "listEgressGateways", "error")
        verify(fixture.metricsService).apimSyntheticCheck(hipEnvironment.id, "fetchClientScopes", "error")
        verify(fixture.metricsService).apimSyntheticCheck(hipEnvironment.id, "getDeploymentDetails", "error")
      )
      verifyNoMoreInteractions(fixture.metricsService)
    }

    "record metrics on APIM checks that result in timeouts" in {
      val fixture = buildFixture()

      FakeHipEnvironments.environments.foreach(hipEnvironment =>
        when(fixture.apimConnector.getDeployment(publisherReference, hipEnvironment)(hc))
          .thenReturn(Future.successful(Left(ApimException("message", new RuntimeException, ApimException.UnexpectedResponse))))
        when(fixture.apimConnector.listEgressGateways(hipEnvironment)(hc))
          .thenReturn(Future.successful(Left(ApimException("message", new RuntimeException, ApimException.ServiceNotFound))))
        when(fixture.idmsConnector.fetchClientScopes(hipEnvironment, hipEnvironment.clientId)(hc))
          .thenReturn(Future.successful(Left(IdmsException("message", new GatewayTimeoutException("timeout exception"), IdmsException.ClientNotFound))))
        when(fixture.apimConnector.getDeploymentDetails(publisherReference, hipEnvironment)(hc))
          .thenReturn(Future.failed(new GatewayTimeoutException("timeout exception")))
      )

      fixture.apimSyntheticMonitoringScheduler.run().futureValue

      FakeHipEnvironments.environments.foreach(hipEnvironment =>
        verify(fixture.apimConnector).getDeployment(publisherReference, hipEnvironment)(hc)
        verify(fixture.apimConnector).listEgressGateways(hipEnvironment)(hc)
        verify(fixture.idmsConnector).fetchClientScopes(hipEnvironment, hipEnvironment.clientId)(hc)
        verify(fixture.apimConnector).getDeploymentDetails(publisherReference, hipEnvironment)(hc)
      )
      verifyNoMoreInteractions(fixture.apimConnector)
      verifyNoMoreInteractions(fixture.idmsConnector)

      FakeHipEnvironments.environments.foreach(hipEnvironment =>
        verify(fixture.metricsService).apimSyntheticCheck(hipEnvironment.id, "getDeployment", "error")
        verify(fixture.metricsService).apimSyntheticCheck(hipEnvironment.id, "listEgressGateways", "error")
        verify(fixture.metricsService).apimSyntheticCheck(hipEnvironment.id, "fetchClientScopes", "timeout")
        verify(fixture.metricsService).apimSyntheticCheck(hipEnvironment.id, "getDeploymentDetails", "timeout")
      )
      verifyNoMoreInteractions(fixture.metricsService)
    }
  }

  private case class Fixture(
                              apimSyntheticMonitoringScheduler: ApimSyntheticMonitoringScheduler,
                              apimConnector: APIMConnector,
                              idmsConnector: IdmsConnector,
                              metricsService: MetricsService,
                            )

  private def buildFixture(): Fixture = {
    val apimConnector = mock[APIMConnector]
    val idmsConnector = mock[IdmsConnector]
    val metricsService = mock[MetricsService]
    val mongoLockRepository = mock[MongoLockRepository]
    val timestampSupport = mock[TimestampSupport]
    given ActorSystem = ActorSystem()
    given ApplicationLifecycle = DefaultApplicationLifecycle()
    val schedulerConfig = Configuration(ConfigFactory.parseString(
        s"""
           |apimSyntheticMonitoringScheduler {
           |  enabled      = true
           |  interval     = 1.minutes
           |  initialDelay = 0.second
           |  additionalConfiguration {
           |    publisherReference = "$publisherReference"
           |  }
           |}
           |""".stripMargin)
    )
    val hipEnvironments = FakeHipEnvironments
    val apimSyntheticMonitoringScheduler = ApimSyntheticMonitoringScheduler(
      apimConnector,
      idmsConnector,
      metricsService,
      schedulerConfig,
      hipEnvironments,
      mongoLockRepository,
      timestampSupport,
    )
    Fixture(
      apimSyntheticMonitoringScheduler,
      apimConnector,
      idmsConnector,
      metricsService,
    )
  }
}
