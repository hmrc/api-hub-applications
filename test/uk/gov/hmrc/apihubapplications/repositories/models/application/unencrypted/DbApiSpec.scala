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

package uk.gov.hmrc.apihubapplications.repositories.models.application.unencrypted

import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps

import java.time.LocalDateTime

class DbApiSpec extends AnyFreeSpec with Matchers with OptionValues {
  
  "DbApi" - {
    "when converting from DbApi to Api" - {
      "copy values correctly" in {
        val dbApi = DbApi("test-id", Some("test-title"), Seq(Endpoint("endpoint-method", "endpoint-path")))
        val api = dbApi.toModel
        
        api.id mustBe dbApi.id
        api.title mustEqual dbApi.title.value
        api.endpoints mustBe dbApi.endpoints
      }

      "assign the correct default title if none is present" in {
        val dbApi = DbApi("test-id", None, Seq(Endpoint("endpoint-method", "endpoint-path")))
        val api = dbApi.toModel
        
        api.title mustEqual "API name unknown"
      }

    }

    "when converting from Api to DbApi" - {
      "copy values correctly" in {
        val api = Api("test-id", "test-title", Seq(Endpoint("endpoint-method", "endpoint-path")))
        val dbApi = DbApi(api)

        dbApi.id mustBe api.id
        dbApi.title.value mustEqual api.title
        dbApi.endpoints mustBe api.endpoints
      }

      "assign None to the title if the default title value is present" in {
        val api = Api("test-id", "API name unknown", Seq(Endpoint("endpoint-method", "endpoint-path")))
        val dbApi = DbApi(api)

        dbApi.title mustEqual None
      }

    }
  }

}
