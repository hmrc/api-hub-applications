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

package uk.gov.hmrc.apihubapplications

import org.bson.types.ObjectId
import org.mongodb.scala.model.Filters
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.apihubapplications.models.application.TeamMember
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.repositories.TeamsRepository
import uk.gov.hmrc.apihubapplications.repositories.models.team.encrypted.SensitiveTeam
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext

class TeamsRepositoryIntegrationSpec
  extends AnyFreeSpec
    with Matchers
    with DefaultPlayMongoRepositorySupport[SensitiveTeam]
    with MdcTesting
    with OptionValues {

  import TeamsRepositoryIntegrationSpec._

  private lazy val playApplication = {
    new GuiceApplicationBuilder()
      .overrides(
        bind[MongoComponent].toInstance(mongoComponent)
      )
      .build()
  }

  private implicit lazy val executionContext: ExecutionContext = {
    playApplication.injector.instanceOf[ExecutionContext]
  }

  override protected val repository: TeamsRepository = {
    playApplication.injector.instanceOf[TeamsRepository]
  }

  "insert" - {
    "must persist a Team in MongoDb" in {
      setMdcData()

      val result = repository
        .insert(team1)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data.id mustBe defined
      result.mdcData mustBe testMdcData

      val persisted = find(Filters.equal("_id", new ObjectId(result.data.id.value))).futureValue.head.decryptedValue
      persisted mustEqual result.data
    }
  }

  "findAll" - {
    "must retrieve all teams from MongoDb" in {
      setMdcData()

      val saved1 = repository.insert(team1).futureValue
      val saved2 = repository.insert(team2).futureValue

      val result = repository
        .findAll(None)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data must contain theSameElementsAs Set(saved1, saved2)
      result.mdcData mustBe testMdcData
    }

    "must retrieve only teams with a specified member when requested" in {
      setMdcData()

      repository.insert(team2).futureValue

      val saved1 = repository.insert(team1).futureValue
      val saved3 = repository.insert(team3).futureValue

      val result = repository
        .findAll(Some(teamMember1.email))
        .map(ResultWithMdcData(_))
        .futureValue

      result.data must contain theSameElementsAs Set(saved1, saved3)
      result.mdcData mustBe testMdcData
    }
  }

}

object TeamsRepositoryIntegrationSpec {

  val teamMember1: TeamMember = TeamMember("test-team-member-1")
  val teamMember2: TeamMember = TeamMember("test-team-member-2")
  val teamMember3: TeamMember = TeamMember("test-team-member-3")
  val teamMember4: TeamMember = TeamMember("test-team-member-4")

  val team1: Team = Team("test-team-1", LocalDateTime.now(), Seq(teamMember1, teamMember2))
  val team2: Team = Team("test-team-2", LocalDateTime.now(), Seq(teamMember3, teamMember4))
  val team3: Team = Team("test-team-3", LocalDateTime.now(), Seq(teamMember1))

}
