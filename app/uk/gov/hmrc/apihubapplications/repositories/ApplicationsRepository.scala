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
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model._
import play.api.Logging
import play.api.libs.json._
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.models.requests.UpdateScopeStatus
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository.{mongoApplicationFormat, stringToObjectId}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationsRepository @Inject()
  (mongoComponent: MongoComponent)
  (implicit ec: ExecutionContext)
  extends PlayMongoRepository[Application](
    collectionName = "applications",
    mongoComponent = mongoComponent,
    domainFormat   = mongoApplicationFormat,
    extraCodecs = Seq(Codecs.playFormatCodec(Scope.scopeFormat),
                      Codecs.playFormatCodec(UpdateScopeStatus.updateScopeStatusFormat)
                     ),
    indexes = Seq(
      IndexModel(Indexes.ascending("teamMembers", "email")),
      IndexModel(Indexes.ascending("environments.primary.scopes.status"))
    )
  ) with Logging with ExceptionRaising {

  override lazy val requiresTtlIndex = false // There are no requirements to expire applications

  def findAll(): Future[Seq[Application]] = collection.find().toFuture()

  def filter(teamMemberEmail: String): Future[Seq[Application]] = {
    val document = BsonDocument("teamMembers" -> BsonDocument("email" -> teamMemberEmail))
    collection.find(document).toFuture()
  }

  def findById(id: String): Future[Either[ApplicationsException, Application]] = {
    stringToObjectId(id) match {
      case Some(objectId) =>
        collection
          .find(Filters.equal ("_id", objectId ) )
          .headOption()
          .map {
            case Some(application) => Right(application)
            case _ => Left(raiseApplicationNotFoundException.forId(id))
          }
      case None => Future.successful(Left(raiseApplicationNotFoundException.forId(id)))
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

  def update(application: Application): Future[Either[ApplicationsException, Unit]] = {
    stringToObjectId(application.id) match {
      case Some(id) =>
        collection
          .replaceOne(
            filter = Filters.equal("_id", id),
            replacement = application,
            options     = ReplaceOptions().upsert(false)
          )
          .toFuture()
          .map(
            result =>
              if (result.getModifiedCount > 0) {
                Right(())
              }
              else {
                Left(raiseNotUpdatedException.forApplication(application))
              }
          )
      case None => Future.successful(Left(raiseApplicationNotFoundException.forApplication(application)))
    }
  }

  def delete(application: Application): Future[Either[ApplicationsException, Unit]] = {
    stringToObjectId(application.id) match {
      case Some(id) =>
        collection.deleteOne(Filters.equal("_id", id))
          .toFuture()
          .map(
            result =>
              if (result.getDeletedCount != 0) {
                Right(())
              }
              else {
                Left(raiseNotUpdatedException.forApplication(application))
              }
          )
      case None => Future.successful(Left(raiseApplicationNotFoundException.forApplication(application)))
    }
  }

  def countOfAllApplications(): Future[Long] = {
    collection
      .countDocuments()
      .toFuture()
  }

  def countOfPendingApprovals(): Future[Int] = {
    collection
      .aggregate[BsonDocument](
        Seq(
          Aggregates.filter(Filters.equal("environments.primary.scopes.status", Pending.toString)),
          Aggregates.unwind("$environments.primary.scopes"),
          Aggregates.filter(Filters.equal("environments.primary.scopes.status", Pending.toString)),
          Aggregates.count("pendingApprovals")
        )
      )
      .toFuture()
      .map(_.headOption
        .map(bsonDocument =>
          bsonDocument.get("pendingApprovals")
            .asInt32()
            .getValue
        )
      )
      .map(_.getOrElse(0))
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

    An "issues" array exists in Applications that is transient and should not be
    stored. To avoid this happening it is stripped out from an application
    before storage. An empty array is added to applications retrieved from the
    database.
  */

  private val mongoApplicationWithIdWrites: Writes[Application] =
    Application.applicationFormat.transform(
      json => json.transform(
        JsPath.json.update((JsPath \ "_id" \ "$oid").json.copyFrom((JsPath \ "id").json.pick))
          andThen (JsPath \ "id").json.prune
          andThen (JsPath \ "issues").json.prune
      ).get
    )

  private val mongoApplicationWithoutIdWrites: Writes[Application] =
    Application.applicationFormat.transform(
      json => json.transform(
        (JsPath \ "issues").json.prune
      ).get
    )

  private val mongoApplicationWrites: Writes[Application] = (application: Application) => {
    application.id match {
      case Some(_) => mongoApplicationWithIdWrites.writes(application)
      case _ => mongoApplicationWithoutIdWrites.writes(application)
    }
  }

  private val mongoApplicationReads: Reads[Application] =
    JsPath.json.update((JsPath \ "id").json
      .copyFrom((JsPath \ "_id" \ "$oid").json.pick))
      .andThen(JsPath.json.update(__.read[JsObject].map(o => o ++ Json.obj("issues" -> Json.arr()))))
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

  def stringToObjectId(id: Option[String]): Option[ObjectId] = {
    id.flatMap(stringToObjectId)
  }

}
