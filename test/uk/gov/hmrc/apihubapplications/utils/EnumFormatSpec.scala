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

package uk.gov.hmrc.apihubapplications.utils

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks.forAll
import org.scalatest.prop.Tables.Table
import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor1}
import play.api.libs.json.Json

class EnumFormatSpec extends AnyFreeSpec
    with Matchers {

  enum ExampleEnum derives EnumFormat:
    case Value1, Value2

  "EnumFormat" - {
    "should serialize enum values" in {
      forAll(enumValues) { (enumValue: ExampleEnum) =>
        Json.prettyPrint(Json.toJson(enumValue)) mustBe s"\"$enumValue\""
      }
    }
    "should deserialize to enum values" in {
      forAll(enumValues) { (enumValue: ExampleEnum) =>
        Json.parse(s"\"$enumValue\"").as[ExampleEnum] mustBe enumValue
      }
    }
  }
  val enumValues: TableFor1[ExampleEnum] = Table(
    "enumValue",
    ExampleEnum.Value1,
    ExampleEnum.Value2
  )

}
