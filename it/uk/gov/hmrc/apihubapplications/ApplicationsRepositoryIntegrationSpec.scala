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
import models.Application
import org.bson.types.ObjectId
import org.mongodb.scala.model.Filters
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global

class ApplicationsRepositoryIntegrationSpec
  extends AnyFreeSpec
  with Matchers
  with DefaultPlayMongoRepositorySupport[Application] {

  override protected lazy val repository: ApplicationsRepository = {
    new ApplicationsRepository(mongoComponent)
  }

  "insert" - {
    "must persist an application in MongoDb" in {
      val application = Application(None, "test-app")

      val result = repository.insert(application).futureValue
      println(result.id)
      result.id mustBe defined

      val persisted = find(Filters.equal("_id", new ObjectId(result.id.get))).futureValue.head
      persisted mustEqual result
    }
  }

  "read all" - {
    "must retrieve all applications from MongoDb" in {
      val application1 = Application(None, "test-app-1")
      val application2 = Application(None, "test-app-2")

      val result1 = repository.insert(application1).futureValue
      val result2 = repository.insert(application2).futureValue

      val actual: Set[Application] = repository.findAll().futureValue.toSet

      val expected = Set(result1, result2)

      actual mustEqual expected

    }
  }

}
