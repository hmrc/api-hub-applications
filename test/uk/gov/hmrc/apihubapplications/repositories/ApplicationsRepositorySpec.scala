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
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.repositories.models.MongoIdentifier._
import uk.gov.hmrc.apihubapplications.repositories.models.application.encrypted.SensitiveApplication
import uk.gov.hmrc.apihubapplications.repositories.models.application.unencrypted.DbApplication
import uk.gov.hmrc.crypto.{Decrypter, Encrypter}

import java.time.LocalDateTime

class ApplicationsRepositorySpec
  extends AnyFreeSpec
    with Matchers {

  private implicit val crypto: Encrypter & Decrypter = NoCrypto
  private implicit val formatSensitiveApplication: Format[SensitiveApplication] = SensitiveApplication.formatSensitiveApplication

  "JSON serialisation and deserialisation" - {
    "must successfully deserialise JSON without apis to create an Application object" in {
      val now = LocalDateTime.now()
      val json = Json.parse(
        s"""
           |{
           |"lastUpdated":"${now.toString}",
           |"createdBy":{"email": "\\"test1@test.com\\""},
           |"environments":{
           |  "primary":{"scopes":[],"credentials":[]},
           |  "secondary":{"scopes":[],"credentials":[]}
           |},
           |"created":"${now.toString}",
           |"name":"test-app-1",
           |"_id":{"$$oid":"63bebf8bbbeccc26c12294e5"},
           |"teamMembers":[{"email": "\\"test2@test.com\\""}]
           |}
           |""".stripMargin)

      val result = json.validate(formatDataWithMongoIdentifier[SensitiveApplication])
      result mustBe a[JsSuccess[?]]

      val expected = Application(
        Some("63bebf8bbbeccc26c12294e5"),
        "test-app-1",
        now,
        Creator("test1@test.com"),
        now,
        Seq(TeamMember("test2@test.com")),
        Environments()
      )

      result.get.decryptedValue.toModel mustBe expected
    }

    "must successfully deserialise JSON with apis to create an Application object" in {
      val now = LocalDateTime.now()
      val json = Json.parse(
        s"""
           |{
           |"lastUpdated":"${now.toString}",
           |"createdBy":{"email": "\\"test1@test.com\\""},
           |"environments":{
           |  "primary":{"scopes":[],"credentials":[]},
           |  "secondary":{"scopes":[],"credentials":[]}
           |},
           |"created":"${now.toString}",
           |"name":"test-app-1",
           |"_id":{"$$oid":"63bebf8bbbeccc26c12294e5"},
           |"teamMembers":[{"email": "\\"test2@test.com\\""}],
           |"apis": [
           |    {
           |      "id": "63bebf8bbbeccc26c12294e6",
           |      "title": "my-api",
           |      "endpoints": [
           |        {
           |          "httpMethod": "GET",
           |          "path": "/foo/bar"
           |        }
           |      ]
           |    }
           |  ]
           |}
           |""".stripMargin)

      val result = json.validate(formatDataWithMongoIdentifier[SensitiveApplication])
      result mustBe a[JsSuccess[?]]

      val anEndpoint = Endpoint("GET", "/foo/bar")
      val anApi = Api("63bebf8bbbeccc26c12294e6", "my-api", Seq(anEndpoint))

      val expected = Application(
        Some("63bebf8bbbeccc26c12294e5"),
        "test-app-1",
        now,
        Creator("test1@test.com"),
        now,
        Seq(TeamMember("test2@test.com")),
        Environments()
      ).copy(apis = Seq(anApi))

      result.get.decryptedValue.toModel mustBe expected
    }

    "must successfully serialise an Application with an Id" in {
      val now = LocalDateTime.now()
      val application = SensitiveApplication(DbApplication(Application(Some("63bebf8bbbeccc26c12294e5"), "test-app-1", now, Creator("test1@test.com"), now, Seq.empty, Environments())))

      val result = Json.toJson(application)(formatDataWithMongoIdentifier[SensitiveApplication])
      (result \ "id") mustBe a[JsUndefined]
      (result \ "_id" \ "$oid") mustBe JsDefined(JsString("63bebf8bbbeccc26c12294e5"))
      (result \ "name") mustBe JsDefined(JsString("test-app-1"))
    }

    "must successfully serialise an Application without an Id" in {
      val application = SensitiveApplication(DbApplication(Application(NewApplication("test-app-without-id", Creator("test1@test.com"), Seq(TeamMember("test1@test.com"))))))

      val result = Json.toJson(application)(SensitiveApplication.formatSensitiveApplication(NoCrypto))
      (result \ "id") mustBe a[JsUndefined]
      (result \ "_id") mustBe a[JsUndefined]
      (result \ "name") mustBe JsDefined(JsString("test-app-without-id"))
    }

    "must strip out the issues sequence while serialising an application without an Id" in {
      val application = SensitiveApplication(DbApplication(Application(None, "test-app-1", Creator("test1@test.com"), Seq.empty)
        .setIssues(Seq("test-issue"))))

      val result = Json.toJson(application)(SensitiveApplication.formatSensitiveApplication(NoCrypto))
      (result \ "issues") mustBe a[JsUndefined]
    }

    "must strip out the issues sequence while serialising an application with an Id" in {
      val application = SensitiveApplication(DbApplication(Application(Some("test-id"), "test-app-1", Creator("test1@test.com"), Seq.empty)
        .setIssues(Seq("test-issue"))))

      val result = Json.toJson(application)(SensitiveApplication.formatSensitiveApplication(NoCrypto))
      (result \ "issues") mustBe a[JsUndefined]
    }
  }

}
