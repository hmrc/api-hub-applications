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

package uk.gov.hmrc.apihubapplications.controllers

import akka.actor.ActorSystem
import akka.stream.Materializer
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar.mock
import org.mockito.{ArgumentMatchers, MockitoSugar}
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.http.Status
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{ControllerComponents, Request}
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import play.api.{Application => PlayApplication}
import sttp.model.StatusCode.NoContent
import uk.gov.hmrc.apihubapplications.controllers.ApplicationsControllerSpec._
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.idms.{IdmsException, Secret}
import uk.gov.hmrc.apihubapplications.models.requests.UpdateScopeStatus
import uk.gov.hmrc.apihubapplications.services.ApplicationsService
import uk.gov.hmrc.apihubapplications.utils.CryptoUtils
import uk.gov.hmrc.crypto.ApplicationCrypto

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class ApplicationsControllerSpec
  extends AnyFreeSpec
    with Matchers
    with MockitoSugar
    with OptionValues
    with CryptoUtils {

  "registerApplication" - {
    "must return 201 Created for a valid request" in {
      val fixture = buildFixture()
      running(fixture.application) {
        val newApplication = NewApplication(
          "test-app",
          Creator("test1@test.com"),
          Seq(TeamMember("test1@test.com"), TeamMember("test2@test.com"))
        )

        val json = Json.toJson(newApplication)

        val request: Request[JsValue] = FakeRequest(POST, routes.ApplicationsController.registerApplication.url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        val expected = Application(newApplication)
          .copy(id = Some("test-id"))

        when(fixture.applicationsService.registerApplication(ArgumentMatchers.eq(newApplication))(any()))
          .thenReturn(Future.successful(Right(expected)))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.CREATED
        contentAsJson(result) mustBe Json.toJson(expected)
      }
    }

    "must return 400 Bad Request when the JSON is not a valid application" in {
      val fixture = buildFixture()
      running(fixture.application) {
        val request: Request[JsValue] = FakeRequest(POST, routes.ApplicationsController.registerApplication.url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.obj())

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_REQUEST
      }
    }

    "must return 502 Bad Gateway if IDMS does not respond successfully" in {
      val fixture = buildFixture()
      running(fixture.application) {
        val newApplication = NewApplication(
          "test-app",
          Creator("test1@test.com"),
          Seq(TeamMember("test1@test.com"), TeamMember("test2@test.com"))
        )

        val json = Json.toJson(newApplication)

        val request: Request[JsValue] = FakeRequest(POST, routes.ApplicationsController.registerApplication.url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        when(fixture.applicationsService.registerApplication(any())(any()))
          .thenReturn(Future.successful(Left(IdmsException("test-message"))))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_GATEWAY
      }
    }
  }

  "retrieve all Applications" - {
    "must return 200 and a JSON array representing all applications in db" in {
      val fixture = buildFixture()
      val now = LocalDateTime.now()
      running(fixture.application) {
        val application1 = Application(Some("1"), "test-app-1", now, Creator("test1@test.com"), now, Seq.empty, Environments())
        val application2 = Application(Some("2"), "test-app-2", now, Creator("test2@test.com"), now, Seq.empty, Environments())

        val expected_apps = Seq(application1, application2)
        val expected_json = Json.toJson(expected_apps)

        val request = FakeRequest(GET, routes.ApplicationsController.getApplications(None).url)

        when(fixture.applicationsService.findAll()).thenReturn(Future.successful(expected_apps))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe expected_json
      }
    }

    "must return 200 and a JSON array representing all applications in db for the specified team member" in {
      val fixture = buildFixture()
      val now = LocalDateTime.now()
      running(fixture.application) {

        val teamMemberEmail = "jo.bloggs@hmrc.gov.uk"
        val expected_apps = Seq(Application(Some("1"), "test-app-1", now, Creator(teamMemberEmail), now, Seq(TeamMember(teamMemberEmail)), Environments()))
        val expected_json = Json.toJson(expected_apps)

        val crypto = fixture.application.injector.instanceOf[ApplicationCrypto]
        val request = FakeRequest(GET, routes.ApplicationsController.getApplications(
          Some(encrypt(crypto, teamMemberEmail))).url)

        when(fixture.applicationsService.filter(teamMemberEmail)).thenReturn(Future.successful(expected_apps))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe expected_json
      }
    }

  }

  "getApplication" - {
    "must return 200 Ok and a JSON body representing the application when it exists in the repository" in {
      val id = "1"
      val now = LocalDateTime.now()
      val expected = Application(Some(id), "test-app-1", now, Creator("test1@test.com"), now, Seq.empty, Environments())

      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.findById(any())(any())).thenReturn(Future.successful(Some(Right(expected))))

        val request = FakeRequest(GET, routes.ApplicationsController.getApplication(id).url)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe Json.toJson(expected)

        verify(fixture.applicationsService).findById(ArgumentMatchers.eq(id))(any())
      }
    }

    "must return 404 Not Found when the application does not exist in the repository" in {
      val id = "id"
      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.findById(any())(any())).thenReturn(Future.successful(None))

        val request = FakeRequest(GET, routes.ApplicationsController.getApplication(id).url)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.NOT_FOUND
      }
    }
  }

  "add scopes" - {
    "must return 204 NoContent" in {
      val id = "app-id-1"
      val scopes: Seq[NewScope] = Seq(
        NewScope("scope1", Seq(Secondary, Primary)),
        NewScope("scope2", Seq(Secondary))

      )
      val json = Json.toJson(scopes)
      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.addScopes(id, scopes)).thenReturn(Future.successful(true))

        val request = FakeRequest(POST, routes.ApplicationsController.addScopes(id).url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        val result = route(fixture.application, request).value
        status(result) mustBe NoContent.code

        verify(fixture.applicationsService).addScopes(ArgumentMatchers.eq(id), ArgumentMatchers.eq(scopes))
      }
    }

    "must return 404 Not Found when adding new scopes but the application does not exist in the repository" in {
      val id = "id"

      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.addScopes(any(), any())).thenReturn(Future.successful(false))

        val request = FakeRequest(POST, routes.ApplicationsController.addScopes(id).url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(Seq.empty[NewScope]))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.NOT_FOUND
      }
    }
    "must return 400 badRequest when adding new scopes with invalid environment value" in {
      val id = "id"
      val json = Json.parse(
        s"""
           |[
           |  {
           |    "name": "my-scope-name-1",
           |    "environments": ["secondary", "primary"]
           |  },
           |  {
           |    "name": "my-scope-name-2",
           |    "environments": ["invalid"]
           |  }
           |]
           |""".stripMargin)

      val fixture = buildFixture()
      running(fixture.application) {

        val request = FakeRequest(POST, routes.ApplicationsController.addScopes(id).url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)
        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_REQUEST
      }
    }

  }

  "pendingScopes" - {
    "must return 200 and only applications with pending production scopes" in {
      val application1 = testApplication
        .addProdScope(Scope("app-1-scope-1", Pending))
        .addProdScope(Scope("app-1-scope-2", Approved))

      val application6 = testApplication
        .addProdScope(Scope("app-6-scope-1", Pending))

      val expected = Seq(application1, application6)

      val fixture = buildFixture()
      when(fixture.applicationsService.getApplicationsWithPendingScope()).thenReturn(Future.successful(Seq(application1, application6)))

      running(fixture.application) {
        val request = FakeRequest(GET, routes.ApplicationsController.pendingScopes.url)

        val result = route(fixture.application, request).value

        status(result) mustBe Status.OK
        contentAsJson(result) mustBe Json.toJson(expected)
      }
    }
  }

  "set scope to APPROVED" - {
    "must return 204 NoContent" in {
      val appId = "1"
      val envName = "prod"
      val scopeName = "test-scope-name"
      val updateScope: UpdateScopeStatus = UpdateScopeStatus(Approved)
      val json = Json.toJson(updateScope)
      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.setPendingProdScopeStatusToApproved(appId, scopeName)).thenReturn(Future.successful(Some(true)))

        val request = FakeRequest(PUT, routes.ApplicationsController.approveProdScopeStatus(appId, envName, scopeName).url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        val result = route(fixture.application, request).value
        status(result) mustBe NoContent.code

        verify(fixture.applicationsService).setPendingProdScopeStatusToApproved(ArgumentMatchers.eq(appId), ArgumentMatchers.eq(scopeName))
      }
    }
    "must return 404 Not Found when trying to set scope status on the application that does not exist in DB" in {
      val appId = "not-exist"
      val envName = "prod"
      val scopeName = "test-scope-name"
      val updateScope: UpdateScopeStatus = UpdateScopeStatus(Approved)
      val json = Json.toJson(updateScope)
      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.setPendingProdScopeStatusToApproved(appId, scopeName)).thenReturn(Future.successful(Some(false)))

        val request = FakeRequest(PUT, routes.ApplicationsController.approveProdScopeStatus(appId, envName, scopeName).url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.NOT_FOUND

        verify(fixture.applicationsService).setPendingProdScopeStatusToApproved(ArgumentMatchers.eq(appId), ArgumentMatchers.eq(scopeName))
      }
    }
    "must return 404 Not Found when trying to set scope status to APPROVED on prod env when existing status is not PENDING" in {
      val appId = "not-exist"
      val envName = "prod"
      val scopeName = "test-scope-name"
      val updateScope: UpdateScopeStatus = UpdateScopeStatus(Approved)
      val json = Json.toJson(updateScope)
      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.setPendingProdScopeStatusToApproved(appId, scopeName)).thenReturn(Future.successful(None))

        val request = FakeRequest(PUT, routes.ApplicationsController.approveProdScopeStatus(appId, envName, scopeName).url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.NOT_FOUND

        verify(fixture.applicationsService).setPendingProdScopeStatusToApproved(ArgumentMatchers.eq(appId), ArgumentMatchers.eq(scopeName))
      }
    }

    "must return 400 Invalid Request when trying to set scope status on environment to other than prod" in {
      val appId = "not-exist"
      val envName = "dev"
      val scopeName = "test-scope-name"
      val updateScope: UpdateScopeStatus = UpdateScopeStatus(Approved)
      val json = Json.toJson(updateScope)
      val fixture = buildFixture()
      running(fixture.application) {

        val request = FakeRequest(PUT, routes.ApplicationsController.approveProdScopeStatus(appId, envName, scopeName).url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_REQUEST
      }
    }
    "must return 400 Invalid Request when trying to set scope status on prod environment to other than APPROVED" in {
      val appId = "not-exist"
      val envName = "prod"
      val scopeName = "test-scope-name"
      val updateScope: UpdateScopeStatus = UpdateScopeStatus(Pending)
      val json = Json.toJson(updateScope)
      val fixture = buildFixture()
      running(fixture.application) {
        val request = FakeRequest(PUT, routes.ApplicationsController.approveProdScopeStatus(appId, envName, scopeName).url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)
        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_REQUEST
      }
    }

  }

  "createPrimarySecret" - {
    "must return 200 Ok for a valid request" in {
      val fixture = buildFixture()
      running(fixture.application) {
        val applicationId = "app_id_12345"
        val request = FakeRequest(POST, routes.ApplicationsController.createPrimarySecret(applicationId).url).withHeaders(
          CONTENT_TYPE -> "application/json"
        )

        val expected = Secret("a new secret")
        when(fixture.applicationsService.createPrimarySecret(ArgumentMatchers.eq(applicationId))(any()))
          .thenReturn(Future.successful(Right(expected)))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe Json.toJson(expected)
      }
    }

    "must return 400 bad request when essential application data is missing" in {
      val fixture = buildFixture()
      running(fixture.application) {
        val applicationId = "app_id_12345"
        val request = FakeRequest(POST, routes.ApplicationsController.createPrimarySecret(applicationId).url).withHeaders(
          CONTENT_TYPE -> "application/json"
        )
        when(fixture.applicationsService.createPrimarySecret(ArgumentMatchers.eq(applicationId))(any()))
          .thenReturn(Future.successful(Left(ApplicationBadException("bad thing"))))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_REQUEST
      }
    }

    "must return 404 not found when application is missing" in {
      val fixture = buildFixture()
      running(fixture.application) {
        val applicationId = "app_id_12345"
        val request = FakeRequest(POST, routes.ApplicationsController.createPrimarySecret(applicationId).url).withHeaders(
          CONTENT_TYPE -> "application/json"
        )
        when(fixture.applicationsService.createPrimarySecret(ArgumentMatchers.eq(applicationId))(any()))
          .thenReturn(Future.successful(Left(ApplicationNotFoundException("bad thing"))))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.NOT_FOUND
      }
    }

    "must return 502 bad gateway when IDMS service fail" in {
      val fixture = buildFixture()
      running(fixture.application) {
        val applicationId = "app_id_12345"
        val request = FakeRequest(POST, routes.ApplicationsController.createPrimarySecret(applicationId).url).withHeaders(
          CONTENT_TYPE -> "application/json"
        )
        when(fixture.applicationsService.createPrimarySecret(ArgumentMatchers.eq(applicationId))(any()))
          .thenReturn(Future.successful(Left(IdmsException("bad thing"))))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_GATEWAY
      }
    }
  }
}


object ApplicationsControllerSpec {

  implicit val materializer: Materializer = Materializer(ActorSystem())

  case class Fixture(
                      application: PlayApplication,
                      applicationsService: ApplicationsService
                    )

  def buildFixture(): Fixture = {
    val applicationsService = mock[ApplicationsService]

    val application = new GuiceApplicationBuilder()
      .overrides(
        bind[ControllerComponents].toInstance(Helpers.stubControllerComponents()),
        bind[ApplicationsService].toInstance(applicationsService),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction])
      )
      .build()

    Fixture(application, applicationsService)
  }

  private val testCreator = Creator("test@email.com")

  def testApplication: Application = {
    Application(Some(UUID.randomUUID().toString), "test-app-name", testCreator, Seq(TeamMember(testCreator.email)))
  }

}
