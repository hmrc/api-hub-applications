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
import org.mongodb.scala.{ObservableFuture, SingleObservableFuture}
import org.mongodb.scala.MongoWriteException
import org.mongodb.scala.model.{Collation, CollationStrength, Filters, IndexModel, IndexOptions, Indexes, ReplaceOptions}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.models.application.TeamMember
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.models.team.TeamLenses._
import uk.gov.hmrc.apihubapplications.repositories.RepositoryHelpers.{sensitiveStringFormat, stringToObjectId}
import uk.gov.hmrc.apihubapplications.repositories.TeamsRepository.{caseInsensitiveCollation, isDuplicateKey}
import uk.gov.hmrc.apihubapplications.repositories.models.MongoIdentifier.formatDataWithMongoIdentifier
import uk.gov.hmrc.apihubapplications.repositories.models.application.encrypted.SensitiveTeamMember
import uk.gov.hmrc.apihubapplications.repositories.models.team.encrypted.SensitiveTeam
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, PlainText}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.play.http.logging.Mdc

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TeamsRepository @Inject()(
  mongoComponent: MongoComponent,
  @Named("aes") implicit val crypto: Encrypter & Decrypter
)(implicit ec: ExecutionContext)
  extends PlayMongoRepository[SensitiveTeam](
    collectionName = "teams",
    domainFormat = formatDataWithMongoIdentifier[SensitiveTeam],
    mongoComponent = mongoComponent,
    indexes = Seq(
      IndexModel(Indexes.ascending("teamMembers.email")),
      IndexModel(
        keys = Indexes.ascending(fieldNames = "name"),
        indexOptions = IndexOptions()
          .name("uniqueName")
          .unique(true)
          .collation(
            caseInsensitiveCollation
          )
      )
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

  def insert(team: Team): Future[Either[ApplicationsException, Team]] = {
    Mdc.preservingMdc {
      collection
        .insertOne(
          document = SensitiveTeam(team)
        )
        .toFuture()
    } map (
      result =>
        Right(team.setId(result.getInsertedId.asObjectId().getValue.toString))
    ) recoverWith {
      case e: MongoWriteException if isDuplicateKey(e) =>
        Future.successful(Left(raiseTeamNameNotUniqueException.forName(team.name)))
    }
  }

  def findAll(teamMember: Option[String]): Future[Seq[Team]] = {
    Mdc.preservingMdc {
      collection
        .find(
          teamMember
            .map(email => Filters.equal("teamMembers.email", SensitiveTeamMember(TeamMember(email)).email))
            .getOrElse(Filters.empty())
        )
        .toFuture()
    } map (_.map(_.decryptedValue))
  }

  def findById(id: String): Future[Either[ApplicationsException, Team]] = {
    stringToObjectId(id) match {
      case Some(objectId) =>
        Mdc.preservingMdc {
          collection
            .find(Filters.equal("_id", objectId))
            .headOption()
        } map {
          case Some(team) => Right(team.decryptedValue)
          case None => Left(raiseTeamNotFoundException.forId(id))
        }
      case None => Future.successful(Left(raiseTeamNotFoundException.forId(id)))
    }
  }

  def findByName(name: String): Future[Option[Team]] = {
    Mdc.preservingMdc {
      collection
        .find(Filters.equal("name", name))
        .collation(caseInsensitiveCollation)
        .toFuture()
    } map(_.headOption.map(_.decryptedValue))
  }

  def update(team: Team): Future[Either[ApplicationsException, Unit]] = {
    stringToObjectId(team.id) match {
      case Some(id) =>
        Mdc.preservingMdc {
          collection
            .replaceOne(
              filter = Filters.equal("_id", id),
              replacement = SensitiveTeam(team),
              options     = ReplaceOptions().upsert(false)
            )
            .toFuture()
        } map (
          result =>
            if (result.getMatchedCount > 0) {
              if (result.getModifiedCount == 0) {
                logger.warn(s"Updating Team: Team with id $id was found, but was not updated.")
              }
              Right(())
            }
            else {
              Left(raiseNotUpdatedException.forTeam(team))
            }
        ) recoverWith {
          case e: MongoWriteException if isDuplicateKey(e) =>
            Future.successful(Left(raiseTeamNameNotUniqueException.forName(team.name)))
        }
      case None => Future.successful(Left(raiseTeamNotFoundException.forTeam(team)))
    }
  }

}

object TeamsRepository {

  private val caseInsensitiveCollation: Collation = Collation.builder()
    .locale("en")
    .collationStrength(CollationStrength.PRIMARY)
    .build()

  private def isDuplicateKey(e: MongoWriteException): Boolean = {
    ErrorCategory.fromErrorCode(e.getCode) == ErrorCategory.DUPLICATE_KEY
  }

}
