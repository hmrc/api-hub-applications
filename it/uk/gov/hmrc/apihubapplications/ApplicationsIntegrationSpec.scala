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

import org.mongodb.scala.result.InsertOneResult
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.http.Status.{BAD_REQUEST, NOT_FOUND, NO_CONTENT}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsSuccess, Json}
import play.api.libs.ws.{EmptyBody, WSClient}
import play.api.test.Helpers.CONTENT_TYPE
import play.api.{Application => GuideApplication}
import uk.gov.hmrc.apihubapplications.connectors.{EmailConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.crypto.NoCrypto
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.idms.Secret
import uk.gov.hmrc.apihubapplications.models.requests.{AddApiRequest, UpdateScopeStatus, UserEmail}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.repositories.models.application.encrypted.SensitiveApplication
import uk.gov.hmrc.apihubapplications.repositories.models.application.unencrypted.DbApplication
import uk.gov.hmrc.apihubapplications.testhelpers.ApplicationTestLenses.ApplicationTestLensOps
import uk.gov.hmrc.apihubapplications.testhelpers.{ApplicationGenerator, FakeEmailConnector, FakeIdmsConnector}
import uk.gov.hmrc.crypto.{ApplicationCrypto, PlainText}
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ApplicationsIntegrationSpec
  extends AnyWordSpec
    with Matchers
    with OptionValues
    with GuiceOneServerPerSuite
    with DefaultPlayMongoRepositorySupport[SensitiveApplication]
    with ScalaCheckPropertyChecks
    with ApplicationGenerator {

  private val wsClient = app.injector.instanceOf[WSClient]
  private val baseUrl = s"http://localhost:$port"
  private val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
  override def fakeApplication(): GuideApplication =
    GuiceApplicationBuilder()
      .overrides(
        bind[ApplicationsRepository].toInstance(repository),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction]),
        bind[IdmsConnector].to(classOf[FakeIdmsConnector]),
        bind[EmailConnector].to(classOf[FakeEmailConnector]),
        bind[Clock].toInstance(clock)
      )
      .configure("metrics.enabled" -> false)
      .build()

  override protected lazy val repository: ApplicationsRepository = {
    new ApplicationsRepository(mongoComponent, NoCrypto, clock)
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
        val storedApplication = storedApplications.head.decryptedValue.toModel

        val expectedTeamMembers =
          if (newApplication.teamMembers.contains(TeamMember(newApplication.createdBy.email))) {
            newApplication.teamMembers
          }
          else {
            newApplication.teamMembers :+ TeamMember(newApplication.createdBy.email)
          }

        // This test is becoming a bit tricky as the stored and returned responses deviate
        //  -we don't return hidden primary credentials
        //  -we do return the client secret for secondary credentials
        val expectedApplication = storedApplication
          .makePublic()
          .setSecondaryCredentials(
            storedApplication
              .getSecondaryCredentials
              .map(credential => credential.copy(clientSecret = Some(FakeIdmsConnector.fakeSecret)))
          )

        responseApplication shouldBe expectedApplication
        storedApplication.name shouldBe newApplication.name
        storedApplication.createdBy shouldBe newApplication.createdBy
        storedApplication.created shouldBe expectedApplication.lastUpdated
        storedApplication.teamMembers shouldBe expectedTeamMembers
      }
    }
  }

  "GET all application" should {
    "respond with 200 status and a list of applications" in {
      forAll { (application1: Application, application2: Application) =>
        deleteAll().futureValue

        insert(application1).futureValue
        insert(application2).futureValue

        val storedApplications: Seq[Application] = findAll().futureValue.map(_.decryptedValue.toModel)

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

      val application1: Application = new Application(id = None, name = "app1", created = LocalDateTime.now, createdBy = Creator("creator@digital.hmrc.gov.uk"), lastUpdated = LocalDateTime.now(), teamMembers = myTeamMembers, environments = Environments(), apis = Seq.empty, deleted = None)
      val otherTeamMembers = Seq(TeamMember("member3@digital.hmrc.gov.uk"), TeamMember("member4@digital.hmrc.gov.uk"))

      val application2 = new Application(id = None, name = "app2", created = LocalDateTime.now, createdBy = Creator("creator@digital.hmrc.gov.uk"), lastUpdated = LocalDateTime.now(), teamMembers = otherTeamMembers, environments = Environments(), apis = Seq.empty, deleted = None)
      deleteAll().futureValue
      val crypto = fakeApplication().injector.instanceOf[ApplicationCrypto]
      insert(application1).futureValue
      insert(application2).futureValue

      val storedApplications: Seq[Application] = findAll().futureValue.map(_.decryptedValue.toModel)
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

      insert(
        application
          .setPrimaryCredentials(Seq(Credential(FakeIdmsConnector.fakeClientId, LocalDateTime.now(), None, None)))
          .setPrimaryScopes(Seq.empty)
          .setSecondaryCredentials(Seq(Credential(FakeIdmsConnector.fakeClientId, LocalDateTime.now(), None, None)))
      ).futureValue

      val storedApplication = findAll().futureValue.head.decryptedValue.toModel

      val expected = storedApplication
        .setSecondaryCredentials(
          storedApplication
            .getSecondaryCredentials.map(
              credential => credential.copy(
                clientSecret = Some(FakeIdmsConnector.fakeSecret),
                secretFragment = Some(FakeIdmsConnector.fakeSecret.takeRight(4))
              )
            )
        )
        .setPrimaryScopes(
          Seq(
            Scope(FakeIdmsConnector.fakeClientScopeId1, Approved),
            Scope(FakeIdmsConnector.fakeClientScopeId2, Approved)
          )
        )
        .setSecondaryScopes(
          Seq(
            Scope(FakeIdmsConnector.fakeClientScopeId1, Approved),
            Scope(FakeIdmsConnector.fakeClientScopeId2, Approved)
          )
        )
        .makePublic()

        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}?enrich=true")
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

  "Deleting an application" should {
    "soft delete the application and respond with 204 No Content when successful" in {
      forAll { (application: Application) =>
        deleteAll().futureValue
        insert(application).futureValue

        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}/delete")
            .withHttpHeaders((CONTENT_TYPE, "application/json"))
            .post(Json.toJson(UserEmail("me@test.com")).toString())
            .futureValue

        response.status shouldBe NO_CONTENT

        val storedApplications = findAll().futureValue
        storedApplications.size shouldBe 1
        val storedApplication = storedApplications.headOption
        storedApplication.isDefined mustBe true
        storedApplication.get.deleted.isDefined mustBe true
        storedApplication.get.deleted.get.decryptedValue mustBe Deleted(LocalDateTime.now(clock), TeamMember("me@test.com"))
      }
    }

    "respond with 404 Not Found when the application does not exist" in {
      forAll { (application: Application) =>
        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}/delete")
            .withHttpHeaders((CONTENT_TYPE, "application/json"))
            .post(Json.toJson(UserEmail("me@test.com")).toString())
            .futureValue
        response.status shouldBe NOT_FOUND
      }
    }

    "respond with 400 Bad Request when the request does not have a user email body" in {
      forAll { (application: Application) =>
        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}/delete")
            .withHttpHeaders((CONTENT_TYPE, "application/json"))
            .post("{}")
            .futureValue
        response.status shouldBe BAD_REQUEST
      }
    }
  }

