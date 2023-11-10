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
import org.scalatest.prop.TableDrivenPropertyChecks

class AccessRequestStatusSpec extends AnyFreeSpec with Matchers with TableDrivenPropertyChecks {

  "queryStringBindable" - {
    "must correctly bind from valid status names" in {
      val statuses = Table(
        ("name", "status"),
        (Pending.toString, Pending),
        (Pending.toString.toLowerCase, Pending),
        (Approved.toString, Approved),
        (Approved.toString.toLowerCase, Approved),
        (Rejected.toString, Rejected),
        (Rejected.toString.toLowerCase, Rejected)
      )

      forAll(statuses) {(name: String, status: AccessRequestStatus) =>
        val params = Map("sTaTuS" -> Seq(name))
        val actual = AccessRequestStatus.queryStringBindable.bind("test-key", params)
        actual mustBe Some(Right(status))
      }
    }

    "must return None when there is no status query parameter" in {
      val actual = AccessRequestStatus.queryStringBindable.bind("test-key", Map.empty)
      actual mustBe None
    }

    "must return a message when the status name is invalid" in {
      val params = Map("status" -> Seq("DECLINED"))
      val actual = AccessRequestStatus.queryStringBindable.bind("test-key", params)
      actual mustBe Some(Left("Unknown access request status DECLINED"))
    }

    "must return a message when the status name has no value" in {
      val params = Map("status" -> Seq.empty)
      val actual = AccessRequestStatus.queryStringBindable.bind("test-key", params)
      actual mustBe None
    }
  }

}
