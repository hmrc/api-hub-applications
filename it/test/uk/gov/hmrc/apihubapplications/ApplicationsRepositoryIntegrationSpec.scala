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

package uk.gov.hmrc.apihubapplications

import org.bson.types.ObjectId
import org.mongodb.scala.model.Filters
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationNotFoundException, NotUpdatedException}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.repositories.models.application.encrypted.SensitiveApplication
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.{ExecutionContext, Future}

class ApplicationsRepositoryIntegrationSpec
  extends AnyFreeSpec
  with Matchers
  with DefaultPlayMongoRepositorySupport[SensitiveApplication]
  with OptionValues
  with MdcTesting {

  private lazy val playApplication = {
    new GuiceApplicationBuilder()
      .overrides(
        bind[MongoComponent].toInstance(mongoComponent),
        bind[Clock].toInstance(Clock.fixed(Instant.now(), ZoneId.systemDefault()))
      )
      .build()
  }

  private implicit lazy val executionContext: ExecutionContext = {
    playApplication.injector.instanceOf[ExecutionContext]
  }

  override protected val repository: ApplicationsRepository = {
    playApplication.injector.instanceOf[ApplicationsRepository]
  }

  private implicit lazy val clock: Clock = {
    playApplication.injector.instanceOf[Clock]
  }

  "insert" - {
    "must persist an application in MongoDb" in {
      setMdcData()

      val now = LocalDateTime.now()
      val application = Application(None, "test-app", Creator("test1@test.com"), now, Seq.empty, credentials = Set.empty)

      val result = repository
        .insert(application)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data.id mustBe defined
      result.mdcData mustBe testMdcData

      val persisted = find(Filters.equal("_id", new ObjectId(result.data.id.value))).futureValue.head.decryptedValue.toModel(FakeHipEnvironments)
      persisted mustEqual result.data
    }
  }

  "findAll" - {
    "must retrieve all applications that are not soft deleted from MongoDb" in {
      setMdcData()

      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", Creator("test1@test.com"), now, Seq.empty, credentials = Set.empty)
      val application2 = Application(None, "test-app-2", Creator("test1@test.com"), now, Seq.empty, credentials = Set.empty)
      val application3 = Application(None, "test-app-3", Creator("test1@test.com"), now, Seq.empty, credentials = Set.empty, deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))
      val application4 = Application(None, "test-app-4", Creator("test1@test.com"), now, Seq.empty, credentials = Set.empty, deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))

      val saved1 = repository.insert(application1).futureValue
      val saved2 = repository.insert(application2).futureValue
      repository.insert(application3).futureValue
      repository.insert(application4).futureValue

      val result = repository
        .findAll(None, Seq.empty, false)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data must contain theSameElementsAs Set(saved1, saved2)
      result.mdcData mustBe testMdcData
    }

    "must retrieve all applications including soft deleted from MongoDb" in {
      setMdcData()

      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", Creator("test1@test.com"), now, Seq.empty, credentials = Set.empty)
      val application2 = Application(None, "test-app-2", Creator("test1@test.com"), now, Seq.empty, credentials = Set.empty)
      val application3 = Application(None, "test-app-3", Creator("test1@test.com"), now, Seq.empty, credentials = Set.empty, deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))
      val application4 = Application(None, "test-app-4", Creator("test1@test.com"), now, Seq.empty, credentials = Set.empty, deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))

      val saved1 = repository.insert(application1).futureValue
      val saved2 = repository.insert(application2).futureValue
      val saved3 = repository.insert(application3).futureValue
      val saved4 = repository.insert(application4).futureValue

      val result = repository
        .findAll(None, Seq.empty, true)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data must contain theSameElementsAs Set(saved1, saved2, saved3, saved4)
      result.mdcData mustBe testMdcData
    }

    "must retrieve all applications that are not soft deleted from MongoDb belonging to named team member" in {
      setMdcData()

      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", Creator("test1@test.com"), now, Seq(TeamMember("test1@test.com")), credentials = Set.empty)
      val application2 = Application(None, "test-app-2", Creator("test1@test.com"), now, Seq.empty, credentials = Set.empty)
      val application3 = Application(None, "test-app-3", Creator("test1@test.com"), now, Seq(TeamMember("test1@test.com")), credentials = Set.empty, deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))
      val application4 = Application(None, "test-app-4", Creator("test1@test.com"), now, Seq.empty, credentials = Set.empty, deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))

      val saved1 = repository.insert(application1).futureValue
      repository.insert(application2).futureValue
      repository.insert(application3).futureValue
      repository.insert(application4).futureValue

      val result = repository
        .findAll(Some("test1@test.com"), Seq.empty, false)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustEqual Seq(saved1)
      result.mdcData mustBe testMdcData
    }

    "must retrieve all applications including soft deleted from MongoDb belonging to named team member" in {
      setMdcData()

      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", Creator("test1@test.com"), now, Seq(TeamMember("test1@test.com")), credentials = Set.empty)
      val application2 = Application(None, "test-app-2", Creator("test1@test.com"), now, Seq.empty, credentials = Set.empty)
      val application3 = Application(None, "test-app-3", Creator("test1@test.com"), now, Seq(TeamMember("test1@test.com")), credentials = Set.empty, deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))
      val application4 = Application(None, "test-app-4", Creator("test1@test.com"), now, Seq.empty, credentials = Set.empty, deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))

      val saved1 = repository.insert(application1).futureValue
      repository.insert(application2).futureValue
      val saved3 = repository.insert(application3).futureValue
      repository.insert(application4).futureValue

      val result = repository
        .findAll(Some("test1@test.com"), Seq.empty, true)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data must contain theSameElementsAs Seq(saved1, saved3)
      result.mdcData mustBe testMdcData
    }
    "must retrieve all applications owned by given teams that are not soft deleted from MongoDb" in {
      setMdcData()

      val team1 = Team("test-team-1", Seq.empty, id = Some("test-team-id-1"), created = Some(LocalDateTime.now(clock)))
      val team2 = Team("test-team-2", Seq.empty, id = Some("test-team-id-2"), created = Some(LocalDateTime.now(clock)))
      val team3 = Team("test-team-3", Seq.empty, id = Some("test-team-id-3"), created = Some(LocalDateTime.now(clock)))

      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", Creator("test1@test.com"), now, team1.id.value, credentials = Set.empty)
      val application2 = Application(None, "test-app-2", Creator("test1@test.com"), now, team2.id.value, credentials = Set.empty)
      val application3 = Application(None, "test-app-3", Creator("test1@test.com"), now, "test-team-id-4", credentials = Set.empty)
      val application4 = Application(None, "test-app-4", Creator("test1@test.com"), now, team1.id.value, credentials = Set.empty).delete(Deleted(LocalDateTime.now(clock), "team@test.com"))

      val saved1 = repository.insert(application1).futureValue
      val saved2 = repository.insert(application2).futureValue
      repository.insert(application3).futureValue
      repository.insert(application4).futureValue

      val result = repository
        .findAll(None, Seq(team1, team2, team3), includeDeleted = false)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data must contain theSameElementsAs Set(saved1, saved2)
      result.mdcData mustBe testMdcData
    }

    "must retrieve all applications owned by given teams including soft deleted from MongoDb" in {
      setMdcData()

      val team1 = Team("test-team-1", Seq.empty, id = Some("test-team-id-1"), created = Some(LocalDateTime.now(clock)))
      val team2 = Team("test-team-2", Seq.empty, id = Some("test-team-id-2"), created = Some(LocalDateTime.now(clock)))
      val team3 = Team("test-team-3", Seq.empty, id = Some("test-team-id-3"), created = Some(LocalDateTime.now(clock)))

      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", Creator("test1@test.com"), now, team1.id.value, credentials = Set.empty)
      val application2 = Application(None, "test-app-2", Creator("test1@test.com"), now, team2.id.value, credentials = Set.empty)
      val application3 = Application(None, "test-app-3", Creator("test1@test.com"), now, "test-team-id-4", credentials = Set.empty)
      val application4 = Application(None, "test-app-4", Creator("test1@test.com"), now, team1.id.value, credentials = Set.empty).delete(Deleted(LocalDateTime.now(clock), "team@test.com"))

      val saved1 = repository.insert(application1).futureValue
      val saved2 = repository.insert(application2).futureValue
      repository.insert(application3).futureValue
      val saved4 = repository.insert(application4).futureValue

      val result = repository
        .findAll(None, Seq(team1, team2, team3), includeDeleted = true)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data must contain theSameElementsAs Set(saved1, saved2, saved4)
      result.mdcData mustBe testMdcData
    }

    "must combine team member email and team filters correctly" in {
      setMdcData()

      val teamMemberEmail = "test0email-1"
      val team1 = Team("test-team-1", Seq.empty, created = Some(LocalDateTime.now()), id = Some("test-team-id-1"))
      val team2 = Team("test-team-2", Seq.empty, created = Some(LocalDateTime.now()), id = Some("test-team-id-2"))

      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", Creator("test1@test.com"), now, team1.id.value, credentials = Set.empty)
      val application2 = Application(None, "test-app-2", Creator("test1@test.com"), now, Seq(TeamMember(teamMemberEmail)), credentials = Set.empty)
      val application3 = Application(None, "test-app-3", Creator("test1@test.com"), now, Seq.empty, credentials = Set.empty)
      val application4 = Application(None, "test-app-4", Creator("test1@test.com"), now, team1.id.value, credentials = Set.empty).delete(Deleted(LocalDateTime.now(clock), "team@test.com"))

      val saved1 = repository.insert(application1).futureValue
      val saved2 = repository.insert(application2).futureValue
      repository.insert(application3).futureValue
      repository.insert(application4).futureValue

      val result = repository
        .findAll(Some(teamMemberEmail), Seq(team1, team2), includeDeleted = false)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data must contain theSameElementsAs Set(saved1, saved2)
      result.mdcData mustBe testMdcData
    }
  }

  "findAllUsingApi" - {
    def applicationWithApis(name: String, apiIds: Seq[String], isDeleted: Boolean): Application = {
      val now = LocalDateTime.now()
      val apis = apiIds.map(id => Api(id, s"${id}_title", Seq.empty))
      val deleted = if (isDeleted) Some(Deleted(now, "team@test.com")) else None
      Application(None, name, now, Creator("test1@test.com"), now, None, Seq.empty, Seq.empty, apis, deleted, None, Set.empty)
    }

    val targetApiId = "api_id"
    val otherApiId = "other_api_id"
    val appHasJustTargetApiIsNotDeleted = applicationWithApis("app1", Seq(targetApiId), isDeleted = false)
    val appHasJustTargetApiIsDeleted = applicationWithApis("app2", Seq(targetApiId), isDeleted = true)
    val appHasTargetApiAndOthersIsNotDeleted = applicationWithApis("app3", Seq(otherApiId, targetApiId), isDeleted = false)
    val appHasTargetApiAndOthersIsDeleted = applicationWithApis("app4", Seq(otherApiId, targetApiId), isDeleted = true)
    val appHasDifferentApiIsNotDeleted = applicationWithApis("app5", Seq(otherApiId), isDeleted = false)
    val appHasDifferentApiIsDeleted = applicationWithApis("app6", Seq(otherApiId), isDeleted = true)

    "must retrieve all applications associated with an API that are not soft deleted from MongoDb" in {
      setMdcData()

      val hasJustTargetApi = repository.insert(appHasJustTargetApiIsNotDeleted).futureValue
      repository.insert(appHasJustTargetApiIsDeleted).futureValue
      val hasTargetApiAndOthers = repository.insert(appHasTargetApiAndOthersIsNotDeleted).futureValue
      repository.insert(appHasTargetApiAndOthersIsDeleted).futureValue
      repository.insert(appHasDifferentApiIsNotDeleted).futureValue
      repository.insert(appHasDifferentApiIsDeleted).futureValue

      val result = repository
        .findAllUsingApi(targetApiId, false)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data must contain theSameElementsAs Set(hasJustTargetApi, hasTargetApiAndOthers)
      result.mdcData mustBe testMdcData
    }

    "must retrieve all applications associated with an API, including those that are soft deleted, from MongoDb" in {
      setMdcData()

      val hasJustTargetApi = repository.insert(appHasJustTargetApiIsNotDeleted).futureValue
      val hasJustTargetApiIsDeleted = repository.insert(appHasJustTargetApiIsDeleted).futureValue
      val hasTargetApiAndOthers = repository.insert(appHasTargetApiAndOthersIsNotDeleted).futureValue
      val hasTargetApiAndOthersIsDeleted = repository.insert(appHasTargetApiAndOthersIsDeleted).futureValue
      repository.insert(appHasDifferentApiIsNotDeleted).futureValue
      repository.insert(appHasDifferentApiIsDeleted).futureValue

      val result = repository
        .findAllUsingApi(targetApiId, true)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data must contain theSameElementsAs Set(hasJustTargetApi, hasJustTargetApiIsDeleted, hasTargetApiAndOthers, hasTargetApiAndOthersIsDeleted)
      result.mdcData mustBe testMdcData
    }
  }

  "findById" - {
    "must return an application when it exists in MongoDb" in {
      setMdcData()

      val now = LocalDateTime.now()
      val application = Application(None, "test-app", Creator("test1@test.com"), now, Seq.empty, credentials = Set.empty)

      val expected = repository.insert(application).futureValue

      val result = repository
        .findById(expected.id.value, false)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Right(expected)
      result.mdcData mustBe testMdcData
    }

    "must return ApplicationNotFoundException when the application is soft deleted in MongoDb and includeDeleted is false" in {
      setMdcData()
      val now = LocalDateTime.now()
      val application = Application(None, "test-app", Creator("test1@test.com"), now, Seq.empty, Set.empty, Some(Deleted(LocalDateTime.now(clock), "team@test.com")))
      val expected = repository.insert(application).futureValue

      val result = repository
        .findById(expected.id.get, false)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Left(ApplicationNotFoundException.forId(expected.id.get))
      result.mdcData mustBe testMdcData
    }

    "must return an application when the application is soft deleted in MongoDb and includeDeleted is true" in {
      setMdcData()

      val now = LocalDateTime.now()
      val application = Application(None, "test-app", Creator("test1@test.com"), now, Seq.empty, Set.empty, Some(Deleted(LocalDateTime.now(clock), "team@test.com")))

      val expected = repository.insert(application).futureValue

      val result = repository
        .findById(expected.id.value, true)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Right(expected)
      result.mdcData mustBe testMdcData
    }

    "must return ApplicationNotFoundException when the application does not exist in MongoDb" in {
      setMdcData()

      val id = List.fill(24)("0").mkString

      val result = repository
        .findById(id, false)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Left(ApplicationNotFoundException.forId(id))
      result.mdcData mustBe testMdcData
    }

    "must return ApplicationNotFoundException when the Id is not a valid Object Id" in {
      setMdcData()

      val id = "invalid"

      val result = repository
        .findById(id, false)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Left(ApplicationNotFoundException.forId(id))
      result.mdcData mustBe testMdcData
    }
  }

  "findByTeamId" - {
    val teamId = "team-id"
    "must return an application when it exists in MongoDb and belongs to the expected team" in {
      setMdcData()

      val now = LocalDateTime.now()
      val application = Application(None, "test-app", Creator("test1@test.com"), now, Seq.empty, Set.empty)
        .copy(teamId = Some(teamId))

      val expected = repository.insert(application).futureValue

      val result = repository
        .findByTeamId(teamId, false)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Seq(expected)
      result.mdcData mustBe testMdcData
    }

    "must return the applications that belong a team" in {
      setMdcData()

      val now = LocalDateTime.now()
      val applications = Seq(
        Application(None, "test-app", Creator("test1@test.com"), now, Seq.empty, Set.empty)
          .copy(teamId = Some(teamId)),
        Application(None, "test-app-other-team", Creator("test1@test.com"), now, Seq.empty, Set.empty)
          .copy(teamId = Some("other-team")),
        Application(None, "test-deleted-app", Creator("test1@test.com"), now, Seq.empty, Set.empty)
          .copy(teamId = Some("another-team"))
          .copy(deleted = Some(Deleted(LocalDateTime.now(clock), "test-user")))
      )

      val expected = Future.traverse(applications)(application =>
        repository.insert(application)
      ).futureValue.filter(a => !a.isDeleted && a.teamId.contains(teamId))

      val result = repository
        .findByTeamId(teamId, false)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe expected
      result.mdcData mustBe testMdcData
    }

    "must return an empty result when the application is soft deleted in MongoDb and includeDeleted is false" in {
      setMdcData()
      val now = LocalDateTime.now()
      val application = Application(None, "test-app", Creator("test1@test.com"), now, Seq.empty, Set.empty, Some(Deleted(LocalDateTime.now(clock), "team@test.com")))
        .copy(teamId = Some(teamId))
      val expected = repository.insert(application).futureValue

      val result = repository
        .findByTeamId(teamId, false)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Seq.empty[Application]
      result.mdcData mustBe testMdcData
    }

    "must return an application when the application is soft deleted in MongoDb and includeDeleted is true" in {
      setMdcData()

      val now = LocalDateTime.now()
      val application = Application(None, "test-app", Creator("test1@test.com"), now, Seq.empty, Set.empty, Some(Deleted(LocalDateTime.now(clock), "team@test.com")))
        .copy(teamId = Some(teamId))

      val expected = Seq(repository.insert(application).futureValue)

      val result = repository
        .findByTeamId(teamId, true)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe expected
      result.mdcData mustBe testMdcData
    }

    "must return an empty result when the application does not exist in MongoDb" in {
      setMdcData()

      val teamId = List.fill(24)("0").mkString

      val result = repository
        .findByTeamId(teamId, false)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Seq.empty[Application]
      result.mdcData mustBe testMdcData
    }
  }

  "update" - {
    "must update MongoDb when the application exists in the database" in {
      setMdcData()

      val api = Api("api_id", "api_title", Seq(Endpoint("GET", "/foo/bar")))
      val application = Application(None, "test-app", Creator("test1@test.com"), Seq(TeamMember("test1@test.com"))).copy(apis = Seq(api))

      val saved = repository.insert(application).futureValue
      val updated = saved.copy(name = "test-app-updated")

      val result = repository
        .update(updated)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Right(())
      result.mdcData mustBe testMdcData

      val actual = repository.findById(updated.id.value, false).futureValue
      actual.map(_.copy(lastUpdated = updated.lastUpdated)) mustBe Right(updated)
    }

    "must return NotUpdatedException when the application does not exist in the database" in {
      setMdcData()

      val id = List.fill(24)("0").mkString
      val application = Application(Some(id), "test-app", Creator("test1@test.com"), Seq(TeamMember("test1@test.com")))

      val result = repository
        .update(application)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Left(NotUpdatedException.forId(id))
      result.mdcData mustBe testMdcData
    }
  }

  "delete" - {
    "must delete the application from MongoDb when it exists" in {
      setMdcData()

      val application = Application(None, "test-app", Creator("test1@test.com"), Seq(TeamMember("test1@test.com")))
      val saved = repository.insert(application).futureValue

      val result = repository
        .delete(saved.safeId)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Right(())
      result.mdcData mustBe testMdcData

      val actual = repository.findById(saved.safeId, false).futureValue
      actual mustBe Left(ApplicationNotFoundException.forId(saved.safeId))
    }

    "must return NotUpdatedException when the application does not exist in the database" in {
      setMdcData()

      val id = List.fill(24)("0").mkString

      val result = repository
        .delete(id)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Left(NotUpdatedException.forId(id))
      result.mdcData mustBe testMdcData
    }
  }

  "countOfAllApplications" - {
    "must return the correct count of applications" in {
      setMdcData()

      val count = 3
      (1 to count).foreach(
        i =>
          repository.insert(Application(None, s"test-app-$i", Creator(s"test$i@test.com"), Seq.empty)).futureValue
      )

      val result = repository
        .countOfAllApplications()
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe count
      result.mdcData mustBe testMdcData
    }
  }

}
