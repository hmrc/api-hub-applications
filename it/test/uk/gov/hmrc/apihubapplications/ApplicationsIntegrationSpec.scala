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
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.http.Status.{BAD_REQUEST, NOT_FOUND, NO_CONTENT}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.libs.ws.DefaultBodyWritables.writeableOf_String
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import play.api.libs.ws.WSClient
import play.api.test.Helpers.CONTENT_TYPE
import play.api.Application as GuideApplication
import uk.gov.hmrc.apihubapplications.connectors.{EmailConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.crypto.NoCrypto
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.requests.UserEmail
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.repositories.models.application.encrypted.SensitiveApplication
import uk.gov.hmrc.apihubapplications.repositories.models.application.unencrypted.DbApplication
import uk.gov.hmrc.apihubapplications.testhelpers.{ApplicationGenerator, FakeEmailConnector, FakeHipEnvironments, FakeIdmsConnector}
import uk.gov.hmrc.crypto.{ApplicationCrypto, PlainText}
import uk.gov.hmrc.mongo.MongoComponent
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

  private lazy val wsClient = app.injector.instanceOf[WSClient]
  private val baseUrl = s"http://localhost:$port"
  private lazy val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
  private lazy val playApplication = {
    GuiceApplicationBuilder()
      .overrides(
        bind[ApplicationsRepository].toInstance(applicationRepository),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction]),
        bind[IdmsConnector].to(classOf[FakeIdmsConnector]),
        bind[EmailConnector].to(classOf[FakeEmailConnector]),
        bind[Clock].toInstance(clock)
      )
      .configure("metrics.enabled" -> false)
      .build()
  }
  override def fakeApplication(): GuideApplication = playApplication

  override protected val repository: ApplicationsRepository = applicationRepository

  private def applicationRepository: ApplicationsRepository = ApplicationsRepository(mongoComponent, NoCrypto, FakeHipEnvironments)

  "POST to register a new application" should {
    "respond with a 201 Created and body containing the application" in {
      forAll { (newApplication: NewApplication) =>
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
      forAll { (newApplication: NewApplication) =>

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
        val storedApplication = storedApplications.head.decryptedValue.toModel(FakeHipEnvironments)

        val expectedTeamMembers =
          if (newApplication.teamMembers.contains(TeamMember(newApplication.createdBy.email))) {
            newApplication.teamMembers
          }
          else {
            newApplication.teamMembers :+ TeamMember(newApplication.createdBy.email)
          }

        val expectedApplication = storedApplication
          .makePublic(FakeHipEnvironments)

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

        val storedApplications: Seq[Application] = findAll().futureValue.map(_.decryptedValue.toModel(FakeHipEnvironments))

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

      val application1: Application = new Application(id = None, name = "app1", created = LocalDateTime.now, createdBy = Creator("creator@digital.hmrc.gov.uk"), lastUpdated = LocalDateTime.now(), None, teamMembers = myTeamMembers, environments = Environments(), apis = Seq.empty, deleted = None, teamName = None, credentials = Set.empty)
      val otherTeamMembers = Seq(TeamMember("member3@digital.hmrc.gov.uk"), TeamMember("member4@digital.hmrc.gov.uk"))

      val application2 = new Application(id = None, name = "app2", created = LocalDateTime.now, createdBy = Creator("creator@digital.hmrc.gov.uk"), lastUpdated = LocalDateTime.now(), None, teamMembers = otherTeamMembers, environments = Environments(), apis = Seq.empty, deleted = None, teamName = None, credentials = Set.empty)
      deleteAll().futureValue
      val crypto = fakeApplication().injector.instanceOf[ApplicationCrypto]
      insert(application1).futureValue
      insert(application2).futureValue

      val storedApplications: Seq[Application] = findAll().futureValue.map(_.decryptedValue.toModel(FakeHipEnvironments))
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
            .setCredentials(FakeHipEnvironments.primaryEnvironment, Seq(Credential(FakeIdmsConnector.fakeClientId, LocalDateTime.now(), None, None, FakeHipEnvironments.primaryEnvironment.id)))
            .setScopes(FakeHipEnvironments.primaryEnvironment, Seq.empty)
            .setCredentials(FakeHipEnvironments.secondaryEnvironment, Seq(Credential(FakeIdmsConnector.fakeClientId, LocalDateTime.now(), None, None, FakeHipEnvironments.secondaryEnvironment.id)))
        ).futureValue

        val storedApplication = findAll().futureValue.head.decryptedValue.toModel(FakeHipEnvironments)

        val expected = storedApplication
          .setCredentials(
            FakeHipEnvironments.secondaryEnvironment, 
            storedApplication
              .getCredentials(FakeHipEnvironments.secondaryEnvironment).map(
                credential => credential.copy(
                  clientSecret = Some(FakeIdmsConnector.fakeSecret),
                  secretFragment = Some(FakeIdmsConnector.fakeSecret.takeRight(4))
                )
              )
          )
          .setScopes(
            FakeHipEnvironments.primaryEnvironment, 
            Seq(
              Scope(FakeIdmsConnector.fakeClientScopeId1),
              Scope(FakeIdmsConnector.fakeClientScopeId2)
            )
          )
          .setScopes(
            FakeHipEnvironments.secondaryEnvironment, 
            Seq(
              Scope(FakeIdmsConnector.fakeClientScopeId1),
              Scope(FakeIdmsConnector.fakeClientScopeId2)
            )
          )
          .makePublic(FakeHipEnvironments)

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
    "hard delete the application and respond with 204 No Content when successful" in {
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
        storedApplications.size shouldBe 0
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

  private def insert(application: Application): Future[InsertOneResult] = {
    insert(SensitiveApplication(DbApplication(application)))
  }

}
