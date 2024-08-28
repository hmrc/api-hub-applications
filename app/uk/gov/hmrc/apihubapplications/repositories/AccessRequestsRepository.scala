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
import com.mongodb.client.model.IndexOptions
import org.mongodb.scala.model.{Filters, IndexModel, Indexes, ReplaceOptions}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses.AccessRequestLensOps
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, AccessRequestStatus, Pending}
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.repositories.RepositoryHelpers._
import uk.gov.hmrc.apihubapplications.repositories.models.MongoIdentifier._
import uk.gov.hmrc.apihubapplications.repositories.models.accessRequest.encrypted.SensitiveAccessRequest
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, PlainText}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.play.http.logging.Mdc

import javax.inject.Named
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AccessRequestsRepository @Inject()(
  mongoComponent: MongoComponent,
  @Named("aes") implicit val crypto: Encrypter with Decrypter
)(implicit ec: ExecutionContext)
  extends PlayMongoRepository[SensitiveAccessRequest](
    collectionName = "access-requests",
    domainFormat = formatDataWithMongoIdentifier[SensitiveAccessRequest],
    mongoComponent = mongoComponent,
    indexes = Seq(
      IndexModel(Indexes.ascending("applicationId", "status"), new IndexOptions().name("applicationId-status-index")),
      IndexModel(Indexes.ascending("status"), new IndexOptions().name("status-index"))
    ),
    extraCodecs = Seq(
      // Sensitive string codec so we can operate on individual string fields
      Codecs.playFormatCodec(sensitiveStringFormat(crypto))
    )
  ) with Logging with ExceptionRaising {

  // Ensure that we are using a deterministic cryptographic algorithm, or we won't be able to search on encrypted fields
  require(
    crypto.encrypt(PlainText("foo")) == crypto.encrypt(PlainText("foo")),
    "Crypto algorithm provided is not deterministic."
  )

  override lazy val requiresTtlIndex = false // There are no requirements to expire access requests

  def insert(accessRequests: Seq[AccessRequest]): Future[Seq[AccessRequest]] = {
    Mdc.preservingMdc {
      collection
        .insertMany(accessRequests.map(SensitiveAccessRequest(_)))
        .toFuture()
    } map {
      result =>
        accessRequests.zipWithIndex.map {
          case (accessRequest, index) =>
            accessRequest.setId(result.getInsertedIds.get(index).asObjectId().getValue.toString)
        }
    }
  }

  def find(applicationId: Option[String], status: Option[AccessRequestStatus]): Future[Seq[AccessRequest]] = {
    Mdc.preservingMdc {
      collection
        .find(
          buildAndFilter(
            applicationId.map(id => Filters.equal("applicationId", id)),
            status.map(status => Filters.equal("status", status.toString))
          )
        )
        .toFuture()
    } map {requests => requests.map(_.decryptedValue)}
  }

  def findById(id: String): Future[Option[AccessRequest]] = {
    stringToObjectId(id) match {
      case Some(objectId) =>
        Mdc.preservingMdc {
          collection
            .find(Filters.equal("_id", objectId))
            .headOption()
        } map (_.map(_.decryptedValue))
      case _ => Future.successful(None)
    }
  }

  def update(accessRequest: AccessRequest): Future[Either[ApplicationsException, Unit]] = {
    stringToObjectId(accessRequest.id) match {
      case Some(id) =>
        Mdc.preservingMdc {
          collection
            .replaceOne(
              filter = Filters.equal("_id", id),
              replacement = SensitiveAccessRequest(accessRequest),
              options     = ReplaceOptions().upsert(false)
            )
            .toFuture()
        } map {
          result =>
            if (result.getModifiedCount > 0) {
              Right(())
            }
            else {
              Left(raiseNotUpdatedException.forAccessRequest(accessRequest))
            }
        }
      case _ => Future.successful(Left(raiseAccessRequestNotFoundException.forAccessRequest(accessRequest)))
    }
  }

  def countOfPendingApprovals(): Future[Long] = {
    Mdc.preservingMdc {
      collection
        .countDocuments(Filters.equal("status", Pending.toString))
        .toFuture()
    }
  }

}
