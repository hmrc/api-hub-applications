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


import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.bson.types.ObjectId
import org.mongodb.scala.model.Filters
import org.scalatest.OptionValues
import uk.gov.hmrc.apihubapplications.models.application.{NewScope, _}
import uk.gov.hmrc.apihubapplications.models.requests.UpdateScopeStatus
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.ApplicationsService
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.ExecutionContext.Implicits.global

class ApplicationsServiceIntegrationSpec extends AnyFreeSpec
  with Matchers
  with DefaultPlayMongoRepositorySupport[Application]
  with OptionValues {

  val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

  override protected lazy val repository: ApplicationsRepository = {
    new ApplicationsRepository(mongoComponent)
  }
  protected lazy val service: ApplicationsService = {
    new ApplicationsService(repository, clock)
  }


  "register application" - {
    "must persist an application in MongoDb" in {
      val application = NewApplication("test-app-id", Creator("test-email@bla.com"))
      val result = service.registerApplication(application).futureValue

      result.id mustBe defined

      val persisted = find(Filters.equal("_id", new ObjectId(result.id.value))).futureValue.head
      persisted mustEqual result
    }
  }
  "findAll" - {
    "must retrieve all applications from MongoDb" in {
      val now = LocalDateTime.now()
      val application1 = Application(None, "test-app-1", now, Creator("test1@test.com"), now, Seq.empty, Environments())
      val application2 = Application(None, "test-app-2", now, Creator("test1@test.com"), now, Seq.empty, Environments())

      val result1 = repository.insert(application1).futureValue
      val result2 = repository.insert(application2).futureValue

      val actual: Set[Application] = service.findAll().futureValue.toSet

      val expected = Set(result1, result2)

      actual mustEqual expected
    }
  }
  "find" - {
    "must find an application by id" in {
      val now = LocalDateTime.now()
      val application = Application(None, "test-app", now, Creator("test1@test.com"), now, Seq.empty, Environments())

      val expected = repository.insert(application).futureValue
      val actual = service.findById(expected.id.value).futureValue

      actual mustBe Some(expected)
    }
    "must return None when the application does not exist in MongoDb" in {
      val id = List.fill(24)("0").mkString
      val actual = service.findById(id).futureValue

      actual mustBe None
    }
    "must return None when the Id is not a valid Object Id" in {
      val id = "invalid"
      val actual = service.findById(id).futureValue

      actual mustBe None
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
      val expected = service.addScopes(result.id.get, scopes).futureValue
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
        NewScope("existing dev scope", Seq(Dev)), //this one must be ignored
        NewScope("new prod scope", Seq(Prod))
      )

      val application = Application(None, "test-app", now, Creator("test1@test.com"), now, Seq.empty, envs)
      val result = repository.insert(application).futureValue
      val expected = service.addScopes(result.id.get, scopes).futureValue
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
      val expected = service.addScopes("9999999999999aaaaaaaaaaa", scopes).futureValue

      expected mustBe None
    }

    "must return None when the Id is not a valid Object Id" in {
      val scopes: Seq[NewScope] = Seq(
        NewScope("scope1", Seq(Dev, Test)),
        NewScope("scope2", Seq(Dev))
      )
      val expected = service.addScopes("invalid", scopes).futureValue

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
      val response = service.setScope(existingApp.id.get, "prod", "scope1", UpdateScopeStatus(Approved)).futureValue
      val actualApp = repository.findById(existingApp.id.get).futureValue.value
      actualApp.environments.prod.scopes mustBe Seq(Scope("scope1", Approved), Scope("scope2", Pending))
      response mustBe Some(true)

    }

    "must return None when trying to set scope on the application that does not exist in DB" in {
      val nonExistingAppId = "9999999999999aaaaaaaaaaa"
      val actualResult = service.setScope(nonExistingAppId, "prod", "scope1", UpdateScopeStatus(Approved)).futureValue
      actualResult mustBe Some(false)
    }

    "must return None when specified application id of invalid format" in {
      val nonExistingAppId = "invalid mongo object id"
      val actualResult = service.setScope(nonExistingAppId, "prod", "scope1", UpdateScopeStatus(Approved)).futureValue
      actualResult mustBe Some(false)
    }

  }
}
