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

package uk.gov.hmrc.apihubapplications

import com.github.tomakehurst.wiremock.client.WireMock.*
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import play.api.http.Status.{INTERNAL_SERVER_ERROR, NOT_FOUND, NO_CONTENT}
import uk.gov.hmrc.apihubapplications.connectors.{AutopublishConnector, AutopublishConnectorImpl}
import uk.gov.hmrc.apihubapplications.models.exception.AutopublishException
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

class AutopublishConnectorSpec
  extends AsyncFreeSpec
    with Matchers
    with WireMockSupport
    with HttpClientV2Support
    with MockitoSugar {

  import AutopublishConnectorSpec.*

  "forcePublish" - {
    "must place the correct request to auto-publish and return success" in {
      stubFor(
        put(s"/integration-catalogue-autopublish/apis/$publisherReference/publish")
          .willReturn(
            aResponse()
              .withStatus(NO_CONTENT)
          )
      )

      buildConnector().forcePublish(publisherReference).map(
        result =>
          result mustBe Right(())
      )
    }

    "must return AutopublishException (DeploymentNotFound) when the deployment cannot be found" in {
      stubFor(
        put(s"/integration-catalogue-autopublish/apis/$publisherReference/publish")
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )

      buildConnector().forcePublish(publisherReference).map(
        result =>
          result mustBe Left(AutopublishException.deploymentNotFound(publisherReference))
      )
    }

    "must return AutopublishException (UnexpectedResponse) when auto-publish returns an unexpected status" in {
      stubFor(
        put(s"/integration-catalogue-autopublish/apis/$publisherReference/publish")
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
          )
      )

      buildConnector().forcePublish(publisherReference).map(
        result =>
          result mustBe Left(AutopublishException.unexpectedResponse(INTERNAL_SERVER_ERROR))
      )
    }
  }

  private def buildConnector(): AutopublishConnector = {
    val servicesConfig = new ServicesConfig(
      Configuration.from(
        Map(
          "microservice.services.integration-catalogue-autopublish.host" -> wireMockHost,
          "microservice.services.integration-catalogue-autopublish.port" -> wireMockPort,
        )
      )
    )

    new AutopublishConnectorImpl(servicesConfig, httpClientV2)
  }

}

private object AutopublishConnectorSpec {

  val publisherReference = "test-publisher-reference"

  implicit val headerCarrier: HeaderCarrier = HeaderCarrier()

}
