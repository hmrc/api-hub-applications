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

package uk.gov.hmrc.apihubapplications.services.helpers

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.CallError

class HelpersSpec extends AnyFreeSpec with Matchers {

  "useFirstException" - {
    "must return a sequence of successes if the input is all successes" in {
      val expected = Seq("test-1", "test-2")
      val input: Seq[Either[IdmsException, String]] = expected.map(Right(_))

      val actual = Helpers.useFirstException(input)

      actual mustBe Right(expected)
    }

    "must return an empty sequence if the input is empty" in {
      val input: Seq[Either[IdmsException, String]] = Seq.empty

      val actual = Helpers.useFirstException(input)

      actual mustBe Right(Seq.empty)
    }

    "must return an IdmsException given a mix sequence of success and failure" in {
      val expected = IdmsException("test-message", CallError)
      val input: Seq[Either[IdmsException, String]] =
        Seq(
          Right("test-1"),
          Left(expected),
          Right("test-2")
        )

      val actual = Helpers.useFirstException(input)

      actual mustBe Left(expected)
    }
  }

}
