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

import com.google.inject.{Inject, Singleton}
import org.bson.types.ObjectId
import org.mongodb.scala.model.Filters
import org.mongodb.scala.model.Updates.{addEachToSet, addToSet, combine, pushEach, set}
import play.api.Logging
import play.api.libs.json._
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository.{mongoApplicationFormat, stringToObjectId}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationsRepository @Inject()
  (mongoComponent: MongoComponent)
  (implicit ec: ExecutionContext)
  extends PlayMongoRepository[Application](
    collectionName = "applications",
    mongoComponent = mongoComponent,
    domainFormat   = mongoApplicationFormat,
    extraCodecs = Seq(Codecs.playFormatCodec(Scope.scopeFormat)),
    indexes        = Seq.empty
  ) {

  def findAll():Future[Seq[Application]] = collection.find().toFuture()

  def findById(id: String): Future[Option[Application]] = {
    stringToObjectId(id) match {
      case Some(objectId) =>
        collection
          .find (Filters.equal ("_id", objectId ) )
          .headOption ()
      case None => Future.successful(None)
    }
  }

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

  def addScopes(applicationId:String, newScopes: Seq[NewScope]): Future[Option[Boolean]] = {
    stringToObjectId(applicationId) match {
      case Some(appIdObject) =>
             val envScopes: Seq[(EnvironmentName, String)] = newScopes.foldLeft(Seq[(EnvironmentName,String)])((m, sc) => m++sc.environments.map(env => (env,sc.name)))
             val updates = envScopes.groupBy(_._1).map(kv => {
                val (env, scopes) = (kv._1, kv._2.map(newScope => kv._1 match{
                  case Prod => Scope(newScope._2, Pending)
                  case _ => Scope(newScope._2, Approved)
                }))
                addEachToSet(f"environments.$env.scopes", scopes: _*)
             }).toSeq:+set("lastUpdated", LocalDateTime.now().toString)
              collection.updateOne(
                Filters.equal("_id", appIdObject),
                combine(updates: _*) //
              ).toFuture().map(res => {
                if (res.getMatchedCount==1 && res.getModifiedCount==1)
                  Some(true)
                else
                  Some(false)
              })
      case None => Future.successful(None)
    }
  }

}

object ApplicationsRepository extends Logging {

  /*
    We have a JSON serializer/deserializer for the Application case class. This
    reads and writes the "id" element. If its value is None then it is not output.

    Mongo wants the "id" element to be called "_id" with this structure:
      "_id" : {
        "$oid" : "63bebf8bbbeccc26c12294e5"
      }

    The gibberish string is a hex string based on Mongo's internal Id value.

    When we read/write JSON with Mongo we need to transform between the "id"
    and "_id" structures:
      1) When reading from Mongo we transform from "_id" to "id" and then use
         our standard reads to deserialize to an Application object
      2) When writing to Mongo we need to serialize an Application object using
         our standard writes and then transform from "id" to "_id"

    Read about Play's JSON transformers here:
      https://www.playframework.com/documentation/2.8.x/ScalaJsonTransformers

    One problem wth transformers is that they don't work well with optional
    elements. We only want to apply our write transform when we have Some(id).
    When we have None we can use the standard write which simply omits id's
    element.
   */

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

  def stringToObjectId(id: String): Option[ObjectId] = {
    try {
      Some(new ObjectId(id))
    }
    catch {
      case _: IllegalArgumentException =>
        logger.debug(s"Invalid ObjectId specified: $id")
        None
    }
  }

}
