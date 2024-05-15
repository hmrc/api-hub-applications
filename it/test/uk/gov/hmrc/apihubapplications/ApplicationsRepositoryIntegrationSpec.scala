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
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationNotFoundException, NotUpdatedException}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.repositories.models.application.encrypted.SensitiveApplication
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.ExecutionContext

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

  override protected lazy val repository: ApplicationsRepository = {
    playApplication.injector.instanceOf[ApplicationsRepository]
  }

  private implicit lazy val clock: Clock = {
    playApplication.injector.instanceOf[Clock]
  }

  "insert" - {
    "must persist an application in MongoDb" in {
      setMdcData()

      val now = LocalDateTime.now()
      val application = Application(None, "test-app", Creator("test1@test.com"), now, Seq.empty, Environments())

      val result = repository
        .insert(application)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data.id mustBe defined
      result.mdcData mustBe testMdcData

      val persisted = find(Filters.equal("_id", new ObjectId(result.data.id.value))).futureValue.head.decryptedValue.toModel
      persisted mustEqual result.data
    }
  }

  "findAll" - {
    "must retrieve all applications that are not soft deleted from MongoDb" in {
      setMdcData()

      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", Creator("test1@test.com"), now, Seq.empty, Environments())
      val application2 = Application(None, "test-app-2", Creator("test1@test.com"), now, Seq.empty, Environments())
      val application3 = Application(None, "test-app-3", Creator("test1@test.com"), now, Seq.empty, Environments(), deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))
      val application4 = Application(None, "test-app-4", Creator("test1@test.com"), now, Seq.empty, Environments(), deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))

      val saved1 = repository.insert(application1).futureValue
      val saved2 = repository.insert(application2).futureValue
      repository.insert(application3).futureValue
      repository.insert(application4).futureValue

      val result = repository
        .findAll(None, false)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data must contain theSameElementsAs Set(saved1, saved2)
      result.mdcData mustBe testMdcData
    }

    "must retrieve all applications including soft deleted from MongoDb" in {
      setMdcData()

      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", Creator("test1@test.com"), now, Seq.empty, Environments())
      val application2 = Application(None, "test-app-2", Creator("test1@test.com"), now, Seq.empty, Environments())
      val application3 = Application(None, "test-app-3", Creator("test1@test.com"), now, Seq.empty, Environments(), deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))
      val application4 = Application(None, "test-app-4", Creator("test1@test.com"), now, Seq.empty, Environments(), deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))

      val saved1 = repository.insert(application1).futureValue
      val saved2 = repository.insert(application2).futureValue
      val saved3 = repository.insert(application3).futureValue
      val saved4 = repository.insert(application4).futureValue

      val result = repository
        .findAll(None, true)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data must contain theSameElementsAs Set(saved1, saved2, saved3, saved4)
      result.mdcData mustBe testMdcData
    }

    "must retrieve all applications that are not soft deleted from MongoDb belonging to named team member" in {
      setMdcData()

      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", Creator("test1@test.com"), now, Seq(TeamMember("test1@test.com")), Environments())
      val application2 = Application(None, "test-app-2", Creator("test1@test.com"), now, Seq.empty, Environments())
      val application3 = Application(None, "test-app-3", Creator("test1@test.com"), now, Seq(TeamMember("test1@test.com")), Environments(), deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))
      val application4 = Application(None, "test-app-4", Creator("test1@test.com"), now, Seq.empty, Environments(), deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))

      val saved1 = repository.insert(application1).futureValue
      repository.insert(application2).futureValue
      repository.insert(application3).futureValue
      repository.insert(application4).futureValue

      val result = repository
        .findAll(Some("test1@test.com"), false)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustEqual Seq(saved1)
      result.mdcData mustBe testMdcData
    }

    "must retrieve all applications including soft deleted from MongoDb belonging to named team member" in {
      setMdcData()

      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", Creator("test1@test.com"), now, Seq(TeamMember("test1@test.com")), Environments())
      val application2 = Application(None, "test-app-2", Creator("test1@test.com"), now, Seq.empty, Environments())
      val application3 = Application(None, "test-app-3", Creator("test1@test.com"), now, Seq(TeamMember("test1@test.com")), Environments(), deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))
      val application4 = Application(None, "test-app-4", Creator("test1@test.com"), now, Seq.empty, Environments(), deleted = Some(Deleted(LocalDateTime.now(clock), "team@test.com")))

      val saved1 = repository.insert(application1).futureValue
      repository.insert(application2).futureValue
      val saved3 = repository.insert(application3).futureValue
      repository.insert(application4).futureValue

      val result = repository
        .findAll(Some("test1@test.com"), true)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data must contain theSameElementsAs Seq(saved1, saved3)
      result.mdcData mustBe testMdcData
    }
  }

  "findById" - {
    "must return an application when it exists in MongoDb" in {
      setMdcData()

      val now = LocalDateTime.now()
      val application = Application(None, "test-app", Creator("test1@test.com"), now, Seq.empty, Environments())

      val expected = repository.insert(application).futureValue

      val result = repository
        .findById(expected.id.value)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Right(expected)
      result.mdcData mustBe testMdcData
    }

    "must return ApplicationNotFoundException when the application is soft deleted in MongoDb" in {
      setMdcData()
      val now = LocalDateTime.now()
      val application = Application(None, "test-app", Creator("test1@test.com"), now, Seq.empty, Environments(), Some(Deleted(LocalDateTime.now(clock), "team@test.com")))
      val expected = repository.insert(application).futureValue

      val result = repository
        .findById(expected.id.get)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Left(ApplicationNotFoundException.forId(expected.id.get))
      result.mdcData mustBe testMdcData
    }

    "must return ApplicationNotFoundException when the application does not exist in MongoDb" in {
      setMdcData()

      val id = List.fill(24)("0").mkString

      val result = repository
        .findById(id)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Left(ApplicationNotFoundException.forId(id))
      result.mdcData mustBe testMdcData
    }

    "must return ApplicationNotFoundException when the Id is not a valid Object Id" in {
      setMdcData()

      val id = "invalid"

      val result = repository
        .findById(id)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Left(ApplicationNotFoundException.forId(id))
      result.mdcData mustBe testMdcData
    }
  }

  "update" - {
    "must update MongoDb when the application exists in the database" in {
      setMdcData()

      val api = Api("api_id", Seq(Endpoint("GET", "/foo/bar")))
      val application = Application(None, "test-app", Creator("test1@test.com"), Seq(TeamMember("test1@test.com"))).copy(apis = Seq(api))

      val saved = repository.insert(application).futureValue
      val updated = saved.copy(name = "test-app-updated")

      val result = repository
        .update(updated)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Right(())
      result.mdcData mustBe testMdcData

      val actual = repository.findById(updated.id.value).futureValue
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
