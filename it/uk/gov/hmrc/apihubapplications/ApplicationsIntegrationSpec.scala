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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.OptionValues
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.{Application => GuideApplication}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.requests.UpdateScopeStatus
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.testhelpers.ApplicationGenerator
import uk.gov.hmrc.apihubapplications.testhelpers.ApplicationTestLenses.ApplicationTestLensOps
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global

class ApplicationsIntegrationSpec
  extends AnyWordSpec
     with Matchers
     with OptionValues
     with GuiceOneServerPerSuite
     with DefaultPlayMongoRepositorySupport[Application]
     with ScalaCheckPropertyChecks
     with ApplicationGenerator {

  private val wsClient = app.injector.instanceOf[WSClient]
  private val baseUrl  = s"http://localhost:$port"

  override def fakeApplication(): GuideApplication =
    GuiceApplicationBuilder()
      .overrides(
        bind[ApplicationsRepository].toInstance(repository)
      )
      .configure("metrics.enabled" -> false)
      .build()

  override protected lazy val repository: ApplicationsRepository = {
    new ApplicationsRepository(mongoComponent)
  }

  "POST to register a new application" should {
    "respond with a 201 Created and body containing the application" in {
      forAll { newApplication: NewApplication =>
        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications")
            .addHttpHeaders(("Content", "application/json"))
            .post(Json.toJson(newApplication))
            .futureValue

        response.status shouldBe 201
        noException should be thrownBy response.json.as[Application]
      }
    }

    "respond with a 400 BadRequest if the application does not have the required properties" in {
      val appWithoutANameRequest = Json.parse(
        s"""
           |{
           |    "createdBy": {
           |        "email": "laura@email.com"
           |    }
           |}
           |""".stripMargin)

      val response =
        wsClient
          .url(s"$baseUrl/api-hub-applications/applications")
          .addHttpHeaders(("Content", "application/json"))
          .post(appWithoutANameRequest)
          .futureValue

      response.status shouldBe 400
    }

    "add a full Application to the database" in {
      forAll { newApplication: NewApplication =>

        val response = wsClient
          .url(s"$baseUrl/api-hub-applications/applications")
          .addHttpHeaders(("Content", "application/json"))
          .post(Json.toJson(newApplication))
          .futureValue

        val responseApplication = response.json.as[Application]
        val hexStringLength = 24
        responseApplication.id.value.length shouldBe hexStringLength

        val storedApplications = findAll().futureValue.filter(app => app.id == responseApplication.id)
        storedApplications.size shouldBe 1
        val storedApplication = storedApplications.head

        responseApplication shouldBe storedApplication
        storedApplication.name shouldBe newApplication.name
        storedApplication.createdBy shouldBe newApplication.createdBy
        storedApplication.created shouldBe storedApplication.lastUpdated
        storedApplication.teamMembers shouldBe Seq(TeamMember(newApplication.createdBy.email))
        storedApplication.environments.dev shouldBe Environment(Seq.empty, Seq.empty)
        storedApplication.environments.test shouldBe Environment(Seq.empty, Seq.empty)
        storedApplication.environments.preProd shouldBe Environment(Seq.empty, Seq.empty)
        storedApplication.environments.prod shouldBe Environment(Seq.empty, Seq.empty)
      }
    }
  }

  "GET all application" should {
    "respond with 200 status and a list of applications" in {
      forAll { (application1: Application, application2: Application) =>
        deleteAll().futureValue

        insert(application1).futureValue
        insert(application2).futureValue

        val storedApplications: Seq[Application] = findAll().futureValue

        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications")
            .addHttpHeaders(("Accept", "application/json"))
            .get()
            .futureValue

        response.status shouldBe 200
        response.json shouldBe Json.toJson(storedApplications)
      }
    }
  }

  "GET application by ID" should {
    "respond with 200 status and the found application" in {
      forAll { (application: Application) =>
        deleteAll().futureValue

        insert(application).futureValue

        val storedApplication = findAll().futureValue.head

        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}")
            .addHttpHeaders(("Accept", "application/json"))
            .get()
            .futureValue

        response.status shouldBe 200
        response.json shouldBe Json.toJson(storedApplication)
      }
    }

    "respond with 404 status if the application cannot be found" in {
      val response =
        wsClient
          .url(s"$baseUrl/api-hub-applications/applications/non-existent-app-id")
          .addHttpHeaders(("Accept", "application/json"))
          .get()
          .futureValue

      response.status shouldBe 404

    }
  }

  "POST to add scopes to environments of an application" should {
    "respond with a 204 No Content" in {
      forAll { (application: Application, newScopes: Seq[NewScope]) =>
        deleteAll().futureValue
        insert(application).futureValue

        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}/environments/scopes")
            .addHttpHeaders(("Content", "application/json"))
            .post(Json.toJson(newScopes))
            .futureValue

        response.status shouldBe 204
      }
    }

    "respond with a 404 NotFound if the application does not exist" in {
      forAll { (application: Application, newScopes: Seq[NewScope]) =>
        deleteAll().futureValue

        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}/environments/scopes")
            .addHttpHeaders(("Content", "application/json"))
            .post(Json.toJson(newScopes))
            .futureValue

        response.status shouldBe 404
      }
    }

    "respond with a 400 BadRequest if the application exist but we try to add scopes to an environment that does not exist" in {
      forAll { application: Application =>
        deleteAll().futureValue
        insert(application).futureValue

        val invalidEnvironmentRequest = Json.parse(
          s"""
             |[
             |  {
             |    "name": "scope1",
             |    "environments": ["env-does-not-exist"]
             |  }
             |]
             |""".stripMargin)

        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}/environments/scopes")
            .addHttpHeaders(("Content", "application/json"))
            .post(invalidEnvironmentRequest)
            .futureValue

        response.status shouldBe 400
      }
    }

    "set the status of scopes to approved in all environments except prod, and pending in prod" in {
      forAll { application: Application =>
        val emptyScopesApp = application.withEmptyScopes

        deleteAll().futureValue
        insert(emptyScopesApp).futureValue

        val newScopes = Seq(NewScope("scope1", Seq(Dev, Test, PreProd, Prod)))
        wsClient
          .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}/environments/scopes")
          .addHttpHeaders(("Content", "application/json"))
          .post(Json.toJson(newScopes))
          .futureValue


        val storedApplications = findAll().futureValue.filter(app => app.id == application.id)
        storedApplications.size shouldBe 1
        val storedApplication = storedApplications.head

        storedApplication.environments.dev.scopes.map(_.status).toSet shouldBe Set(Approved)
        storedApplication.environments.test.scopes.map(_.status).toSet shouldBe Set(Approved)
        storedApplication.environments.preProd.scopes.map(_.status).toSet shouldBe Set(Approved)
        storedApplication.environments.prod.scopes.map(_.status).toSet shouldBe Set(Pending)
      }
    }
  }

  "GET pending scopes" should {
    "respond with a 200 and a list applications that have at least one status of pending for prod" in {
      forAll { (application1: Application, application2: Application) =>
        val appWithPendingTestScopes = application1.withEmptyScopes.withTestPendingScopes
        val appWithPendingProdScopes = application2.withEmptyScopes.withProdPendingScopes.withProdApprovedScopes

        deleteAll().futureValue
        insert(appWithPendingTestScopes).futureValue
        insert(appWithPendingProdScopes).futureValue

        val response = wsClient
          .url(s"$baseUrl/api-hub-applications/applications/pending-scopes")
          .addHttpHeaders(("Accept", "application/json"))
          .get()
          .futureValue

        response.status shouldBe 200
        response.json shouldBe Json.toJson(Seq(appWithPendingProdScopes))
      }
    }
  }


  "PUT set scope status" should{
    "respond with a 204 No Content when the status was set successfully" in {
      forAll { (application: Application) =>
        val appWithPendingProdScope = application.withEmptyScopes.withProdPendingScopes.withProdApprovedScopes
        deleteAll().futureValue
        insert(appWithPendingProdScope).futureValue
        val updateScopeStatus = UpdateScopeStatus(Approved)
        val statusUpdateJson = Json.toJson(updateScopeStatus)
        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}/environments/prod/scopes/${application.pendingScopeName}")
            .addHttpHeaders(("Content-Type", "application/json"))
            .put(statusUpdateJson)
            .futureValue

        response.status shouldBe 204
      }
    }
    "must return 404 Not Found when trying to set scope on the application that does not exist in DB" in {
      forAll { (_: Application) =>
        deleteAll().futureValue
        val updateScopeStatus = UpdateScopeStatus(Approved)
        val statusUpdateJson = Json.toJson(updateScopeStatus)
        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/non-existent-app-id/environments/prod/scopes/test-scope-name")
            .addHttpHeaders(("Content-Type", "application/json"))
            .put(statusUpdateJson)
            .futureValue

        response.status shouldBe 404
      }
    }
    "must return 400 badRequest when trying to set scope not to APPROVED" in {
      forAll { (application: Application) =>
        val appWithPendingProdScope = application.withEmptyScopes.withProdPendingScopes.withProdApprovedScopes
        deleteAll().futureValue
        insert(appWithPendingProdScope).futureValue
        val updateScopeStatus = UpdateScopeStatus(Pending)
        val statusUpdateJson = Json.toJson(updateScopeStatus)
        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}/environments/prod/scopes/${application.pendingScopeName}")
            .addHttpHeaders(("Content-Type", "application/json"))
            .put(statusUpdateJson)
            .futureValue

        response.status shouldBe 400
      }
    }
  }

}