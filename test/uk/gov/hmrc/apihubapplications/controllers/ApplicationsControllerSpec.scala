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

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{verify, verifyNoInteractions, when}
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatestplus.mockito.MockitoSugar
import play.api.Application as PlayApplication
import play.api.http.Status
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{ControllerComponents, Request}
import play.api.test.Helpers.*
import play.api.test.{FakeRequest, Helpers}
import sttp.model.StatusCode.NoContent
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.controllers.ApplicationsControllerSpec.*
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.exception.*
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.CallError
import uk.gov.hmrc.apihubapplications.models.requests.{AddApiRequest, TeamMemberRequest, UserEmail}
import uk.gov.hmrc.apihubapplications.services.ApplicationsService
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments
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
    with TableDrivenPropertyChecks
    with CryptoUtils {

  private val userEmail = "me@test.com"
  private val userEmailRequestBody = Json.toJson(UserEmail(userEmail)).toString()

  "registerApplication" - {
    "must return 201 Created for a valid request and the application in public form" in {
      val fixture = buildFixture()
      running(fixture.application) {
        val newApplication = NewApplication(
          "test-app",
          Creator("test1@test.com"),
          Seq(TeamMember("test1@test.com"), TeamMember("test2@test.com"))
        )

        val json = Json.toJson(newApplication)

        val request: Request[JsValue] = FakeRequest(POST, routes.ApplicationsController.registerApplication().url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        val expected = Application(newApplication)
          .addCredential(FakeHipEnvironments.productionEnvironment, Credential("test-client-id-1", LocalDateTime.now(), None, None, FakeHipEnvironments.productionEnvironment.id))
          .addCredential(FakeHipEnvironments.testEnvironment, Credential("test-client-id-2", LocalDateTime.now(), None, Some("test-fragment"), FakeHipEnvironments.testEnvironment.id))
          .copy(id = Some("test-id"))

        when(fixture.applicationsService.registerApplication(eqTo(newApplication))(any()))
          .thenReturn(Future.successful(Right(expected)))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.CREATED
        contentAsJson(result) mustBe Json.toJson(expected)
      }
    }

    "must return 400 Bad Request when the JSON is not a valid application" in {
      val fixture = buildFixture()
      running(fixture.application) {
        val request: Request[JsValue] = FakeRequest(POST, routes.ApplicationsController.registerApplication().url)
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

        val request: Request[JsValue] = FakeRequest(POST, routes.ApplicationsController.registerApplication().url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        when(fixture.applicationsService.registerApplication(any())(any()))
          .thenReturn(Future.successful(Left(IdmsException("test-message", CallError))))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_GATEWAY
      }
    }

    "must return 500 Internal Server Error for unexpected application exceptions" in {
      val fixture = buildFixture()
      val newApplication = NewApplication(
        "test-app",
        Creator("test1@test.com"),
        Seq(TeamMember("test1@test.com"), TeamMember("test2@test.com"))
      )

      val json = Json.toJson(newApplication)

      when(fixture.applicationsService.registerApplication(any())(any()))
        .thenReturn(Future.successful(Left(UnexpectedApplicationsException)))

      running(fixture.application) {
        val request: Request[JsValue] = FakeRequest(POST, routes.ApplicationsController.registerApplication().url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        val result = route(fixture.application, request).value
        status(result) mustBe INTERNAL_SERVER_ERROR
      }
    }
  }

  "retrieve all Applications" - {
    "must return 200 and a JSON array representing all applications in db in public form" in {
      val fixture = buildFixture()
      val now = LocalDateTime.now()

      running(fixture.application) {
        val application1 = Application(Some("1"), "test-app-1", now, Creator("test1@test.com"), now, Seq.empty, Set.empty)
        val application2 = Application(Some("2"), "test-app-2", now, Creator("test2@test.com"), now, Seq.empty, Set.empty)

        val expected_apps = Seq(application1, application2).zipWithIndex.map {
          case (application, index) =>
            application
              .setCredentials(FakeHipEnvironments.productionEnvironment, Seq(Credential(s"test-client-id-$index-1", LocalDateTime.now(), None, None, FakeHipEnvironments.productionEnvironment.id)))
              .setCredentials(FakeHipEnvironments.testEnvironment, Seq(Credential(s"test-client-id-$index-2", LocalDateTime.now(), None, Some("test-fragment"), FakeHipEnvironments.testEnvironment.id)))
        }

        val expected_json = Json.toJson(expected_apps)

        val request = FakeRequest(GET, routes.ApplicationsController.getApplications(None).url)

        when(fixture.applicationsService.findAll(any(), any())).thenReturn(Future.successful(expected_apps))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe expected_json
        verify(fixture.applicationsService).findAll(eqTo(None), eqTo(false))
      }
    }

    "must return 200 and a JSON array representing all applications in db in public form for the specified team member" in {
      val fixture = buildFixture()
      val now = LocalDateTime.now()
      running(fixture.application) {

        val teamMemberEmail = "jo.bloggs@hmrc.gov.uk"

        val expected_apps = Seq(
          Application(Some("1"), "test-app-1", now, Creator(teamMemberEmail), now, Seq(TeamMember(teamMemberEmail)), Set.empty)
            .setCredentials(FakeHipEnvironments.productionEnvironment, Seq(Credential("test-client-id-1", LocalDateTime.now(), None, None, FakeHipEnvironments.productionEnvironment.id)))
            .setCredentials(FakeHipEnvironments.testEnvironment, Seq(Credential("test-client-id-2", LocalDateTime.now(), None, Some("test-fragment"), FakeHipEnvironments.testEnvironment.id)))
        )

        val expected_json = Json.toJson(expected_apps)

        val request = FakeRequest(GET, routes.ApplicationsController.getApplications(
          Some(encrypt(fixture.crypto, teamMemberEmail))).url)

        when(fixture.applicationsService.findAll(eqTo(Some(teamMemberEmail)), eqTo(false)))
          .thenReturn(Future.successful(expected_apps))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe expected_json
      }
    }

    "must return deleted applications in public form when requested" in {
      val fixture = buildFixture()
      val now = LocalDateTime.now()
      val deleted = Deleted(now, "test-deleted-by")

      running(fixture.application) {
        val application1 = Application(Some("1"), "test-app-1", now, Creator("test1@test.com"), now, Seq.empty, Set.empty)
        val application2 = Application(Some("2"), "test-app-2", now, Creator("test2@test.com"), now, Seq.empty, Set.empty).delete(deleted)

        val expected_apps = Seq(application1, application2).zipWithIndex.map {
          case (application, index) =>
            application
              .setCredentials(FakeHipEnvironments.productionEnvironment, Seq(Credential(s"test-client-id-$index-1", LocalDateTime.now(), None, None, FakeHipEnvironments.productionEnvironment.id)))
              .setCredentials(FakeHipEnvironments.testEnvironment, Seq(Credential(s"test-client-id-$index-2", LocalDateTime.now(), None, Some("test-fragment"), FakeHipEnvironments.testEnvironment.id)))
        }

        val expected_json = Json.toJson(expected_apps)

        val request = FakeRequest(GET, routes.ApplicationsController.getApplications(None, true).url)

        when(fixture.applicationsService.findAll(any(), any())).thenReturn(Future.successful(expected_apps))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe expected_json
        verify(fixture.applicationsService).findAll(eqTo(None), eqTo(true))
      }
    }
  }

  "find all applications with an API" - {
    "must return 200 and a JSON array representing all applications in db that have the API in public form not including deleted apps" in {
      val fixture = buildFixture()
      val now = LocalDateTime.now()
      val apiId = "my-api"

      running(fixture.application) {
        val application1 = Application(Some("1"), "test-app-1", now, Creator("test1@test.com"), now, Seq.empty, Set.empty).addApi(Api(apiId, "api1"))
        val application2 = Application(Some("2"), "test-app-2", now, Creator("test2@test.com"), now, Seq.empty, Set.empty).addApi(Api(apiId, "api2"))

        val expected_apps = Seq(application1, application2).zipWithIndex.map {
          case (application, index) =>
            application
              .setCredentials(FakeHipEnvironments.productionEnvironment, Seq(Credential(s"test-client-id-$index-1", LocalDateTime.now(), None, None, FakeHipEnvironments.productionEnvironment.id)))
              .setCredentials(FakeHipEnvironments.testEnvironment, Seq(Credential(s"test-client-id-$index-2", LocalDateTime.now(), None, Some("test-fragment"), FakeHipEnvironments.testEnvironment.id)))
        }

        val expected_json = Json.toJson(expected_apps)

        val request = FakeRequest(GET, routes.ApplicationsController.getApplicationsUsingApi(apiId).url)

        when(fixture.applicationsService.findAllUsingApi(any(), any())).thenReturn(Future.successful(expected_apps))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe expected_json
        verify(fixture.applicationsService).findAllUsingApi(eqTo(apiId), eqTo(false))
      }
    }

    "must return 200 and a JSON array representing all applications in db that have the API in public form including deleted apps" in {
      val fixture = buildFixture()
      val now = LocalDateTime.now()
      val apiId = "my-api"

      running(fixture.application) {
        val application1 = Application(Some("1"), "test-app-1", now, Creator("test1@test.com"), now, Seq.empty, Set.empty).addApi(Api(apiId, "api1"))
        val application2 = Application(Some("2"), "test-app-2", now, Creator("test2@test.com"), now, Seq.empty, Set.empty).addApi(Api(apiId, "api2"))
          .delete(Deleted(now, "test-deleted-by"))

        val expected_apps = Seq(application1, application2).zipWithIndex.map {
          case (application, index) =>
            application
              .setCredentials(FakeHipEnvironments.productionEnvironment, Seq(Credential(s"test-client-id-$index-1", LocalDateTime.now(), None, None, FakeHipEnvironments.productionEnvironment.id)))
              .setCredentials(FakeHipEnvironments.testEnvironment, Seq(Credential(s"test-client-id-$index-2", LocalDateTime.now(), None, Some("test-fragment"), FakeHipEnvironments.testEnvironment.id)))
        }

        val expected_json = Json.toJson(expected_apps)

        val request = FakeRequest(GET, routes.ApplicationsController.getApplicationsUsingApi(apiId, true).url)

        when(fixture.applicationsService.findAllUsingApi(any(), any())).thenReturn(Future.successful(expected_apps))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe expected_json
        verify(fixture.applicationsService).findAllUsingApi(eqTo(apiId), eqTo(true))
      }
    }
  }

  "getApplication" - {
    "must return 200 Ok and a JSON body representing the application in public form when it exists in the repository" in {
      val id = "1"
      val now = LocalDateTime.now()
      val expected = Application(Some(id), "test-app-1", now, Creator("test1@test.com"), now, Seq.empty, Set.empty)
        .setCredentials(FakeHipEnvironments.productionEnvironment, Seq(Credential("test-client-id-1", LocalDateTime.now(), None, None, FakeHipEnvironments.productionEnvironment.id)))
        .setCredentials(FakeHipEnvironments.testEnvironment, Seq(Credential("test-client-id-2", LocalDateTime.now(), None, Some("test-fragment"), FakeHipEnvironments.testEnvironment.id)))

      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.findById(any(), any())(any())).thenReturn(Future.successful(Right(expected)))

        val request = FakeRequest(GET, routes.ApplicationsController.getApplication(id).url)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe Json.toJson(expected)

        verify(fixture.applicationsService).findById(eqTo(id), eqTo(false))(any())
      }
    }

    "must return 404 Not Found when the application does not exist in the repository" in {
      val id = "id"
      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.findById(any(), any())(any())).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(id))))

        val request = FakeRequest(GET, routes.ApplicationsController.getApplication(id).url)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.NOT_FOUND
      }
    }

    "must return 500 Internal Server Error for unexpected application exceptions" in {
      val id = "id"
      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.findById(any(), any())(any())).thenReturn(Future.successful(Left(UnexpectedApplicationsException)))

        val request = FakeRequest(GET, routes.ApplicationsController.getApplication(id).url)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.INTERNAL_SERVER_ERROR
      }
    }

    "must not enrich with IDMS data unless asked to" in {
      val id = "1"
      val expected = Application(Some(id), "test-app-1", Creator("test1@test.com"), Seq.empty)

      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.findById(any(), any())(any())).thenReturn(Future.successful(Right(expected)))

        val request = FakeRequest(GET, routes.ApplicationsController.getApplication(id).url)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe Json.toJson(expected)

        verify(fixture.applicationsService).findById(eqTo(id), eqTo(false))(any())
      }
    }
  }

  "deleteApplication" - {
    "must delete the application and return 204 No Content" in {
      val id = "test-id"
      val fixture = buildFixture()

      running(fixture.application) {
        when(fixture.applicationsService.delete(eqTo(id), eqTo(userEmail))(any()))
          .thenReturn(Future.successful(Right(())))

        val request = FakeRequest(POST, routes.ApplicationsController.deleteApplication(id).url)
          .withBody(userEmailRequestBody)
          .withHeaders(CONTENT_TYPE -> "application/json")
        val result = route(fixture.application, request).value

        status(result) mustBe NO_CONTENT
        verify(fixture.applicationsService).delete(any(), eqTo(userEmail))(any())
      }
    }

    "must return 404 Not Found when the application does not exist" in {
      val id = "test-id"
      val fixture = buildFixture()

      running(fixture.application) {
        when(fixture.applicationsService.delete(eqTo(id), any())(any()))
          .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(id))))

        val request = FakeRequest(POST, routes.ApplicationsController.deleteApplication(id).url)
          .withBody(userEmailRequestBody)
          .withHeaders(CONTENT_TYPE -> "application/json")
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return 502 Bad Gateway if an IDMS exception is encountered" in {
      val id = "test-id"
      val fixture = buildFixture()

      running(fixture.application) {
        when(fixture.applicationsService.delete(eqTo(id), any())(any()))
          .thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))

        val request = FakeRequest(POST, routes.ApplicationsController.deleteApplication(id).url)
          .withBody(userEmailRequestBody)
          .withHeaders(CONTENT_TYPE -> "application/json")

        val result = route(fixture.application, request).value

        status(result) mustBe BAD_GATEWAY
      }
    }

    "must return 500 Internal Server Error for any unexpected exception" in {
      val id = "test-id"
      val fixture = buildFixture()

      running(fixture.application) {
        when(fixture.applicationsService.delete(eqTo(id), any())(any()))
          .thenReturn(Future.successful(Left(UnexpectedApplicationsException)))

        val request = FakeRequest(POST, routes.ApplicationsController.deleteApplication(id).url)
          .withBody(userEmailRequestBody)
          .withHeaders(CONTENT_TYPE -> "application/json")
        val result = route(fixture.application, request).value

        status(result) mustBe INTERNAL_SERVER_ERROR
      }
    }

    "must return 400 Bad Request for missing user email request body" in {
      val id = "test-id"
      val fixture = buildFixture()

      running(fixture.application) {
        when(fixture.applicationsService.delete(eqTo(id), any())(any()))
          .thenReturn(Future.successful(Left(UnexpectedApplicationsException)))

        val request = FakeRequest(POST, routes.ApplicationsController.deleteApplication(id).url).withHeaders(CONTENT_TYPE -> "application/json")
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_REQUEST
      }
    }

  }

  "add api" - {
    "must return 204 NoContent" in {
      val id = "app-id-1"
      val api = AddApiRequest("api_id", "api_title", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))
      val json = Json.toJson(api)
      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.addApi(eqTo(id), eqTo(api), eqTo(testUserEmail))(any())).thenReturn(Future.successful(Right(())))

        val request = FakeRequest(PUT, routes.ApplicationsController.addApi(id).url)
          .withHeaders(
            CONTENT_TYPE -> "application/json",
            userEmailHeader -> encrypt(fixture.crypto, testUserEmail)
          )
          .withBody(json)

        val result = route(fixture.application, request).value
        status(result) mustBe NoContent.code
      }
    }

    "must return 400 bad request when adding no api" in {
      val id = "id"

      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.addApi(any[String](), any(), any())(any())).thenReturn(Future.successful(Right(())))

        val request = FakeRequest(PUT, routes.ApplicationsController.addApi(id).url)
          .withHeaders(
            CONTENT_TYPE -> "application/json",
            userEmailHeader -> encrypt(fixture.crypto, testUserEmail)
          )
          .withBody(Json.toJson("{}"))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_REQUEST
      }
    }

    "must return 404 Not Found when adding new scopes but the application does not exist in the repository" in {
      val id = "id"
      val api = AddApiRequest("api_id", "api_title", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))
      val json = Json.toJson(api)
      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.addApi(any[String](), any(), any())(any())).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(id))))

        val request = FakeRequest(PUT, routes.ApplicationsController.addApi(id).url)
          .withHeaders(
            CONTENT_TYPE -> "application/json",
            userEmailHeader -> encrypt(fixture.crypto, testUserEmail)
          )
          .withBody(json)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.NOT_FOUND
      }
    }

    "must return 500 Internal Server Error for unexpected application exceptions" in {
      val api = AddApiRequest("api_id", "api_title", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))
      val json = Json.toJson(api)
      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.addApi(any[String](), any(), any())(any())).thenReturn(Future.successful(Left(UnexpectedApplicationsException)))

        val request = FakeRequest(PUT, routes.ApplicationsController.addApi("id").url)
          .withHeaders(
            CONTENT_TYPE -> "application/json",
            userEmailHeader -> encrypt(fixture.crypto, testUserEmail)
          )
          .withBody(json)

        val result = route(fixture.application, request).value
        status(result) mustBe INTERNAL_SERVER_ERROR
      }
    }

    "must return 401 Unauthorized when the user email header is not present" in {
      val id = "app-id-1"
      val api = AddApiRequest("api_id", "api_title", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))
      val json = Json.toJson(api)
      val fixture = buildFixture()

      running(fixture.application) {
        val request = FakeRequest(PUT, routes.ApplicationsController.addApi(id).url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        val result = route(fixture.application, request).value
        status(result) mustBe UNAUTHORIZED
      }
    }
  }

  "removeApi" - {
    "must process the request and respond with No Content on success" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val apiId = "test-api-id"

      when(fixture.applicationsService.removeApi(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.removeApi(applicationId, apiId))
          .withHeaders(userEmailHeader -> encrypt(fixture.crypto, testUserEmail))
        val result = route(fixture.application, request).value

        status(result) mustBe NO_CONTENT

        verify(fixture.applicationsService).removeApi(eqTo(applicationId), eqTo(apiId), eqTo(testUserEmail))(any())
      }
    }

    "must respond with Not Found when the application does not exist" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val apiId = "test-api-id"

      when(fixture.applicationsService.removeApi(any(), any(), any())(any()))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.removeApi(applicationId, apiId))
          .withHeaders(userEmailHeader -> encrypt(fixture.crypto, testUserEmail))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must respond with Not Found when the API is not linked to the application" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val apiId = "test-api-id"

      when(fixture.applicationsService.removeApi(any(), any(), any())(any()))
        .thenReturn(Future.successful(Left(ApiNotFoundException.forApplication(applicationId, apiId))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.removeApi(applicationId, apiId))
          .withHeaders(userEmailHeader -> encrypt(fixture.crypto, testUserEmail))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return Bad Gateway when an IDMS exception is encountered" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val apiId = "test-api-id"

      when(fixture.applicationsService.removeApi(any(), any(), any())(any()))
        .thenReturn(Future.successful(Left(IdmsException.clientNotFound("test-client-id"))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.removeApi(applicationId, apiId))
          .withHeaders(userEmailHeader -> encrypt(fixture.crypto, testUserEmail))
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_GATEWAY
      }
    }

    "must return 401 Unauthorized when the user email header is not present" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val apiId = "test-api-id"

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.removeApi(applicationId, apiId))
        val result = route(fixture.application, request).value

        status(result) mustBe UNAUTHORIZED
      }
    }
  }

  "changeOwningTeam" - {
    "must process the request and respond with No Content on success" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val teamId = "team-id"

      when(fixture.applicationsService.changeOwningTeam(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.changeOwningTeam(applicationId, teamId))
          .withHeaders(userEmailHeader -> encrypt(fixture.crypto, testUserEmail))
        val result = route(fixture.application, request).value

        status(result) mustBe NO_CONTENT

        verify(fixture.applicationsService).changeOwningTeam(eqTo(applicationId), eqTo(teamId), eqTo(testUserEmail))(any())
      }
    }

    "must respond with Not Found when the application does not exist" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val teamId = "team-id"

      when(fixture.applicationsService.changeOwningTeam(any(), any(), any())(any()))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.changeOwningTeam(applicationId, teamId))
          .withHeaders(userEmailHeader -> encrypt(fixture.crypto, testUserEmail))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must respond with Not Found when the team does not exist" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val teamId = "team-id"

      when(fixture.applicationsService.changeOwningTeam(any(), any(), any())(any()))
        .thenReturn(Future.successful(Left(TeamNotFoundException.forId(teamId))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.changeOwningTeam(applicationId, teamId))
          .withHeaders(userEmailHeader -> encrypt(fixture.crypto, testUserEmail))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return 401 Unauthorized when the user email header is not present" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val teamId = "team-id"

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.changeOwningTeam(applicationId, teamId))
        val result = route(fixture.application, request).value

        status(result) mustBe UNAUTHORIZED
      }
    }
  }

  "get credentials" - {
    "must return 200 and credentials when the application exists" in {
      val fixture = buildFixture()

      val applicationId = "test-app-id"
      val hipEnvironment = FakeHipEnvironments.productionEnvironment

      val credentials = (1 to 2).map(
        i =>
          Credential(
            clientId = s"test-client-id$i",
            created = LocalDateTime.now(),
            clientSecret = Some(s"test-client-secret-$i"),
            secretFragment = Some(s"xxx$i"),
            environmentId = hipEnvironment.id
          )
      )

      when(fixture.applicationsService.getCredentials(eqTo(applicationId), eqTo(hipEnvironment))(any))
        .thenReturn(Future.successful(Right(credentials)))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.getCredentials(applicationId, hipEnvironment.id))
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(credentials)
      }
    }

    "must return 404 Not Found when the application does not exist" in {
      val fixture = buildFixture()

      val applicationId = "test-app-id"
      val hipEnvironment = FakeHipEnvironments.productionEnvironment

      when(fixture.applicationsService.getCredentials(eqTo(applicationId), eqTo(hipEnvironment))(any))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.getCredentials(applicationId, hipEnvironment.id))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return 404 Not Found when the environment is not known" in {
      val fixture = buildFixture()

      val applicationId = "test-app-id"

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.getCredentials(applicationId, "test-unknown-environment"))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
        verifyNoInteractions(fixture.applicationsService)
      }
    }
  }

  "add credential" - {
    "must return 201 and a credential" in {
      val id = "app-id-1"
      val fixture = buildFixture()
      val credential = Credential("clientId", LocalDateTime.now, Some("secret-1234"), Some("1234"), FakeHipEnvironments.productionEnvironment.id)

      running(fixture.application) {
        when(fixture.applicationsService.addCredential(eqTo(id), eqTo(FakeHipEnvironments.productionEnvironment))(any())).thenReturn(Future.successful(Right(credential)))

        val request = FakeRequest(POST, routes.ApplicationsController.addCredential(id, FakeHipEnvironments.productionEnvironment.id).url)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.CREATED
        contentAsJson(result) mustBe Json.toJson(credential)
      }
    }

    "must return 201 for an environment matching an EnvironmentName or a HipEnvironment Id and 404 otherwise" in {
      val id = "app-id-1"
      val fixture = buildFixture()

      val validEnvironmentNames = Table(
        ("environment", "status"),
        ("production", Status.CREATED),
        ("test", Status.CREATED),
        ("another one", Status.NOT_FOUND),
      )

      running(fixture.application) {
        forAll(validEnvironmentNames) { (environment, expectedStatus) =>
          val credential = Credential("clientId", LocalDateTime.now, Some("secret-1234"), Some("1234"), environment)

          when(fixture.applicationsService.addCredential(eqTo(id), any())(any())).thenReturn(Future.successful(Right(credential)))

          val request = FakeRequest(POST, routes.ApplicationsController.addCredential(id, environment).url)

          val result = route(fixture.application, request).value
          status(result) mustBe expectedStatus
        }
      }
    }
    
    "must return 404 Not Found when adding credential but the application does not exist in the repository" in {
      val id = "id"
      val fixture = buildFixture()

      running(fixture.application) {
        when(fixture.applicationsService.addCredential(eqTo(id), eqTo(FakeHipEnvironments.productionEnvironment))(any())).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(id))))

        val request = FakeRequest(POST, routes.ApplicationsController.addCredential(id, "primary").url)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.NOT_FOUND
      }
    }

    "must return 500 Internal Server Error for unexpected application exceptions" in {
      val id = "id"
      val fixture = buildFixture()

      running(fixture.application) {
        when(fixture.applicationsService.addCredential(eqTo(id), eqTo(FakeHipEnvironments.productionEnvironment))(any())).thenReturn(Future.successful(Left(UnexpectedApplicationsException)))

        val request = FakeRequest(POST, routes.ApplicationsController.addCredential(id, FakeHipEnvironments.productionEnvironment.id).url)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.INTERNAL_SERVER_ERROR
      }
    }

    "must return 409 Conflict when the application already has 5 credentials" in {
      val id = "id"
      val fixture = buildFixture()

      running(fixture.application) {
        when(fixture.applicationsService.addCredential(eqTo(id), eqTo(FakeHipEnvironments.productionEnvironment))(any())).thenReturn(Future.successful(Left(ApplicationCredentialLimitException("too many credentials"))))

        val request = FakeRequest(POST, routes.ApplicationsController.addCredential(id, FakeHipEnvironments.productionEnvironment.id).url)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.CONFLICT
      }
    }
  }

  "deleteCredential" - {
    "must delete the credential and return 204 No Content when successful" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val clientId = "test-client-id"

      when(fixture.applicationsService.deleteCredential(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      running(fixture.application) {
        val request = FakeRequest(DELETE, routes.ApplicationsController.deleteCredential(applicationId, FakeHipEnvironments.productionEnvironment.id, clientId).url)
        val result = route(fixture.application, request).value

        status(result) mustBe NO_CONTENT
        verify(fixture.applicationsService).deleteCredential(eqTo(applicationId), eqTo(FakeHipEnvironments.productionEnvironment), eqTo(clientId))(any())
      }
    }

    "must return 204 for an environment matching an EnvironmentName or a HipEnvironment Id and 404 otherwise" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val clientId = "test-client-id"

      val validEnvironmentNames = Table(
        ("environment", "status"),
        ("production", Status.NO_CONTENT),
        ("test", Status.NO_CONTENT),
        ("another one", Status.NOT_FOUND),
      )

      when(fixture.applicationsService.deleteCredential(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))
      
      running(fixture.application) {
        forAll(validEnvironmentNames) { (environment, expectedStatus) =>
          val request = FakeRequest(DELETE, routes.ApplicationsController.deleteCredential(applicationId, environment, clientId).url)
          val result = route(fixture.application, request).value

          status(result) mustBe expectedStatus
        }
      }
    }
    
    "must return 404 Not Found when the application does not exist" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val clientId = "test-client-id"

      when(fixture.applicationsService.deleteCredential(any(), any(), any())(any())).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      running(fixture.application) {
        val request = FakeRequest(DELETE, routes.ApplicationsController.deleteCredential(applicationId, "primary", clientId).url)
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return 404 Not Found when the credential does not exist" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val clientId = "test-client-id"

      when(fixture.applicationsService.deleteCredential(any(), any(), any())(any())).thenReturn(Future.successful(Left(CredentialNotFoundException.forClientId(clientId))))

      running(fixture.application) {
        val request = FakeRequest(DELETE, routes.ApplicationsController.deleteCredential(applicationId, "primary", clientId).url)
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return 502 Bad Gateway when IDMS responds with an error" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val clientId = "test-client-id"

      when(fixture.applicationsService.deleteCredential(any(), any(), any())(any())).thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))

      running(fixture.application) {
        val request = FakeRequest(DELETE, routes.ApplicationsController.deleteCredential(applicationId, FakeHipEnvironments.productionEnvironment.id, clientId).url)
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_GATEWAY
      }
    }

    "must return 409 Conflict when an attempt is made to delete the last credential" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val clientId = "test-client-id"

      when(fixture.applicationsService.deleteCredential(any(), any(), any())(any())).thenReturn(Future.successful(Left(ApplicationCredentialLimitException.forId(applicationId, FakeHipEnvironments.productionEnvironment))))

      running(fixture.application) {
        val request = FakeRequest(DELETE, routes.ApplicationsController.deleteCredential(applicationId, FakeHipEnvironments.productionEnvironment.id, clientId).url)
        val result = route(fixture.application, request).value

        status(result) mustBe CONFLICT
      }
    }

    "must return 500 Internal Server Error for other error conditions" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val clientId = "test-client-id"

      when(fixture.applicationsService.deleteCredential(any(), any(), any())(any())).thenReturn(Future.successful(Left(UnexpectedApplicationsException)))

      running(fixture.application) {
        val request = FakeRequest(DELETE, routes.ApplicationsController.deleteCredential(applicationId, FakeHipEnvironments.productionEnvironment.id, clientId).url)
        val result = route(fixture.application, request).value

        status(result) mustBe INTERNAL_SERVER_ERROR
      }
    }
  }

  "addTeamMember" - {
    "must pass on a valid request to the service layer and return success" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val teamMemberRequest = TeamMemberRequest("test-email")

      when(fixture.applicationsService.addTeamMember(any(), any())(any()))
        .thenReturn(Future.successful(Right(())))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.addTeamMember(applicationId))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(teamMemberRequest))
        val result = route(fixture.application, request).value

        status(result) mustBe NO_CONTENT
        verify(fixture.applicationsService).addTeamMember(eqTo(applicationId), eqTo(teamMemberRequest.toTeamMember))(any())
      }
    }

    "must return 404 Not Found when the application does not exist" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val teamMemberRequest = TeamMemberRequest("test-email")

      when(fixture.applicationsService.addTeamMember(any(), any())(any()))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.addTeamMember(applicationId))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(teamMemberRequest))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }

    "must return 400 Bad Request when the team member already exists in the application" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val teamMemberRequest = TeamMemberRequest("test-email")

      when(fixture.applicationsService.addTeamMember(any(), any())(any()))
        .thenReturn(Future.successful(Left(TeamMemberExistsException.forId(applicationId))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.addTeamMember(applicationId))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(teamMemberRequest))
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_REQUEST
      }
    }

    "must return 500 Internal Server Error when an exception is returned by the service layer" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val teamMemberRequest = TeamMemberRequest("test-email")

      when(fixture.applicationsService.addTeamMember(any(), any())(any()))
        .thenReturn(Future.successful(Left(UnexpectedApplicationsException)))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.addTeamMember(applicationId))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(teamMemberRequest))
        val result = route(fixture.application, request).value

        status(result) mustBe INTERNAL_SERVER_ERROR
      }
    }

    "must return 400 Bad Request when an invalid request body is submitted" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.addTeamMember(applicationId))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.obj())
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_REQUEST
        verifyNoInteractions(fixture.applicationsService)
      }
    }

    "must return 409 Conflict when the application has a global team (ie has been migrated)" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val teamMemberRequest = TeamMemberRequest("test-email")

      when(fixture.applicationsService.addTeamMember(any(), any())(any()))
        .thenReturn(Future.successful(Left(ApplicationTeamMigratedException.forId(applicationId))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.addTeamMember(applicationId))
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson(teamMemberRequest))
        val result = route(fixture.application, request).value

        status(result) mustBe CONFLICT
      }
    }
  }

  "fetchAllScopes" - {
    "must return 200 OK and the list of credential scopes on success" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val allScopes = (1 to 2).map(
        i =>
          CredentialScopes(
            environmentId = FakeHipEnvironments.productionEnvironment.id,
            clientId = s"test-client-id-$i",
            created = LocalDateTime.now(),
            scopes = Seq(s"test-scope-$i-1", s"test-scope-$i-2")
          )
      )

      when(fixture.applicationsService.fetchAllScopes(eqTo(applicationId))(any)).thenReturn(Future.successful(Right(allScopes)))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.fetchAllScopes(applicationId))
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(allScopes)
      }
    }

    "must return 404 Not Found when the application does not exist" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"

      when(fixture.applicationsService.fetchAllScopes(eqTo(applicationId))(any))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.fetchAllScopes(applicationId))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }
  }

  "fixScopes" - {
    "must return 202 No Content on success" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"

      when(fixture.applicationsService.fixScopes(eqTo(applicationId))(any))
        .thenReturn(Future.successful(Right(testApplication)))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.fixScopes(applicationId))
        val result = route(fixture.application, request).value

        status(result) mustBe NO_CONTENT
      }
    }

    "must return 404 Not Found when the application does not exist" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"

      when(fixture.applicationsService.fixScopes(eqTo(applicationId))(any))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      running(fixture.application) {
        val request = FakeRequest(routes.ApplicationsController.fixScopes(applicationId))
        val result = route(fixture.application, request).value

        status(result) mustBe NOT_FOUND
      }
    }
  }

}

object ApplicationsControllerSpec extends MockitoSugar {

  implicit val materializer: Materializer = Materializer(ActorSystem())

  case class Fixture(
                      application: PlayApplication,
                      applicationsService: ApplicationsService,
                      crypto: ApplicationCrypto
                    )

  def buildFixture(): Fixture = {
    val applicationsService = mock[ApplicationsService]

    val application = new GuiceApplicationBuilder()
      .overrides(
        bind[ControllerComponents].toInstance(Helpers.stubControllerComponents()),
        bind[ApplicationsService].toInstance(applicationsService),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction]),
        bind[HipEnvironments].toInstance(FakeHipEnvironments)
      )
      .build()

    val crypto = application.injector.instanceOf[ApplicationCrypto]

    Fixture(application, applicationsService, crypto)
  }

  private val testCreator = Creator("test@email.com")
  private val testUserEmail = "test-email"
  private val userEmailHeader = "Encrypted-User-Email"

  def testApplication: Application = {
    Application(Some(UUID.randomUUID().toString), "test-app-name", testCreator, Seq(TeamMember(testCreator.email)))
  }

  case object UnexpectedApplicationsException extends ApplicationsException("unexpected-message", null)

}
