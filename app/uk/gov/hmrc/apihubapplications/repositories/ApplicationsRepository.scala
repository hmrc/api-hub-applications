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
import org.mongodb.scala.bson.{BsonDocument, Document}
import org.mongodb.scala.model._
import play.api.Logging
import play.api.libs.json.Format
import uk.gov.hmrc.apihubapplications.models.application.{Application, Pending, TeamMember}
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository.{sensitiveStringFormat, stringToObjectId}
import uk.gov.hmrc.apihubapplications.repositories.models.{SensitiveApplication, SensitiveTeamMember}
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.crypto.json.JsonEncryption
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, PlainText}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import javax.inject.Named
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationsRepository @Inject()(
  mongoComponent: MongoComponent,
  @Named("aes") crypto: Encrypter with Decrypter
)(implicit ec: ExecutionContext)
  extends PlayMongoRepository[SensitiveApplication](
    collectionName = "applications",
    domainFormat = SensitiveApplication.formatSensitiveApplication(crypto),
    mongoComponent = mongoComponent,
    indexes = Seq(
      IndexModel(Indexes.ascending("teamMembers.email")),
      IndexModel(Indexes.ascending("environments.primary.scopes.status"))
    ),
    extraCodecs = Seq(
      // Sensitive string codec so we can operate on individual string fields
      Codecs.playFormatCodec(sensitiveStringFormat(crypto))
    ),
    replaceIndexes = true
  ) with Logging with ExceptionRaising {

  // Ensure that we are using a deterministic cryptographic algorithm, or we won't be able to search on encrypted fields
  require(
    crypto.encrypt(PlainText("foo")) == crypto.encrypt(PlainText("foo")),
    s"Crypto algorithm provided is not deterministic."
  )

  override lazy val requiresTtlIndex = false // There are no requirements to expire applications

  implicit val theCrypto: Encrypter with Decrypter = crypto

  def findAll(): Future[Seq[Application]] = {
    collection
      .find()
      .toFuture()
      .map(_.map(_.decryptedValue))
  }

  def filter(teamMemberEmail: String): Future[Seq[Application]] = {
    collection
      .find(Filters.equal("teamMembers.email", SensitiveTeamMember(TeamMember(teamMemberEmail)).email))
      .toFuture()
      .map(_.map(_.decryptedValue))
  }

  def findById(id: String): Future[Either[ApplicationsException, Application]] = {
    stringToObjectId(id) match {
      case Some(objectId) =>
        collection
          .find(Filters.equal("_id", objectId))
          .headOption()
          .map {
            case Some(application) => Right(application.decryptedValue)
            case _ => Left(raiseApplicationNotFoundException.forId(id))
          }
      case None => Future.successful(Left(raiseApplicationNotFoundException.forId(id)))
    }
  }

  def insert(application: Application): Future[Application] = {
    collection
      .insertOne(
        document = SensitiveApplication(application)
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
            replacement = SensitiveApplication(application),
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

  def listIndexes: Future[Seq[Document]] = {
    collection.listIndexes().toFuture()
  }

}

object ApplicationsRepository extends Logging {

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

  private def sensitiveStringFormat(implicit crypto: Encrypter with Decrypter): Format[SensitiveString] =
    JsonEncryption.sensitiveEncrypterDecrypter(SensitiveString.apply)

}
