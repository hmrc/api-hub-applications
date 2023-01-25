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

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.bson.types.ObjectId
import org.mongodb.scala.model.Filters
import org.scalatest.OptionValues
import uk.gov.hmrc.apihubapplications.models.application
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

class ApplicationsRepositoryIntegrationSpec
  extends AnyFreeSpec
  with Matchers
  with DefaultPlayMongoRepositorySupport[Application]
  with OptionValues {

  override protected lazy val repository: ApplicationsRepository = {
    new ApplicationsRepository(mongoComponent)
  }

  "insert" - {
    "must persist an application in MongoDb" in {
      val now = LocalDateTime.now()
      val application = Application(None, "test-app", now, Creator("test1@test.com"), now, Seq.empty, Environments())

      val result = repository.insert(application).futureValue
      println(result.id)
      result.id mustBe defined

      val persisted = find(Filters.equal("_id", new ObjectId(result.id.value))).futureValue.head
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
  }

  "findById" - {
    "must return an application when it exists in MongoDb" in {
      val now = LocalDateTime.now()
      val application = Application(None, "test-app", now, Creator("test1@test.com"), now, Seq.empty, Environments())

      val expected = repository.insert(application).futureValue
      val actual = repository.findById(expected.id.value).futureValue

      actual mustBe Some(expected)
    }

    "must return None when the application does not exist in MongoDb" in {
      val id = List.fill(24)("0").mkString
      val actual = repository.findById(id).futureValue

      actual mustBe None
    }

    "must return None when the Id is not a valid Object Id" in {
      val id = "invalid"
      val actual = repository.findById(id).futureValue

      actual mustBe None
    }
  }

}
