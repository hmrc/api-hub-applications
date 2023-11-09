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

package uk.gov.hmrc.apihubapplications

import org.scalatest.OptionValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses._
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, Approved}
import uk.gov.hmrc.apihubapplications.repositories.AccessRequestsRepository
import uk.gov.hmrc.apihubapplications.repositories.models.accessRequest.encrypted.SensitiveAccessRequest
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext

class AccessRequestsRepositoryIntegrationSpec
  extends AsyncFreeSpec
  with Matchers
  with DefaultPlayMongoRepositorySupport[SensitiveAccessRequest]
  with OptionValues
  with MdcTesting {

  private lazy val playApplication = {
    new GuiceApplicationBuilder()
      .overrides(bind[MongoComponent].toInstance(mongoComponent))
      .build()
  }

  override implicit lazy val executionContext: ExecutionContext = {
    playApplication.injector.instanceOf[ExecutionContext]
  }

  override protected val repository: AccessRequestsRepository = {
    playApplication.injector.instanceOf[AccessRequestsRepository]
  }

  "insert" - {
    "must persist a list of production access requests in the MongoDb collection" in  {
      setMdcData()

      val accessRequestWithFullAttributes = AccessRequest(
        applicationId = "test-application-id-1",
        apiId = "test-api-id-1",
        apiName = "test-api-name-1",
        status = Approved,
        supportingInformation = "test-supporting-information-1",
        requested = LocalDateTime.now().minusDays(1),
        requestedBy = "test-requested-by-1"
      )
        .addEndpoint(
          httpMethod = "test-http-method-1",
          path = "test-path-1",
          scopes = Seq("test-scope-1-1", "test-scope-1-2")
        )
        .addEndpoint(
          httpMethod = "test-http-method-2",
          path = "test-path-2",
          scopes = Seq("test-scope-2-1", "test-scope-2-2")
        )
        .setDecision(
          decided = LocalDateTime.now(),
          decidedBy = "test-decided-by",
          rejectedReason = "test-denied-reason"
        )

      val accessRequestWithMinimalAttributes = AccessRequest(
        applicationId = "test-application-id-2",
        apiId = "test-api-id-2",
        apiName = "test-api-name-2",
        status = Approved,
        supportingInformation = "test-supporting-information-2",
        requested = LocalDateTime.now().minusDays(2),
        requestedBy = "test-requested-by-2"
      )

      val accessRequests = Seq(accessRequestWithFullAttributes, accessRequestWithMinimalAttributes)

      repository.insert(accessRequests).map(ResultWithMdcData(_)).flatMap {
        result =>
          val expected = accessRequests.map(
            accessRequest =>
              accessRequest.setId(
                result.data.find(_.applicationId == accessRequest.applicationId).value.id
              )
          )

          result.data must contain theSameElementsAs expected
          result.mdcData mustBe testMdcData
      }
    }
  }

}
