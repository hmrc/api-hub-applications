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
import uk.gov.hmrc.apihubapplications.models.Application
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository.mongoApplicationFormat

class ApplicationsRepositorySpec
  extends AnyFreeSpec
  with Matchers {

  "JSON serialisation and deserialisation" - {
    "must successfully deserialise JSON to create an Application object" in {
      val json = Json.parse(
        """
          |{
          |  "_id" : {
          |    "$oid" : "63bebf8bbbeccc26c12294e5"
          |  },
          |  "name" : "test-app"
          |}
          |""".stripMargin)

      val result = json.validate(mongoApplicationFormat)
      result mustBe a [JsSuccess[_]]

      val expected = Application(Some("63bebf8bbbeccc26c12294e5"), "test-app")
      result.get mustBe expected
    }

    "must successfully serialise an Application with an Id" in {
      val application = Application(Some("63bebf8bbbeccc26c12294e5"), "test-app-with-id")

      val result = Json.toJson(application)(mongoApplicationFormat)
      (result \ "id") mustBe a [JsUndefined]
      (result \ "_id" \ "$oid") mustBe JsDefined(JsString("63bebf8bbbeccc26c12294e5"))
      (result \ "name") mustBe JsDefined(JsString("test-app-with-id"))
    }

    "must successfully serialise an Application without an Id" in {
      val application = Application(None, "test-app-without-id")

      val result = Json.toJson(application)(mongoApplicationFormat)
      (result \ "id") mustBe a [JsUndefined]
      (result \ "_id") mustBe a [JsUndefined]
      (result \ "name") mustBe JsDefined(JsString("test-app-without-id"))
    }

  }

}