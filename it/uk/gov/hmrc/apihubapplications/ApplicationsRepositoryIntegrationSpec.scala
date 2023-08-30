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
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationNotFoundException, NotUpdatedException}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.repositories.models.SensitiveApplication
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.play.http.logging.Mdc

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext

class ApplicationsRepositoryIntegrationSpec
  extends AnyFreeSpec
  with Matchers
  with DefaultPlayMongoRepositorySupport[SensitiveApplication]
  with OptionValues {

  private lazy val playApplication = {
    new GuiceApplicationBuilder()
      .overrides(bind[MongoComponent].toInstance(mongoComponent))
      .build()
  }

  private implicit lazy val executionControl: ExecutionContext = {
    playApplication.injector.instanceOf[ExecutionContext]
  }

  override protected lazy val repository: ApplicationsRepository = {
    playApplication.injector.instanceOf[ApplicationsRepository]
  }

  private val mdcData = Map("X-Request-Id" -> "test-request-id")

  private def saveMdc(): Unit = {
    Mdc.putMdc(mdcData)
  }

  private case class ResultWithMdcData[T](data: T, mdcData: Map[String, String])

  private object ResultWithMdcData {

    def apply[T](data: T): ResultWithMdcData[T] = {
      ResultWithMdcData(data, Mdc.mdcData)
    }

  }

  "insert" - {
    "must persist an application in MongoDb" in {
      saveMdc()

      val now = LocalDateTime.now()
      val application = Application(None, "test-app", now, Creator("test1@test.com"), now, Seq.empty, Environments())

      val result = repository
        .insert(application)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data.id mustBe defined
      result.mdcData mustBe mdcData

      val persisted = find(Filters.equal("_id", new ObjectId(result.data.id.value))).futureValue.head.decryptedValue
      persisted mustEqual result.data
    }
  }

  "findAll" - {
    "must retrieve all applications from MongoDb" in {
      saveMdc()

      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", now, Creator("test1@test.com"), now, Seq.empty, Environments())
      val application2 = Application(None, "test-app-2", now, Creator("test1@test.com"), now, Seq.empty, Environments())

      val result1 = repository.insert(application1).futureValue
      val result2 = repository.insert(application2).futureValue

      val actual = repository
        .findAll()
        .map(ResultWithMdcData(_))
        .futureValue

      actual.data must contain theSameElementsAs Set(result1, result2)
      actual.mdcData mustBe mdcData
    }
  }

  "filter" - {
    "must retrieve all applications from MongoDb belonging to named team member" in {
      saveMdc()

      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", now, Creator("test1@test.com"), now, Seq(TeamMember("test1@test.com")), Environments())
      val application2 = Application(None, "test-app-2", now, Creator("test1@test.com"), now, Seq.empty, Environments())

      val result1 = repository.insert(application1).futureValue
      repository.insert(application2).futureValue

      val actual = repository
        .filter("test1@test.com")
        .map(ResultWithMdcData(_))
        .futureValue

      actual.data mustEqual Seq(result1)
      actual.mdcData mustBe mdcData
    }
  }

  "findById" - {
    "must return an application when it exists in MongoDb" in {
      saveMdc()

      val now = LocalDateTime.now()
      val application = Application(None, "test-app", now, Creator("test1@test.com"), now, Seq.empty, Environments())

      val expected = repository.insert(application).futureValue

      val actual = repository
        .findById(expected.id.value)
        .map(ResultWithMdcData(_))
        .futureValue

      actual.data mustBe Right(expected)
      actual.mdcData mustBe mdcData
    }

    "must return ApplicationNotFoundException when the application does not exist in MongoDb" in {
      saveMdc()

      val id = List.fill(24)("0").mkString

      val actual = repository
        .findById(id)
        .map(ResultWithMdcData(_))
        .futureValue

      actual.data mustBe Left(ApplicationNotFoundException.forId(id))
      actual.mdcData mustBe mdcData
    }

    "must return ApplicationNotFoundException when the Id is not a valid Object Id" in {
      saveMdc()

      val id = "invalid"

      val actual = repository
        .findById(id)
        .map(ResultWithMdcData(_))
        .futureValue

      actual.data mustBe Left(ApplicationNotFoundException.forId(id))
      actual.mdcData mustBe mdcData
    }
  }

  "update" - {
    "must update MongoDb when the application exists in the database" in {
      saveMdc()

      val application = Application(None, "test-app", Creator("test1@test.com"), Seq(TeamMember("test1@test.com")))

      val saved = repository.insert(application).futureValue
      val updated = saved.copy(name = "test-app-updated")

      val result = repository
        .update(updated)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Right(())
      result.mdcData mustBe mdcData

      val actual = repository.findById(updated.id.value).futureValue
      actual.map(_.copy(lastUpdated = updated.lastUpdated)) mustBe Right(updated)
    }

    "must return NotUpdatedException when the application does not exist in the database" in {
      saveMdc()

      val id = List.fill(24)("0").mkString
      val application = Application(Some(id), "test-app", Creator("test1@test.com"), Seq(TeamMember("test1@test.com")))

      val result = repository
        .update(application)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Left(NotUpdatedException.forId(id))
      result.mdcData mustBe mdcData
    }
  }

  "delete" - {
    "must delete the application from MongoDb" in {
      saveMdc()

      val application = Application(None, "test-app", Creator("test1@test.com"), Seq.empty)

      val saved = repository.insert(application).futureValue

      val actual = repository
        .delete(saved)
        .map(ResultWithMdcData(_))
        .futureValue

      actual.data mustBe Right(())
      actual.mdcData mustBe mdcData

      repository.findById(saved.id.value).futureValue mustBe Left(ApplicationNotFoundException.forApplication(saved))
    }

    "must return ApplicationNotFoundException when the application Id is invalid" in {
      saveMdc()

      val id = "invalid-id"
      val application = Application(Some(id), "test-app", Creator("test1@test.com"), Seq.empty)

      val actual = repository
        .delete(application)
        .map(ResultWithMdcData(_))
        .futureValue

      actual.data mustBe Left(ApplicationNotFoundException.forId(id))
      actual.mdcData mustBe mdcData
    }

    "must return NotUpdatedException when the application does not exist in MongoDb" in {
      saveMdc()

      val id = List.fill(24)("0").mkString
      val application = Application(Some(id), "test-app", Creator("test1@test.com"), Seq.empty)

      val actual = repository
        .delete(application)
        .map(ResultWithMdcData(_))
        .futureValue

      actual.data mustBe Left(NotUpdatedException.forApplication(application))
      actual.mdcData mustBe mdcData
    }
  }

  "countOfAllApplications" - {
    "must return the correct count of applications" in {
      saveMdc()

      val count = 3
      (1 to count).foreach(
        i =>
          repository.insert(Application(None, s"test-app-$i", Creator(s"test$i@test.com"), Seq.empty)).futureValue
      )

      val actual = repository
        .countOfAllApplications()
        .map(ResultWithMdcData(_))
        .futureValue

      actual.data mustBe count
      actual.mdcData mustBe mdcData
    }
  }

  "countOfPendingApprovals" - {
    "must return the correct count of pending approvals" in {
      saveMdc()

      repository.insert(
        Application(None, "test-app-1", Creator("test1@test.com"), Seq.empty)
          .addPrimaryScope(Scope("scope-name", Pending))
          .addPrimaryScope(Scope("scope-name", Denied))
      ).futureValue

      repository.insert(
        Application(None, "test-app-2", Creator("test1@test.com"), Seq.empty)
          .addPrimaryScope(Scope("scope-name", Pending))
          .addPrimaryScope(Scope("scope-name", Pending))
      ).futureValue

      repository.insert(
        Application(None, "test-app-3", Creator("test1@test.com"), Seq.empty)
          .addPrimaryScope(Scope("scope-name", Denied))
      ).futureValue

      val actual = repository
        .countOfPendingApprovals()
        .map(ResultWithMdcData(_))
        .futureValue

      actual.data mustBe 3
      actual.mdcData mustBe mdcData
    }
  }

}
