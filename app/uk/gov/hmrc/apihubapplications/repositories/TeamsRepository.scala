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
import org.mongodb.scala.model.{IndexModel, Indexes}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.models.team.TeamLenses._
import uk.gov.hmrc.apihubapplications.repositories.RepositoryHelpers.sensitiveStringFormat
import uk.gov.hmrc.apihubapplications.repositories.models.MongoIdentifier.formatDataWithMongoIdentifier
import uk.gov.hmrc.apihubapplications.repositories.models.team.encrypted.SensitiveTeam
import uk.gov.hmrc.crypto.{Decrypter, Encrypter, PlainText}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.play.http.logging.Mdc

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TeamsRepository @Inject()(
  mongoComponent: MongoComponent,
  @Named("aes") implicit val crypto: Encrypter with Decrypter
)(implicit ec: ExecutionContext)
  extends PlayMongoRepository[SensitiveTeam](
    collectionName = "teams",
    domainFormat = formatDataWithMongoIdentifier[SensitiveTeam],
    mongoComponent = mongoComponent,
    indexes = Seq(
      IndexModel(Indexes.ascending("teamMembers.email"))
    ),
    extraCodecs = Seq(
      // Sensitive string codec so we can operate on individual string fields
      Codecs.playFormatCodec(sensitiveStringFormat(crypto))
    )
  ) {

  // Ensure that we are using a deterministic cryptographic algorithm, or we won't be able to search on encrypted fields
  require(
    crypto.encrypt(PlainText("foo")) == crypto.encrypt(PlainText("foo")),
    s"Crypto algorithm provided is not deterministic."
  )

  override lazy val requiresTtlIndex = false // There are no requirements to expire applications

  def insert(team: Team): Future[Team] = {
    Mdc.preservingMdc {
      collection
        .insertOne(
          document = SensitiveTeam(team)
        )
        .toFuture()
    } map (
      result =>
        team.setId(result.getInsertedId.asObjectId().getValue.toString)
    )
  }

  def findAll(): Future[Seq[Team]] = {
    Mdc.preservingMdc {
      collection
        .find()
        .toFuture()
    } map (_.map(_.decryptedValue))
  }

}
