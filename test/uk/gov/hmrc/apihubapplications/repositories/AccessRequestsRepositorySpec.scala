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

package uk.gov.hmrc.apihubapplications.repositories

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json._
import uk.gov.hmrc.apihubapplications.crypto.NoCrypto
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses._
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, Pending}
import uk.gov.hmrc.apihubapplications.repositories.models.MongoIdentifier._
import uk.gov.hmrc.apihubapplications.repositories.models.accessRequest.encrypted.SensitiveAccessRequest
import uk.gov.hmrc.crypto.{Decrypter, Encrypter}

import java.time.LocalDateTime

class AccessRequestsRepositorySpec extends AnyFreeSpec with Matchers {

  private implicit val crypto: Encrypter & Decrypter = NoCrypto
  private implicit val formatSensitiveAccessRequest: Format[SensitiveAccessRequest] = SensitiveAccessRequest.formatSensitiveAccessRequest

  "AccessRequestsRepository" - {
    "must serialize an access request without an Id" in {
      val accessRequest = SensitiveAccessRequest(
        AccessRequest(
          applicationId = "test-application-id",
          apiId = "test-api-id",
          apiName = "test-api-name",
          status = Pending,
          supportingInformation = "test-supporting-information",
          requested = LocalDateTime.now(),
          requestedBy = "test-requested-by",
          environmentId = "test"
        )
      )

      val json = Json.toJson(accessRequest)(formatDataWithMongoIdentifier[SensitiveAccessRequest])

      (json \ "id") mustBe a[JsUndefined]
      (json \ "_id" \ "$oid") mustBe a[JsUndefined]
    }

    "must serialize an access request with an Id" in {
      val accessRequest = SensitiveAccessRequest(
        AccessRequest(
          applicationId = "test-application-id",
          apiId = "test-api-id",
          apiName = "test-api-name",
          status = Pending,
          supportingInformation = "test-supporting-information",
          requested = LocalDateTime.now(),
          requestedBy = "test-requested-by",
          environmentId = "test"
        ).setId("63bebf8bbbeccc26c12294e5")
      )

      val json = Json.toJson(accessRequest)(formatDataWithMongoIdentifier[SensitiveAccessRequest])

      (json \ "id") mustBe a[JsUndefined]
      (json \ "_id" \ "$oid") mustBe JsDefined(JsString("63bebf8bbbeccc26c12294e5"))
    }

    "must correctly deserialize an access request's Id" in {
      val json = Json.parse(
        """
          |{
          |  "_id" : {
          |    "$oid" : "63bebf8bbbeccc26c12294e5"
          |  },
          |  "applicationId" : "test-application-id",
          |  "apiId" : "test-api-id",
          |  "apiName" : "test-api-name",
          |  "status" : "PENDING",
          |  "supportingInformation" : "test-supporting-information",
          |  "requested" : "2023-11-08T14:18:04.09041",
          |  "requestedBy" : "\"test-requested-by\"",
          |  "endpoints" : [],
          |  "environmentId" : "test"
          |}
          |""".stripMargin
      )

      val result = json.validate(formatDataWithMongoIdentifier[SensitiveAccessRequest])
      result mustBe a[JsSuccess[?]]
      result.get.id mustBe Some("63bebf8bbbeccc26c12294e5")
    }
  }

}
