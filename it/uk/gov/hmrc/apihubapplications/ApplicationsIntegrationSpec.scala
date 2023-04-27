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

import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.libs.ws.{EmptyBody, WSClient}
import play.api.{Application => GuideApplication}
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.idms.{ClientResponse, Secret}
import uk.gov.hmrc.apihubapplications.models.requests.UpdateScopeStatus
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.testhelpers.ApplicationTestLenses.ApplicationTestLensOps
import uk.gov.hmrc.apihubapplications.testhelpers.{ApplicationGenerator, FakeIdmsConnector}
import uk.gov.hmrc.crypto.{ApplicationCrypto, PlainText}
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.LocalDateTime
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
  private val baseUrl = s"http://localhost:$port"

  override def fakeApplication(): GuideApplication =
    GuiceApplicationBuilder()
      .overrides(
        bind[ApplicationsRepository].toInstance(repository),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction]),
        bind[IdmsConnector].to(classOf[FakeIdmsConnector])
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

        val expectedTeamMembers =
          if (newApplication.teamMembers.contains(TeamMember(newApplication.createdBy.email))) {
            newApplication.teamMembers
          }
          else {
            newApplication.teamMembers :+ TeamMember(newApplication.createdBy.email)
          }

        responseApplication shouldBe storedApplication
        storedApplication.name shouldBe newApplication.name
        storedApplication.createdBy shouldBe newApplication.createdBy
        storedApplication.created shouldBe storedApplication.lastUpdated
        storedApplication.teamMembers shouldBe expectedTeamMembers
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

    "respond with 200 status and a list of filtered applications" in {
      val myEmail = "member1@digital.hmrc.gov.uk"
      val myTeamMembers = Seq(TeamMember(myEmail), TeamMember("member2@digital.hmrc.gov.uk"))

      val application1: Application = new Application(id = None, name = "app1", created = LocalDateTime.now, createdBy = Creator("creator@digital.hmrc.gov.uk"), lastUpdated = LocalDateTime.now(), teamMembers = myTeamMembers, environments = Environments())
      val otherTeamMembers = Seq(TeamMember("member3@digital.hmrc.gov.uk"), TeamMember("member4@digital.hmrc.gov.uk"))

      val application2 = new Application(id = None, name = "app2", created = LocalDateTime.now, createdBy = Creator("creator@digital.hmrc.gov.uk"), lastUpdated = LocalDateTime.now(), teamMembers = otherTeamMembers, environments = Environments())
      deleteAll().futureValue
      val crypto = fakeApplication().injector.instanceOf[ApplicationCrypto]
      insert(application1).futureValue
      insert(application2).futureValue

      val storedApplications: Seq[Application] = findAll().futureValue
      val myApplications = storedApplications.filter(application => application.teamMembers.contains(TeamMember(myEmail)))

      val response =
        wsClient
          .url(s"$baseUrl/api-hub-applications/applications")
          .withQueryStringParameters(("teamMember", crypto.QueryParameterCrypto.encrypt(PlainText(myEmail)).value))
          .addHttpHeaders(("Accept", "application/json"))
          .get()
          .futureValue

      response.status shouldBe 200
      response.json shouldBe Json.toJson(myApplications)
    }
  }


