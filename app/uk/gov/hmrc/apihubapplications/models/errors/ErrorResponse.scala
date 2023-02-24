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

package uk.gov.hmrc.apihubapplications.models.errors

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.apihubapplications.models.Enumerable

sealed trait RequestError

case object InvalidJson extends RequestError

case object ApplicationNameNotUnique extends RequestError

object RequestError extends Enumerable.Implicits {

  val values: Seq[RequestError] = Seq(
    InvalidJson,
    ApplicationNameNotUnique
  )

  implicit val enumerable: Enumerable[RequestError] =
    Enumerable(values.map(value => value.toString -> value): _*)

  def isRecoverable(requestError: RequestError): Boolean = {
    Set[RequestError](
      ApplicationNameNotUnique
    ).contains(requestError)
  }

}

case class ErrorResponse(reason: RequestError, description: String)

object ErrorResponse {

  implicit val formatErrorResponse: Format[ErrorResponse] = Json.format[ErrorResponse]

}
