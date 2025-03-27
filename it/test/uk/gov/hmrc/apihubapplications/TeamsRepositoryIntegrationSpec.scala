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
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.apihubapplications.models.application.TeamMember
import uk.gov.hmrc.apihubapplications.models.exception.{NotUpdatedException, TeamNameNotUniqueException, TeamNotFoundException}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.models.team.TeamLenses._
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
    with OptionValues
    with EitherValues {

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

      result.data.value.id mustBe defined
      result.mdcData mustBe testMdcData

      val persisted = find(Filters.equal("_id", new ObjectId(result.data.value.id.value))).futureValue.head.decryptedValue
      persisted mustEqual result.data.value
    }

    "must return TeamNameNotUniqueException if the team name is already in use" in {
      setMdcData()

      repository.insert(team1).futureValue

      val result = repository
        .insert(team1.copy(name = team1.name.toUpperCase))
        .map(ResultWithMdcData(_))
        .futureValue

      result.data.left.value mustBe TeamNameNotUniqueException.forName(team1.name.toUpperCase)
      result.mdcData mustBe testMdcData
    }
  }

  "findAll" - {
    "must retrieve all teams from MongoDb" in {
      setMdcData()

      val saved1 = repository.insert(team1).futureValue.value
      val saved2 = repository.insert(team2).futureValue.value

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

      val saved1 = repository.insert(team1).futureValue.value
      val saved3 = repository.insert(team3).futureValue.value

      val result = repository
        .findAll(Some(teamMember1.email))
        .map(ResultWithMdcData(_))
        .futureValue

      result.data must contain theSameElementsAs Set(saved1, saved3)
      result.mdcData mustBe testMdcData
    }
  }

  "findById" - {
    "must return the correct Team when it exists in the repository" in {
      setMdcData()

      repository.insert(team1).futureValue
      repository.insert(team2).futureValue
      val expected = repository.insert(team3).futureValue.value

      val result = repository
        .findById(expected.id.value)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Right(expected)
      result.mdcData mustBe testMdcData
    }

    "must return TeamNotFoundException when the Team does not exist" in {
      setMdcData()

      val id = "6601487f032b6f410121bef4"

      repository.insert(team1).futureValue
      repository.insert(team2).futureValue
      repository.insert(team3).futureValue

      val result = repository
        .findById(id)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Left(TeamNotFoundException.forId(id))
      result.mdcData mustBe testMdcData
    }

    "must return TeamNotFoundException when the id is not valid MongoDb object id" in {
      setMdcData()

      val id = "not-a-valid-mongodb-object-id"

      val result = repository
        .findById(id)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Left(TeamNotFoundException.forId(id))
      result.mdcData mustBe testMdcData
    }
  }

  "findByName" - {
    "must return a team if it's name matches (case-insensitive)" in {
      setMdcData()

      val saved = repository.insert(team1).futureValue.value

      val result = repository
        .findByName(team1.name.toUpperCase)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data.value mustBe saved
      result.mdcData mustBe testMdcData
    }

    "must return None when no team can be found" in {
      setMdcData()

      val result = repository
        .findByName(team1.name)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe None
      result.mdcData mustBe testMdcData
    }
  }

  "update" - {
    "must update the team when it exists" in {
      setMdcData()

      val saved = repository.insert(team1).futureValue.value
      val updated = saved.addTeamMember("new-team-member").setName("new name")

      val result = repository
        .update(updated)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Right(())
      result.mdcData mustBe testMdcData
      repository.findById(saved.id.value).futureValue mustBe Right(updated)
    }

    "must return NotUpdatedException when the team does not exist in the database" in {
      setMdcData()

      val id = "6601487f032b6f410121bef4"
      val team = team1.setId(id)

      val result = repository
        .update(team)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Left(NotUpdatedException.forTeam(team))
      result.mdcData mustBe testMdcData
    }

    "must return TeamNotFoundException when the id is not valid MongoDb object id" in {
      setMdcData()

      val id = "not-a-valid-mongodb-object-id"
      val team = team1.setId(id)

      val result = repository
        .update(team)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Left(TeamNotFoundException.forTeam(team))
      result.mdcData mustBe testMdcData
    }

    "must return TeamNameNotUniqueException if the updated team name is already in use" in {
      setMdcData()

      val saved = repository.insert(team1).futureValue.value
      val updated = saved.copy(name = team2.name.toUpperCase)

      repository.insert(team2).futureValue

      val result = repository
        .update(updated)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data.left.value mustBe TeamNameNotUniqueException.forName(team2.name.toUpperCase)
      result.mdcData mustBe testMdcData
    }
  }

}

object TeamsRepositoryIntegrationSpec {

  val teamMember1: TeamMember = TeamMember("test-team-member-1")
  val teamMember2: TeamMember = TeamMember("test-team-member-2")
  val teamMember3: TeamMember = TeamMember("test-team-member-3")
  val teamMember4: TeamMember = TeamMember("test-team-member-4")

  val team1: Team = Team("test-team-1", Seq(teamMember1, teamMember2), created = Some(LocalDateTime.now()))
  val team2: Team = Team("test-team-2", Seq(teamMember3, teamMember4), created = Some(LocalDateTime.now()))
  val team3: Team = Team("test-team-3", Seq(teamMember1), created = Some(LocalDateTime.now()))

}
