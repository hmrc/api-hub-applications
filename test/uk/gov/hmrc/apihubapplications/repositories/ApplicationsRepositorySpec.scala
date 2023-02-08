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
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository.mongoApplicationFormat

import java.time.LocalDateTime

class ApplicationsRepositorySpec
  extends AnyFreeSpec
  with Matchers {

  "JSON serialisation and deserialisation" - {
    "must successfully deserialise JSON to create an Application object" in {
      val now = LocalDateTime.now()
      val json = Json.parse(
        s"""
           |{
           |"lastUpdated":"${now.toString}",
           |"createdBy":{"email":"test1@test.com"},
           |"environments":{
           |  "dev":{"scopes":[],"credentials":[]},
           |  "test":{"scopes":[],"credentials":[]},
           |  "preProd":{"scopes":[],"credentials":[]},
           |  "prod":{"scopes":[],"credentials":[]}},
           |"created":"${now.toString}",
           |"name":"test-app-1",
           |"_id":{"$$oid":"63bebf8bbbeccc26c12294e5"},
           |"teamMembers":[]
           |}
           |""".stripMargin)

      val result = json.validate(mongoApplicationFormat)
      result mustBe a [JsSuccess[_]]

      val expected = Application(Some("63bebf8bbbeccc26c12294e5"), "test-app-1", now, Creator("test1@test.com"), now, Seq.empty, Environments())

      result.get mustBe expected
    }

    "must successfully serialise an Application with an Id" in {
      val now = LocalDateTime.now()
      val application = Application(Some("63bebf8bbbeccc26c12294e5"), "test-app-1", now, Creator("test1@test.com"), now, Seq.empty, Environments())

      val result = Json.toJson(application)(mongoApplicationFormat)
      (result \ "id") mustBe a [JsUndefined]
      (result \ "_id" \ "$oid") mustBe JsDefined(JsString("63bebf8bbbeccc26c12294e5"))
      (result \ "name") mustBe JsDefined(JsString("test-app-1"))
    }

    "must successfully serialise an Application without an Id" in {
      val application = Application(NewApplication("test-app-without-id",Creator("test1@test.com")))

      val result = Json.toJson(application)(mongoApplicationFormat)
      (result \ "id") mustBe a [JsUndefined]
      (result \ "_id") mustBe a [JsUndefined]
      (result \ "name") mustBe JsDefined(JsString("test-app-without-id"))
    }

    "must successfully serialise a collection of new scopes" in {
      val newScopes = Seq(NewScope("scope1", Seq(Dev,Test)), NewScope("scope2", Seq(Prod)))
      val result = Json.toJson(newScopes)
      (result \ 0 \"name") mustBe JsDefined(JsString("scope1"))
      (result \ 0 \"environments" \ 0) mustBe JsDefined(JsString("dev"))
      (result \ 0 \"environments" \ 1) mustBe JsDefined(JsString("test"))
      (result \ 1 \"name") mustBe JsDefined(JsString("scope2"))
      (result \ 1 \"environments" \ 0) mustBe JsDefined(JsString("prod"))
    }

  }

}
