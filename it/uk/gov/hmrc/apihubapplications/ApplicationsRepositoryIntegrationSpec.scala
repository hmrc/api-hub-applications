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
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.requests.UpdateScopeStatus
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

  "update" - {
    "must update MongoDb and return true when the application exists in the database" in {
      val application = Application(None, "test-app", Creator("test1@test.com"))

      val saved = repository.insert(application).futureValue
      val updated = saved.copy(name = "test-app-updated")

      val result = repository.update(updated).futureValue
      result mustBe true

      val actual = repository.findById(updated.id.value).futureValue
      actual.value.copy(lastUpdated=updated.lastUpdated) mustBe updated
    }

    "must return false when the application does not exist in the database" in {
      val id = List.fill(24)("0").mkString
      val application = Application(Some(id), "test-app", Creator("test1@test.com"))

      val result = repository.update(application).futureValue
      result mustBe false
    }
  }

  "add scopes" - {
    "must return true if mongoDB was updated successfully with multiple scopes" in {

      val scopes: Seq[NewScope] = Seq(
        NewScope("scope1", Seq(Dev, Test)),
        NewScope("scope2", Seq(Dev)),
        NewScope("scope3", Seq(PreProd)),
        NewScope("scope4", Seq(Prod)),
      )
      val now = LocalDateTime.now()
      val application = Application(None, "test-app", now, Creator("test1@test.com"), now, Seq.empty, Environments())
      val result = repository.insert(application).futureValue
      val expected = repository.addScopes(result.id.get, scopes).futureValue
      val actual_app = repository.findById(result.id.get).futureValue.value
      Some(true) mustBe expected
      val expectedEnvs = Environments(
        Environment(Seq(
          Scope("scope1", Approved),
          Scope("scope2", Approved)
        ), Seq.empty),
        Environment(Seq(
          Scope("scope1", Approved)
        ), Seq.empty),
        Environment(Seq(
          Scope("scope3", Approved)
        ), Seq.empty),
        Environment(Seq(
          Scope("scope4", Pending)
        ), Seq.empty)
      )
      Some(true) mustBe expected
      actual_app.environments mustBe expectedEnvs

    }

    "must update db with new scopes, some of which are duplicating existing application's scopes. Duplicates must be void" in {

      val now = LocalDateTime.now()
      val envs = Environments(Environment(Seq(
        Scope("existing dev scope", Approved)
      ), Seq.empty),
        Environment(Seq.empty, Seq.empty),
        Environment(Seq.empty, Seq.empty),
        Environment(Seq(
          Scope("existing prod scope", Pending)
        ),
          Seq.empty))

      val scopes = Seq(
        NewScope("new dev scope", Seq(Dev)),
        NewScope("existing dev scope", Seq(Dev)),//this one must be ignored
        NewScope("new prod scope", Seq(Prod))
      )

      val application = Application(None, "test-app", now, Creator("test1@test.com"), now, Seq.empty, envs)
      val result = repository.insert(application).futureValue
      val expected = repository.addScopes(result.id.get, scopes).futureValue
      val actual_app = repository.findById(result.id.get).futureValue.value
      Some(true) mustBe expected
      val expectedEnvs = Environments(
        Environment(Seq(
          Scope("existing dev scope", Approved),
          Scope("new dev scope", Approved)
        ), Seq.empty),
        Environment(Seq.empty, Seq.empty),
        Environment(Seq.empty, Seq.empty),
        Environment(Seq(
          Scope("existing prod scope", Pending),
          Scope("new prod scope", Pending)
        ), Seq.empty),
      )
      Some(true) mustBe expected
      actual_app.environments mustBe expectedEnvs
    }


    "Must return None when when attempting to add scopes and the application does not exist in MongoDb" in {

      val scopes: Seq[NewScope] = Seq(
        NewScope("scope1", Seq(Dev, Test)),
        NewScope("scope2", Seq(Dev))
      )
      val expected = repository.addScopes("9999999999999aaaaaaaaaaa", scopes).futureValue

      Some(false) mustBe expected
    }

    "must return None when the Id is not a valid Object Id" in {
      val scopes: Seq[NewScope] = Seq(
        NewScope("scope1", Seq(Dev, Test)),
        NewScope("scope2", Seq(Dev))
      )
      val expected = repository.addScopes("invalid", scopes).futureValue

      None mustBe expected
    }
  }

  "update scope to APPROVED" - {
    "must return true if the scope status was updated successfully to APPROVED" in {

      val prodEnv = Environment(Seq(
        Scope("scope1", Pending),
        Scope("scope2", Pending)
      ), Seq.empty)
      val now = LocalDateTime.now()
      val application = Application(None, "test-app", now, Creator("test1@test.com"), now, Seq.empty, Environments(Environment(), Environment(), Environment(), prodEnv))
      val existingApp = repository.insert(application).futureValue
      val response = repository.setScope(existingApp.id.get, "prod", "scope1", UpdateScopeStatus(Approved)).futureValue
      val actualApp = repository.findById(existingApp.id.get).futureValue.value
      actualApp.environments.prod.scopes mustBe Seq(Scope("scope1", Approved), Scope("scope2", Pending))
      response mustBe Some(true)

    }

    "must return Some(false) when trying to set scope on the application that does not exist in DB" in {
      val nonExistingAppId = "9999999999999aaaaaaaaaaa"
      val actualResult = repository.setScope(nonExistingAppId, "prod", "scope1", UpdateScopeStatus(Approved)).futureValue
      actualResult mustBe Some(false)
    }

    "must return None when specified application id of invalid format" in {
      val nonExistingAppId = "invalid mongo object id"
      val actualResult = repository.setScope(nonExistingAppId, "prod", "scope1", UpdateScopeStatus(Approved)).futureValue
      actualResult mustBe None
    }

  }
}
