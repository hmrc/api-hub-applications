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

package uk.gov.hmrc.apihubapplications.models.api

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.apihubapplications.models.{Enumerable, WithName}

sealed trait ApiStatus

case object Alpha extends WithName("ALPHA") with ApiStatus
case object Beta extends WithName("BETA") with ApiStatus
case object Live extends WithName("LIVE") with ApiStatus
case object Deprecated extends WithName("DEPRECATED") with ApiStatus

object ApiStatus extends Enumerable.Implicits {

  val values: Seq[ApiStatus] = Seq(Alpha, Beta, Live, Deprecated)

  implicit val enumerable: Enumerable[ApiStatus] =
    Enumerable(values.map(value => value.toString -> value)*)

}

sealed trait ApiType

case object SIMPLE extends WithName("SIMPLE") with ApiType
case object ADVANCED extends WithName("ADVANCED") with ApiType

object ApiType extends Enumerable.Implicits {

  val values: Seq[ApiType] = Seq(SIMPLE, ADVANCED)

  implicit val enumerable: Enumerable[ApiType] =
    Enumerable(values.map(value => value.toString -> value)*)
}

sealed trait ApiGeneration

object ApiGeneration extends Enumerable.Implicits {

  val values: Seq[ApiGeneration] = Seq(V1, V2)

  implicit val enumerable: Enumerable[ApiGeneration] =
    Enumerable(values.map(value => value.toString -> value)*)

  case object V1 extends WithName("V1") with ApiGeneration
  case object V2 extends WithName("V2") with ApiGeneration
}

case class ApiDetail(
  id: String,
  publisherReference: String,
  title: String,
  description: String,
  platform: String,
  version: String,
  endpoints: Seq[Endpoint],
  shortDescription: Option[String],
  openApiSpecification: String,
  apiStatus: ApiStatus,
  teamId: Option[String] = None,
  domain: Option[String] = None,
  subDomain: Option[String] = None,
  hods: Seq[String] = List.empty,
  apiType: Option[ApiType] = None,
  apiGeneration: Option[ApiGeneration] = None,
) {

  def toSummary: ApiDetailSummary = ApiDetailSummary(this)

}

object ApiDetail {

  implicit val formatApiDetail: Format[ApiDetail] = Json.format[ApiDetail]

}
