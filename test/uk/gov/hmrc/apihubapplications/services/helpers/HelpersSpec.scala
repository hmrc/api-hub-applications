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
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.{CallError, ClientNotFound, UnexpectedResponse}
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationNotFoundException, ApplicationsException, IdmsException}

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

  "useFirstApplicationsException" - {
    "must return a sequence of successes if the input is all successes" in {
      val expected = Seq("test-1", "test-2")
      val input: Seq[Either[ApplicationsException, String]] = expected.map(Right(_))

      val actual = Helpers.useFirstApplicationsException(input)

      actual mustBe Right(expected)
    }

    "must return an empty sequence if the input is empty" in {
      val input: Seq[Either[ApplicationsException, String]] = Seq.empty

      val actual = Helpers.useFirstApplicationsException(input)

      actual mustBe Right(Seq.empty)
    }

    "must return an ApplicationsException given a mix sequence of success and failure" in {
      val expected = ApplicationNotFoundException("test-message")
      val input: Seq[Either[ApplicationsException, String]] =
        Seq(
          Right("test-1"),
          Left(expected),
          Right("test-2")
        )

      val actual = Helpers.useFirstApplicationsException(input)

      actual mustBe Left(expected)
    }
  }

  "ignoreClientNotFound" - {
    "must filter out Left IdmsException values whose issue is client not found" in {

      val unwanted = Left(IdmsException("client not found", null, ClientNotFound))
      val wanted = Left(IdmsException("something else", null, UnexpectedResponse))

      val input = Seq(Right("test-1"), unwanted, Right("test-2"), wanted)

      val actual = Helpers.ignoreClientNotFound(input);
      val expected = Seq(Right("test-1"), Right("test-2"), wanted)
      actual mustBe expected
    }
  }

}
