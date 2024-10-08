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

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.repositories.models.application.unencrypted.DbApi.API_NAME_UNKNOWN

import java.time.LocalDateTime

case class DbApi(id: String, title: Option[String], endpoints: Seq[Endpoint] = Seq.empty) {
  def toModel: Api =
    Api(
      id = id,
      title = title.getOrElse(API_NAME_UNKNOWN),
      endpoints = endpoints,
    )
}

object DbApi {
  val API_NAME_UNKNOWN = "API name unknown"
  
  def apply(api: Api): DbApi =
    DbApi(
      id = api.id,
      title = api.title match {
        case API_NAME_UNKNOWN => None
        case title => Some(title)
      },
      endpoints = api.endpoints,
    )

  implicit val formatDbApi: Format[DbApi] = Json.format[DbApi]

}
