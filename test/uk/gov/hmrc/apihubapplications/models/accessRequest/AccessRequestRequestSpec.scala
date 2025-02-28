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

package uk.gov.hmrc.apihubapplications.models.accessRequest

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses._

import java.time.{Clock, Instant, LocalDateTime, ZoneId}

class AccessRequestRequestSpec extends AnyFreeSpec with Matchers {

  private val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

  "AccessRequestRequest.toAccessRequests" - {
    "must correctly split an incoming request into a list of access requests" in {
      val api1 = buildAccessRequestApi(1)
      val api2 = buildAccessRequestApi(2)

      val request = AccessRequestRequest(
        applicationId = "test-application-id",
        supportingInformation = "test-supporting-information",
        requestedBy = "test-requested-by",
        apis = Seq(api1, api2),
        environmentId = "test"
      )

      val expected = Seq(
        buildExpectedAccessRequest(request, api1),
        buildExpectedAccessRequest(request, api2)
      )

      val actual = request.toAccessRequests(clock)

      actual mustBe expected
    }
  }

  private def buildAccessRequestApi(apiIndex: Int): AccessRequestApi = {

    AccessRequestApi(
      apiId = s"test-api-id-$apiIndex",
      apiName = s"test-api-name-$apiIndex",
      endpoints = Seq(
        buildAccessRequestEndpoint(apiIndex, 1),
        buildAccessRequestEndpoint(apiIndex, 2)
      )
    )

  }

  private def buildAccessRequestEndpoint(apiIndex: Int, endpointIndex: Int): AccessRequestEndpoint = {

    AccessRequestEndpoint(
      httpMethod = s"test-http-method-$apiIndex-$endpointIndex",
      path = s"test-path-$apiIndex-$endpointIndex",
      scopes = Seq(s"test-scope-$apiIndex-$endpointIndex-1", s"test-scope-$apiIndex-$endpointIndex-1")
    )

  }

  private def buildExpectedAccessRequest(request: AccessRequestRequest, api: AccessRequestApi): AccessRequest = {

    AccessRequest(
      applicationId = request.applicationId,
      apiId = api.apiId,
      apiName = api.apiName,
      status = Pending,
      supportingInformation = request.supportingInformation,
      requested = LocalDateTime.now(clock),
      requestedBy = request.requestedBy,
      environmentId = request.environmentId
    ).setEndpoints(api.endpoints)

  }

}
