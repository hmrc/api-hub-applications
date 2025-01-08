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
import org.mongodb.scala.bson.Document
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.*
import org.mongodb.scala.{ObservableFuture, SingleObservableFuture}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.models.application.{Application, TeamMember}
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.repositories.RepositoryHelpers.*
import uk.gov.hmrc.apihubapplications.repositories.models.MongoIdentifier.*
import uk.gov.hmrc.apihubapplications.repositories.models.application.encrypted.{SensitiveApplication, SensitiveTeamMember}
import uk.gov.hmrc.apihubapplications.repositories.models.application.unencrypted.DbApplication
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, PlainText}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.play.http.logging.Mdc

import javax.inject.Named
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationsRepository @Inject()(
  mongoComponent: MongoComponent,
  @Named("aes") implicit val crypto: Encrypter & Decrypter,
  hipEnvironments: HipEnvironments,
)(implicit ec: ExecutionContext)
  extends PlayMongoRepository[SensitiveApplication](
    collectionName = "applications",
    domainFormat = formatDataWithMongoIdentifier[SensitiveApplication],
    mongoComponent = mongoComponent,
    indexes = Seq(
      IndexModel(Indexes.ascending("teamMembers.email")),
      IndexModel(Indexes.ascending("apis.id")),
      IndexModel(Indexes.ascending("teamId")),
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

  def findAll(teamMemberEmail: Option[String], owningTeams: Seq[Team], includeDeleted: Boolean): Future[Seq[Application]] = {
    Mdc.preservingMdc {
      collection
        .find(orFilters(emailFilter(teamMemberEmail), teamsFilter(owningTeams)))
        .toFuture()
    } map (_.filter(deletedFilter(includeDeleted))
      .map(_.decryptedValue.toModel(hipEnvironments)))
  }

  private def emailFilter(teamMemberEmail: Option[String]): Option[Bson] = {
    teamMemberEmail.map(
      email =>
        Filters.equal("teamMembers.email", SensitiveTeamMember(TeamMember(email)).email)
    )
  }

  private def teamsFilter(teams: Seq[Team]): Option[Bson] = {
    teams match {
      case Nil => None
      case teams => Some(Filters.in("teamId", teams.flatMap(_.id)*))
    }
  }

  private def orFilters(filters: Option[Bson]*): Bson = {
    filters.flatten match {
      case Nil => Filters.empty()
      case filters => Filters.or(filters*)
    }
  }

  def findAllUsingApi(apiId: String, includeDeleted: Boolean): Future[Seq[Application]] = {
    Mdc.preservingMdc {
      collection
        .find(Filters.equal("apis.id", apiId))
        .toFuture()
    } map (_.filter(deletedFilter(includeDeleted))
      .map(_.decryptedValue.toModel(hipEnvironments)))
  }

  private def deletedFilter(includeDeleted: Boolean)(application: SensitiveApplication): Boolean = {
    if (includeDeleted) {
      true
    }
    else {
      application.deleted.isEmpty
    }
  }

  def findById(id: String, includeDeleted: Boolean): Future[Either[ApplicationsException, Application]] = {
    stringToObjectId(id) match {
      case Some(objectId) =>
        Mdc.preservingMdc {
          collection
            .find(Filters.equal("_id", objectId))
            .headOption()
        } map (_.filter(deletedFilter(includeDeleted))
          .map(_.decryptedValue.toModel(hipEnvironments))  match {
            case Some(application) => Right(application)
            case None => Left(raiseApplicationNotFoundException.forId(id))
          })
      case None => Future.successful(Left(raiseApplicationNotFoundException.forId(id)))
    }
  }

  def findByTeamId(teamId: String, includeDeleted: Boolean): Future[Seq[Application]] =
    Mdc.preservingMdc {
      collection
        .find(Filters.equal("teamId", teamId))
        .filter(deletedFilter(includeDeleted))
        .toFuture()
    } map (_.map(_.decryptedValue.toModel(hipEnvironments)))

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
            if (result.getMatchedCount > 0) {
              if (result.getModifiedCount == 0) {
                logger.warn(s"Updating Application: Application with id $id was found, but was not updated.")
              }
              Right(())
            }
            else {
              Left(raiseNotUpdatedException.forApplication(application))
            }
        )
      case None => Future.successful(Left(raiseApplicationNotFoundException.forApplication(application)))
    }
  }

  def delete(id: String): Future[Either[ApplicationsException, Unit]] = {
    stringToObjectId(id) match {
      case Some(objectId) =>
        Mdc.preservingMdc {
          collection
            .deleteOne(Filters.equal("_id", objectId))
            .toFuture()
        } map (
          result =>
            if (result.getDeletedCount > 0) {
              Right(())
            }
            else {
              Left(raiseNotUpdatedException.forId(id))
            }
        )
      case None => Future.successful(Left(raiseApplicationNotFoundException.forId(id)))
    }
  }

  def countOfAllApplications(): Future[Long] = {
    Mdc.preservingMdc {
      collection
        .countDocuments()
        .toFuture()
    }
  }

  def listIndexes: Future[Seq[Document]] = {
    collection.listIndexes().toFuture()
  }

}