"GET application by ID" should {
  "respond with 200 status and the found application" in {
    forAll { (application: Application) =>
      deleteAll().futureValue

      insert(application).futureValue
      val storedApplication = findAll().futureValue.head

      val expected = storedApplication.setSecondaryCredentials(
        storedApplication.getSecondaryCredentials.map(
          credential =>
            ClientResponse(credential.clientId, FakeIdmsConnector.fakeSecret).asCredentialWithSecret()
        )
      )

      val response =
        wsClient
          .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}")
          .addHttpHeaders(("Accept", "application/json"))
          .get()
          .futureValue

      response.status shouldBe 200
      response.json shouldBe Json.toJson(expected)
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
          .post(Json.toJson(Seq(newScopes.head)))
          .futureValue

      response.status shouldBe 204
    }
  }

  "respond with a 404 NotFound if the application does not exist" in {
    forAll { (application: Application) =>
      deleteAll().futureValue

      val newScopes = Seq(NewScope("test-scope", Seq(Primary)))
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

  "set status of scopes to PENDING in primary environment and to APPROVED in secondary environments" in {
    forAll { application: Application =>
      val emptyScopesApp = application.withEmptyScopes

      deleteAll().futureValue
      insert(emptyScopesApp).futureValue

      val newScopes = Seq(NewScope("scope1", Seq(Secondary, Primary)))
      wsClient
        .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}/environments/scopes")
        .addHttpHeaders(("Content", "application/json"))
        .post(Json.toJson(newScopes))
        .futureValue


      val storedApplications = findAll().futureValue.filter(app => app.id == application.id)
      storedApplications.size shouldBe 1
      val storedApplication = storedApplications.head

      storedApplication.environments.secondary.scopes.map(_.status).toSet shouldBe Set(Approved)
      storedApplication.environments.primary.scopes.map(_.status).toSet shouldBe Set(Pending)
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


"PUT change scope status from PENDING to APPROVED on prod environment" should {
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
  "must return 404 Not Found when trying to set scope status to APPROVED on prod env when existing status is not PENDING" in {
    forAll { (application: Application) =>
      val appWithPendingProdScope = application.withEmptyScopes.withProdApprovedScopes
      deleteAll().futureValue
      insert(appWithPendingProdScope).futureValue
      val updateScopeStatus = UpdateScopeStatus(Approved)
      val statusUpdateJson = Json.toJson(updateScopeStatus)
      val response =
        wsClient
          .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}/environments/prod/scopes/${application.approvedScopeName}")
          .addHttpHeaders(("Content-Type", "application/json"))
          .put(statusUpdateJson)
          .futureValue

      response.status shouldBe 404
    }
  }
  "must return 400 Invalid Request when trying to set scope status on environment to other than prod" in {
    val appId = "whatever"
    val envName = "dev"
    val scopeName = "test-scope-name"
    val updateScope: UpdateScopeStatus = UpdateScopeStatus(Approved)
    val statusUpdateJson = Json.toJson(updateScope)
    val response =
      wsClient
        .url(s"$baseUrl/api-hub-applications/applications/$appId/environments/$envName/scopes/$scopeName")
        .addHttpHeaders(("Content-Type", "application/json"))
        .put(statusUpdateJson)
        .futureValue

    response.status shouldBe 400
  }
  "must return 400 Invalid Request when trying to set scope status on prod environment to other than APPROVED" in {
    val appId = "whatever"
    val envName = "prod"
    val scopeName = "test-scope-name"
    val updateScope: UpdateScopeStatus = UpdateScopeStatus(Pending)
    val statusUpdateJson = Json.toJson(updateScope)
    val response =
      wsClient
        .url(s"$baseUrl/api-hub-applications/applications/$appId/environments/$envName/scopes/$scopeName")
        .addHttpHeaders(("Content-Type", "application/json"))
        .put(statusUpdateJson)
        .futureValue

    response.status shouldBe 400
  }

}

  "POST to request a new secret" should {
    "respond with a 200 ok and body containing the secret and update the application with secret fragment" in {
      forAll { app: Application =>
        val appWithPrimaryClientId = app.withPrimaryCredentialClientIdOnly
        deleteAll().futureValue
        insert(appWithPrimaryClientId).futureValue
        val applicationId = app.id.get
        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/$applicationId/environments/primary/credentials/secret")
            .post(EmptyBody)
            .futureValue

        response.status shouldBe 200
        noException should be thrownBy response.json.as[Secret]

        val updatedApp = findAll().futureValue.head
        updatedApp.getPrimaryCredentials.head.secretFragment mustBe Some("1234")
      }
    }
  }
}
