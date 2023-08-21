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
import uk.gov.hmrc.apihubapplications.crypto.NoCrypto
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationNotFoundException, NotUpdatedException}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.repositories.models.SensitiveApplication
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

class ApplicationsRepositoryIntegrationSpec
  extends AnyFreeSpec
  with Matchers
  with DefaultPlayMongoRepositorySupport[SensitiveApplication]
  with OptionValues {

  override protected lazy val repository: ApplicationsRepository = {
    new ApplicationsRepository(mongoComponent, NoCrypto)
  }

  "insert" - {
    "must persist an application in MongoDb" in {
      val now = LocalDateTime.now()
      val application = Application(None, "test-app", now, Creator("test1@test.com"), now, Seq.empty, Environments())

      val result = repository.insert(application).futureValue

      result.id mustBe defined

      val persisted = find(Filters.equal("_id", new ObjectId(result.id.value))).futureValue.head.decryptedValue
      persisted mustEqual result
    }
  }

  "read all" - {
    "must retrieve all applications from MongoDb" in {
      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", now, Creator("test1@test.com"), now, Seq.empty, Environments())
      val application2 = Application(None, "test-app-2", now, Creator("test1@test.com"), now, Seq.empty, Environments())

      val result1 = repository.insert(application1).futureValue
      val result2 = repository.insert(application2).futureValue

      val actual: Set[Application] = repository.findAll().futureValue.toSet

      val expected = Set(result1, result2)

      actual mustEqual expected

    }

    "must retrieve all applications from MongoDb belonging to named team member" in {
      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", now, Creator("test1@test.com"), now, Seq(TeamMember("test1@test.com")), Environments())
      val application2 = Application(None, "test-app-2", now, Creator("test1@test.com"), now, Seq.empty, Environments())

      val result1 = repository.insert(application1).futureValue
      repository.insert(application2).futureValue

      val actual: Set[Application] = repository.filter("test1@test.com").futureValue.toSet

      val expected = Set(result1)

      actual mustEqual expected

    }
  }

  "findById" - {
    "must return an application when it exists in MongoDb" in {
      val now = LocalDateTime.now()
      val application = Application(None, "test-app", now, Creator("test1@test.com"), now, Seq.empty, Environments())

      val expected = repository.insert(application).futureValue
      val actual = repository.findById(expected.id.value).futureValue

      actual mustBe Right(expected)
    }

    "must return ApplicationNotFoundException when the application does not exist in MongoDb" in {
      val id = List.fill(24)("0").mkString
      val actual = repository.findById(id).futureValue

      actual mustBe Left(ApplicationNotFoundException.forId(id))
    }

    "must return ApplicationNotFoundException when the Id is not a valid Object Id" in {
      val id = "invalid"
      val actual = repository.findById(id).futureValue

      actual mustBe Left(ApplicationNotFoundException.forId(id))
    }
  }

  "update" - {
    "must update MongoDb when the application exists in the database" in {
      val application = Application(None, "test-app", Creator("test1@test.com"), Seq(TeamMember("test1@test.com")))

      val saved = repository.insert(application).futureValue
      val updated = saved.copy(name = "test-app-updated")

      val result = repository.update(updated).futureValue
      result mustBe Right(())

      val actual = repository.findById(updated.id.value).futureValue
      actual.map(_.copy(lastUpdated = updated.lastUpdated)) mustBe Right(updated)
    }

    "must return NotUpdatedException when the application does not exist in the database" in {
      val id = List.fill(24)("0").mkString
      val application = Application(Some(id), "test-app", Creator("test1@test.com"), Seq(TeamMember("test1@test.com")))

      val result = repository.update(application).futureValue
      result mustBe Left(NotUpdatedException.forId(id))
    }
  }

  "delete" - {
    "must delete the application from MongoDb" in {
      val application = Application(None, "test-app", Creator("test1@test.com"), Seq.empty)

      val saved = repository.insert(application).futureValue

      repository.delete(saved).futureValue mustBe Right(())

      repository.findById(saved.id.value).futureValue mustBe Left(ApplicationNotFoundException.forApplication(saved))
    }

    "must return ApplicationNotFoundException when the application Id is invalid" in {
      val id = "invalid-id"
      val application = Application(Some(id), "test-app", Creator("test1@test.com"), Seq.empty)

      repository.delete(application).futureValue mustBe Left(ApplicationNotFoundException.forId(id))
    }

    "must return NotUpdatedException when the application does not exist in MongoDb" in {
      val id = List.fill(24)("0").mkString
      val application = Application(Some(id), "test-app", Creator("test1@test.com"), Seq.empty)

      repository.delete(application).futureValue mustBe Left(NotUpdatedException.forApplication(application))
    }
  }

  "countOfAllApplications" - {
    "must return the correct count of applications" in {
      val count = 3
      (1 to count).foreach(
        i =>
          repository.insert(Application(None, s"test-app-$i", Creator(s"test$i@test.com"), Seq.empty)).futureValue
      )

      repository.countOfAllApplications().futureValue mustBe count
    }
  }

  "countOfPendingApprovals" - {
    "must return the correct count of pending approvals" in {
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

      repository.countOfPendingApprovals().futureValue mustBe 3
    }
  }

}
