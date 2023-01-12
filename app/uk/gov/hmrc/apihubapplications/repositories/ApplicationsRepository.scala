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

package uk.gov.hmrc.apihubapplications.repositories

import com.google.inject.Inject
import play.api.libs.json._
import uk.gov.hmrc.apihubapplications.models.Application
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository.mongoApplicationFormat
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import scala.concurrent.{ExecutionContext, Future}

class ApplicationsRepository @Inject()
  (mongoComponent: MongoComponent)
  (implicit ec: ExecutionContext)
  extends PlayMongoRepository[Application](
    collectionName = "applications",
    mongoComponent = mongoComponent,
    domainFormat   = mongoApplicationFormat,
    indexes        = Seq.empty
  ) {

  def insert(application: Application): Future[Application] = {
    collection
      .insertOne(
        document = application
      )
      .toFuture()
      .map(
        result => application.copy(
          id = Some(result.getInsertedId.asObjectId().getValue.toString)
        )
      )
  }

}

object ApplicationsRepository {

  private val mongoApplicationWithIdWrites: Writes[Application] =
    Application.applicationFormat.transform(
      json => json.transform(
        JsPath.json.update((JsPath \ "_id" \ "$oid").json.copyFrom((JsPath \ "id").json.pick))
          andThen (JsPath \ "id").json.prune
      ).get
    )

  private val mongoApplicationWrites: Writes[Application] = (application: Application) => {
    application.id match {
      case Some(_) => mongoApplicationWithIdWrites.writes(application)
      case _ => Application.applicationFormat.writes(application)
    }
  }

  private val mongoApplicationReads: Reads[Application] =
    JsPath.json.update((JsPath \ "id").json
      .copyFrom((JsPath \ "_id" \ "$oid").json.pick))
      .andThen(Application.applicationFormat)

  val mongoApplicationFormat: Format[Application] = Format(mongoApplicationReads, mongoApplicationWrites)

}