"POST to add scopes to environments of an application" should {
  "respond with a 204 No Content" in {
    forAll { (application: Application) =>

      val applicationWithSecondaryCredentials = application.setSecondaryCredentials(Seq(Credential("client-id", LocalDateTime.now(), None, None)))
      deleteAll().futureValue
      insert(applicationWithSecondaryCredentials).futureValue

      val response =
        wsClient
          .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}/environments/scopes")
          .addHttpHeaders(("Content", "application/json"))
          .post(Json.toJson(Seq(NewScope("new scope", Seq(Primary)))))
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

  "set status of scopes to PENDING in primary environment" in {
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

      storedApplication.environments.primary.scopes.map(_.status).toSet shouldBe Set(Pending)
    }
  }
}

  "GET pending scopes" should {
    "respond with a 200 and a list applications that have at least one primary scope with status of pending" in {
      forAll { (application1: Application, application2: Application) =>
        val appWithPendingSecondaryScopes = application1.withEmptyScopes.withSecondaryPendingScopes
        val appWithPendingPrimaryScopes = application2.withEmptyScopes.withPrimaryPendingScopes.withPrimaryApprovedScopes

        deleteAll().futureValue
        insert(appWithPendingSecondaryScopes).futureValue
        insert(appWithPendingPrimaryScopes).futureValue

        val response = wsClient
          .url(s"$baseUrl/api-hub-applications/applications/pending-primary-scopes")
          .addHttpHeaders(("Accept", "application/json"))
          .get()
          .futureValue

        // This expectation has to match what is stored and retrieved from MongoDb
        // The translation from Application to DbApplication does the following:
        //  1) Remove non-pending scopes
        //  2) Sets all secrets to None
        val expected = appWithPendingPrimaryScopes
          .setPrimaryScopes(
            appWithPendingPrimaryScopes
              .getPrimaryScopes
              .filter(_.status == Pending)
          )
          .setPrimaryCredentials(
            appWithPendingPrimaryScopes
              .getPrimaryCredentials
              .map(_.copy(clientSecret = None))
          )
          .setSecondaryCredentials(
            appWithPendingPrimaryScopes
              .getSecondaryCredentials
              .map(_.copy(clientSecret = None))
          )

        response.status shouldBe 200
        response.json shouldBe Json.toJson(Seq(expected))
      }
    }
  }

  "PUT change scope status from PENDING to APPROVED on primary environment" should {
    "respond with a 204 No Content when the status was set successfully" in {
      forAll { (application: Application) =>
        deleteAll().futureValue

        val appWithPendingPrimaryScope = application.withEmptyScopes.withPrimaryPendingScopes.withPrimaryApprovedScopes.withPrimaryCredentialClientIdOnly
        insert(appWithPendingPrimaryScope).futureValue

        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}/environments/primary/scopes/${application.pendingScopeName}")
            .addHttpHeaders(("Content-Type", "application/json"))
            .put(Json.toJson(UpdateScopeStatus(Approved)))
            .futureValue

        response.status shouldBe 204
      }
    }

    "must return 404 Not Found when trying to set scope on the application that does not exist" in {
      forAll { (_: Application) =>
        deleteAll().futureValue

        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/non-existent-app-id/environments/primary/scopes/test-scope-name")
            .addHttpHeaders(("Content-Type", "application/json"))
            .put(Json.toJson(UpdateScopeStatus(Approved)))
            .futureValue

        response.status shouldBe 404
      }
    }

    "must return 404 Not Found when trying to set scope status to APPROVED on an existing scope that is not PENDING" in {
      forAll { (application: Application) =>
        deleteAll().futureValue

        val appWithPendingPrimaryScope = application.withEmptyScopes.withPrimaryApprovedScopes
        insert(appWithPendingPrimaryScope).futureValue

        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}/environments/primary/scopes/${application.approvedScopeName}")
            .addHttpHeaders(("Content-Type", "application/json"))
            .put(Json.toJson(UpdateScopeStatus(Approved)))
            .futureValue

        response.status shouldBe 404
      }
    }

    "must return 404 Not Found when trying to set scope status on an environment other than primary" in {
      val response =
        wsClient
          .url(s"$baseUrl/api-hub-applications/applications/my-app-id/environments/secondary/scopes/test-scope-name")
          .addHttpHeaders(("Content-Type", "application/json"))
          .put(Json.toJson(UpdateScopeStatus(Approved)))
          .futureValue

      response.status shouldBe 404
    }

    "must return 400 Bad Request when trying to set scope status to anything other than APPROVED" in {
      val response =
        wsClient
          .url(s"$baseUrl/api-hub-applications/applications/my-app-id/environments/primary/scopes/test-scope-name")
          .addHttpHeaders(("Content-Type", "application/json"))
          .put(Json.toJson(UpdateScopeStatus(Pending)))
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

        val updatedApp = findAll().futureValue.head.decryptedValue
        updatedApp.toModel.getPrimaryCredentials.head.secretFragment mustBe Some("1234")
      }
    }

    "POST to add apis to an application" should {
      "respond with a 204 No Content" in {
        forAll { (application: Application) =>
          deleteAll().futureValue
          insert(application).futureValue

          val api = AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))

          val id = application.id.get
          val response =
            wsClient
              .url(s"$baseUrl/api-hub-applications/applications/$id/apis")
              .addHttpHeaders(("Content", "application/json"))
              .post(Json.toJson(api))
              .futureValue

          response.status shouldBe 204
        }
      }

      "respond with a 404 NotFound if the application does not exist" in {
        forAll { (application: Application) =>
          deleteAll().futureValue

          val api = AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))

          val response =
            wsClient
              .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}/apis")
              .addHttpHeaders(("Content", "application/json"))
              .post(Json.toJson(api))
              .futureValue

          response.status shouldBe 404
        }
      }
    }

    "POST to add primary credential to an application" should {
      "respond with a 200 with a credential" in {
        forAll { (application: Application) =>
          deleteAll().futureValue

          val appWithPendingPrimaryScope = application.withPrimaryCredentialClientIdOnly

          insert(appWithPendingPrimaryScope).futureValue

          val id = application.id.get
          val environmentName = Primary

          val response =
            wsClient
              .url(s"$baseUrl/api-hub-applications/applications/$id/environments/$environmentName/credentials")
              .addHttpHeaders(("Content", "application/json"))
              .post(EmptyBody)
              .futureValue

          response.status shouldBe 201
          val newCredential = response.json.validate[Credential] match {
            case JsSuccess(credential, _) => credential
            case _ => fail("No credential returned")
          }

          newCredential.clientSecret mustNot be(empty)
          newCredential.secretFragment mustNot be(empty)
        }
      }

      "respond with a 404 NotFound if the application does not exist" in {
        forAll { (application: Application) =>
          deleteAll().futureValue

          val response =
            wsClient
              .url(s"$baseUrl/api-hub-applications/applications/1234/environments/primary/credentials")
              .addHttpHeaders(("Content", "application/json"))
              .post(EmptyBody)
              .futureValue

          response.status shouldBe 404
        }
      }
    }
  }

  private def insert(application: Application): Future[InsertOneResult] = {
    insert(SensitiveApplication(DbApplication(application)))
  }

}
