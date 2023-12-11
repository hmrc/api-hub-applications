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
import org.mongodb.scala.bson.{BsonDocument, Document}
import org.mongodb.scala.model._
import play.api.Logging
import uk.gov.hmrc.apihubapplications.models.application.{Application, Deleted, Pending, TeamMember}
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.repositories.RepositoryHelpers._
import uk.gov.hmrc.apihubapplications.repositories.models.MongoIdentifier._
import uk.gov.hmrc.apihubapplications.repositories.models.application.encrypted.{SensitiveApplication, SensitiveTeamMember}
import uk.gov.hmrc.apihubapplications.repositories.models.application.unencrypted.DbApplication
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, PlainText}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.play.http.logging.Mdc

import java.time.{Clock, LocalDateTime}
import javax.inject.Named
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationsRepository @Inject()(
  mongoComponent: MongoComponent,
  @Named("aes") implicit val crypto: Encrypter with Decrypter,
  clock: Clock
)(implicit ec: ExecutionContext)
  extends PlayMongoRepository[SensitiveApplication](
    collectionName = "applications",
    domainFormat = formatDataWithMongoIdentifier[SensitiveApplication],
    mongoComponent = mongoComponent,
    indexes = Seq(
      IndexModel(Indexes.ascending("teamMembers.email")),
      IndexModel(Indexes.ascending("environments.primary.scopes.status"))
    ),
    extraCodecs = Seq(
      // Sensitive string codec so we can operate on individual string fields
      Codecs.playFormatCodec(sensitiveStringFormat(crypto))
    )
  ) with Logging with ExceptionRaising {

  // Ensure that we are using a deterministic cryptographic algorithm, or we won't be able to search on encrypted fields
  require(
    crypto.encrypt(PlainText("foo")) == crypto.encrypt(PlainText("foo")),
    s"Crypto algorithm provided is not deterministic."
  )

  override lazy val requiresTtlIndex = false // There are no requirements to expire applications

  def findAll(): Future[Seq[Application]] = {
    Mdc.preservingMdc {
      collection
        .find(Filters.equal("deleted", None))
        .toFuture()
    } map (_.map(_.decryptedValue.toModel))
  }

  def filter(teamMemberEmail: String): Future[Seq[Application]] = {
    Mdc.preservingMdc {
      collection
        .find(Filters.and(
          Filters.equal("teamMembers.email", SensitiveTeamMember(TeamMember(teamMemberEmail)).email),
          Filters.equal("deleted", None)
        ))
        .toFuture()
    } map (_.map(_.decryptedValue.toModel))
  }

  def findById(id: String): Future[Either[ApplicationsException, Application]] = {
    stringToObjectId(id) match {
      case Some(objectId) =>
        Mdc.preservingMdc {
          collection
            .find(Filters.and(
              Filters.equal("_id", objectId),
              Filters.equal("deleted", None)
            ))
            .headOption()
        } map {
          case Some(application) => Right(application.decryptedValue.toModel)
          case _ => Left(raiseApplicationNotFoundException.forId(id))
        }
      case None => Future.successful(Left(raiseApplicationNotFoundException.forId(id)))
    }
  }

  def insert(application: Application): Future[Application] = {
    Mdc.preservingMdc {
      collection
        .insertOne(
          document = SensitiveApplication(DbApplication(application))
        )
        .toFuture()
    } map (
      result => application.copy(
        id = Some(result.getInsertedId.asObjectId().getValue.toString)
      )
    )
  }

  def update(application: Application): Future[Either[ApplicationsException, Unit]] = {
    stringToObjectId(application.id) match {
      case Some(id) =>
        Mdc.preservingMdc {
          collection
            .replaceOne(
              filter = Filters.equal("_id", id),
              replacement = SensitiveApplication(DbApplication(application)),
              options     = ReplaceOptions().upsert(false)
            )
            .toFuture()
        } map (
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

  def softDelete(application: Application, currentUser: String): Future[Either[ApplicationsException, Unit]] = {
    val softDeletedApplication = application.copy(deleted = Some(Deleted(currentUser, LocalDateTime.now(clock))))
    update(softDeletedApplication)
  }

    def delete(application: Application): Future[Either[ApplicationsException, Unit]] = {
    stringToObjectId(application.id) match {
      case Some(id) =>
        Mdc.preservingMdc {
          collection.deleteOne(Filters.equal("_id", id))
            .toFuture()
        } map (
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
    Mdc.preservingMdc {
      collection
        .countDocuments()
        .toFuture()
    }
  }

  def countOfPendingApprovals(): Future[Int] = {
    Mdc.preservingMdc {
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
    }.map(_.headOption
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
