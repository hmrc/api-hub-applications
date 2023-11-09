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

package uk.gov.hmrc.apihubapplications.repositories.models

import play.api.libs.json.{Format, JsPath, Reads, Writes}

trait MongoIdentifier {

  def id: Option[String]

}

object MongoIdentifier {

  private def writesDataWithId[T](implicit formatT: Format[T]): Writes[T] = {
    formatT.transform(
      json => json.transform(
        JsPath.json.update((JsPath \ "_id" \ "$oid").json.copyFrom((JsPath \ "id").json.pick))
          andThen (JsPath \ "id").json.prune
      ).get
    )
  }

  private def writesDataWithOptionalId[T <: MongoIdentifier](implicit formatT: Format[T]): Writes[T] = {
    (t: T) => {
      t.id match {
        case Some(_) => writesDataWithId.writes(t)
        case _ => formatT.writes(t)
      }
    }
  }

  private def readsData[T](implicit formatT: Format[T]): Reads[T] = {
    JsPath.json.update((JsPath \ "id").json
      .copyFrom((JsPath \ "_id" \ "$oid").json.pick))
      .andThen(formatT)
  }

  implicit def formatDataWithMongoIdentifier[T <: MongoIdentifier](implicit formatT: Format[T]): Format[T] = {
    Format(readsData, writesDataWithOptionalId)
  }

}
