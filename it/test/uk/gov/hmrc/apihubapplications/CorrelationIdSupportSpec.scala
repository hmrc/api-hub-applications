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

package uk.gov.hmrc.apihubapplications

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.matching.EqualToPattern
import org.apache.pekko.actor.ActorSystem
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.http.Status.{NOT_FOUND, OK}
import uk.gov.hmrc.apihubapplications.connectors.CorrelationIdSupport
import uk.gov.hmrc.http.HttpReads.Implicits as HttpReadImplicits
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.http.{HeaderCarrier, RequestId}

import java.net.URL
import scala.concurrent.ExecutionContext.Implicits.global

class CorrelationIdSupportSpec extends AnyFreeSpec
  with Matchers
  with WireMockSupport
  with HttpClientV2Support
  with ScalaFutures {

  "CorrelationIdSupport" - {
    val correlationIdSupport = new CorrelationIdSupport {}
    import correlationIdSupport.CorrelationIdSupport.*
    given as: ActorSystem = ActorSystem("test-actor-system")
    val baseUrl = URL(s"http://${wireMockHost}:${wireMockPort}")
    val requestId = RequestId("request-id")

    "include the correlation id when a request id header exists" - {
      given hc: HeaderCarrier = HeaderCarrier(requestId = Some(requestId))
      "in the request context details" in {
        val context = Seq[(String, AnyRef)]().withCorrelationId()

        context mustBe Seq((correlationIdHeader -> requestId.value))
      }
      "in a request" in {
        val requestBuilder = httpClientV2.get(baseUrl).withCorrelationId()

        stubFor(
          get(urlEqualTo("/"))
            .withHeader(correlationIdHeader, EqualToPattern(requestId.value, true))
            .willReturn(
              aResponse()
                .withStatus(200)
            )
        )

        val response = requestBuilder.execute(using HttpReadImplicits.readRaw) .futureValue

        response.status mustBe OK
      }
    }
    "not include a correlation id when a request id header does not exist" - {
      given hc: HeaderCarrier = HeaderCarrier()
      "in the request context details" in {
        val context = Seq[(String, AnyRef)]().withCorrelationId()

        context mustBe Seq.empty
      }
      "in a request" in {
        val requestBuilder = httpClientV2.get(baseUrl).withCorrelationId()

        stubFor(
          get(urlEqualTo("/"))
            .withHeader(correlationIdHeader, EqualToPattern(requestId.value, true))
            .willReturn(
              aResponse()
                .withStatus(200)
            )
        )

        val response = requestBuilder.execute(using HttpReadImplicits.readRaw) .futureValue

        response.status mustBe NOT_FOUND
      }
    }
  }
}
