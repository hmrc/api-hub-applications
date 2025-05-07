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

package uk.gov.hmrc.apihubapplications.repositories

import com.google.inject.name.Named
import com.google.inject.{Inject, Singleton}
import com.mongodb.ErrorCategory
import org.mongodb.scala.model.*
import org.mongodb.scala.{MongoWriteException, ObservableFuture, SingleObservableFuture}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.models.event.{EntityType, Event, SensitiveEvent}
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.repositories.RepositoryHelpers.{sensitiveStringFormat, stringToObjectId}
import uk.gov.hmrc.apihubapplications.repositories.models.MongoIdentifier.formatDataWithMongoIdentifier
import uk.gov.hmrc.crypto.Sensitive.SensitiveString
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, PlainText}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.play.http.logging.Mdc

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class EventsRepository @Inject()(
  mongoComponent: MongoComponent,
  @Named("aes") implicit val crypto: Encrypter & Decrypter
)(implicit ec: ExecutionContext)
  extends PlayMongoRepository[SensitiveEvent](
    collectionName = "events",
    domainFormat = formatDataWithMongoIdentifier[SensitiveEvent],
    mongoComponent = mongoComponent,
    indexes = Seq(
      IndexModel(Indexes.ascending("entityId","entityType")),
      IndexModel(Indexes.ascending("user"))
    ),
    extraCodecs = Seq(
      // Sensitive string codec so we can operate on individual string fields
      Codecs.playFormatCodec(sensitiveStringFormat(crypto))
    ),
  ) with Logging with ExceptionRaising {

  // Ensure that we are using a deterministic cryptographic algorithm, or we won't be able to search on encrypted fields
  require(
    crypto.encrypt(PlainText("foo")) == crypto.encrypt(PlainText("foo")),
    s"Crypto algorithm provided is not deterministic."
  )

  override lazy val requiresTtlIndex = false // There are no requirements to expire events

  def insert(event: Event): Future[Event] = {
    Mdc.preservingMdc {
      collection
        .insertOne(
          document = SensitiveEvent(event)
        )
        .toFuture()
    } map (
      result =>
        event.copy(id = Some(result.getInsertedId.asObjectId().getValue.toString))
    )
  }
  
  def insertMany(events: Seq[Event]): Future[Seq[Event]] = {
    Mdc.preservingMdc {
      collection
        .insertMany(events.map(SensitiveEvent(_)))
        .toFuture()
    } map (
      result =>
        events.zipWithIndex.map {
          case (event, index) =>
            event.copy(id = Some(result.getInsertedIds.get(index).asObjectId().getValue.toString))
        }
    )
  }

  def findById(id: String): Future[Either[ApplicationsException, Event]] = {
    stringToObjectId(id) match {
      case Some(objectId) =>
        Mdc.preservingMdc {
          collection
            .find(Filters.equal("_id", objectId))
            .headOption()
        } map {
          case Some(event) => Right(event.decryptedValue)
          case None => Left(raiseTeamNotFoundException.forId(id))
        }
      case None => Future.successful(Left(raiseEventNotFoundException.forId(id)))
    }
  }

  def findByEntity(entityType: EntityType, entityId: String): Future[Seq[Event]] = {
    Mdc.preservingMdc {
      collection
        .find(Filters.and(Filters.equal("entityId", entityId), Filters.equal("entityType", entityType.toString)))
        .toFuture()
    }.map(_.map(_.decryptedValue))
  }


  def findByUser(user: String): Future[Seq[Event]] = {
    Mdc.preservingMdc {
      collection
        .find(Filters.equal("user", SensitiveString(user)))
        .toFuture()
    } map (_.map(_.decryptedValue))
  }
}

object EventsRepository {

  private val caseInsensitiveCollation: Collation = Collation.builder()
    .locale("en")
    .collationStrength(CollationStrength.PRIMARY)
    .build()

  private def isDuplicateKey(e: MongoWriteException): Boolean = {
    ErrorCategory.fromErrorCode(e.getCode) == ErrorCategory.DUPLICATE_KEY
  }

}
