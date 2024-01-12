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

import uk.gov.hmrc.play.http.logging.Mdc

trait MdcTesting {

  val testMdcData = Map("X-Request-Id" -> "test-request-id")

  def setMdcData(): Unit = {
    Mdc.putMdc(testMdcData)
  }

  case class ResultWithMdcData[T](data: T, mdcData: Map[String, String])

  object ResultWithMdcData {

    def apply[T](data: T): ResultWithMdcData[T] = {
      ResultWithMdcData(data, Mdc.mdcData)
    }

  }

}
