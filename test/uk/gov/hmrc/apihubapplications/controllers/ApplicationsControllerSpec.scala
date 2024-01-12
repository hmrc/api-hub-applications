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
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.CallError
import uk.gov.hmrc.apihubapplications.models.exception._
import uk.gov.hmrc.apihubapplications.models.requests.{AddApiRequest, UserEmail}
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
          .addPrimaryCredential(Credential("test-client-id-1", LocalDateTime.now(), None, None))
          .addSecondaryCredential(Credential("test-client-id-2", LocalDateTime.now(), None, Some("test-fragment")))
          .copy(id = Some("test-id"))

        when(fixture.applicationsService.registerApplication(ArgumentMatchers.eq(newApplication))(any()))
          .thenReturn(Future.successful(Right(expected)))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.CREATED
        contentAsJson(result) mustBe Json.toJson(expected.makePublic())
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
        val application1 = Application(Some("1"), "test-app-1", now, Creator("test1@test.com"), now, Seq.empty, Environments())
        val application2 = Application(Some("2"), "test-app-2", now, Creator("test2@test.com"), now, Seq.empty, Environments())

        val expected_apps = Seq(application1, application2).zipWithIndex.map {
          case (application, index) =>
            application
              .setPrimaryCredentials(Seq(Credential(s"test-client-id-$index-1", LocalDateTime.now(), None, None)))
              .setSecondaryCredentials(Seq(Credential(s"test-client-id-$index-2", LocalDateTime.now(), None, Some("test-fragment"))))
        }

        val expected_json = Json.toJson(expected_apps.map(_.makePublic()))

        val request = FakeRequest(GET, routes.ApplicationsController.getApplications(None).url)

        when(fixture.applicationsService.findAll()).thenReturn(Future.successful(expected_apps))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe expected_json
      }
    }

    "must return 200 and a JSON array representing all applications in db in public form for the specified team member" in {
      val fixture = buildFixture()
      val now = LocalDateTime.now()
      running(fixture.application) {

        val teamMemberEmail = "jo.bloggs@hmrc.gov.uk"

        val expected_apps = Seq(
          Application(Some("1"), "test-app-1", now, Creator(teamMemberEmail), now, Seq(TeamMember(teamMemberEmail)), Environments())
            .setPrimaryCredentials(Seq(Credential("test-client-id-1", LocalDateTime.now(), None, None)))
            .setSecondaryCredentials(Seq(Credential("test-client-id-2", LocalDateTime.now(), None, Some("test-fragment"))))
        )

        val expected_json = Json.toJson(expected_apps.map(_.makePublic()))

        val crypto = fixture.application.injector.instanceOf[ApplicationCrypto]
        val request = FakeRequest(GET, routes.ApplicationsController.getApplications(
          Some(encrypt(crypto, teamMemberEmail))).url)

        when(fixture.applicationsService.filter(ArgumentMatchers.eq(teamMemberEmail), ArgumentMatchers.eq(false))(any()))
          .thenReturn(Future.successful(Right(expected_apps)))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe expected_json
      }
    }

    "must enrich and return applications for a team member when requested" in {
      val fixture = buildFixture()
      val now = LocalDateTime.now()
      running(fixture.application) {

        val teamMemberEmail = "jo.bloggs@hmrc.gov.uk"
        val expected_apps = Seq(Application(Some("1"), "test-app-1", now, Creator(teamMemberEmail), now, Seq(TeamMember(teamMemberEmail)), Environments()))

        val crypto = fixture.application.injector.instanceOf[ApplicationCrypto]
        val request = FakeRequest(GET, routes.ApplicationsController.getApplications(
          Some(encrypt(crypto, teamMemberEmail)),
          enrich = true
        ).url)

        when(fixture.applicationsService.filter(ArgumentMatchers.eq(teamMemberEmail), ArgumentMatchers.eq(true))(any()))
          .thenReturn(Future.successful(Right(expected_apps)))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe Json.toJson(expected_apps)
      }
    }

    "must return Bad Gateway when an IdmsException is encountered" in {
      val fixture = buildFixture()
      running(fixture.application) {
        val teamMemberEmail = "jo.bloggs@hmrc.gov.uk"

        val crypto = fixture.application.injector.instanceOf[ApplicationCrypto]
        val request = FakeRequest(GET, routes.ApplicationsController.getApplications(
          Some(encrypt(crypto, teamMemberEmail)),
          enrich = true
        ).url)

        when(fixture.applicationsService.filter(ArgumentMatchers.eq(teamMemberEmail), ArgumentMatchers.eq(true))(any()))
          .thenReturn(Future.successful(Left(IdmsException.clientNotFound("test-client-id"))))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_GATEWAY
      }
    }
  }

  "getApplication" - {
    "must return 200 Ok and a JSON body representing the application in public form when it exists in the repository" in {
      val id = "1"
      val now = LocalDateTime.now()
      val expected = Application(Some(id), "test-app-1", now, Creator("test1@test.com"), now, Seq.empty, Environments())
        .setPrimaryCredentials(Seq(Credential("test-client-id-1", LocalDateTime.now(), None, None)))
        .setSecondaryCredentials(Seq(Credential("test-client-id-2", LocalDateTime.now(), None, Some("test-fragment"))))

      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.findById(any(), any())(any())).thenReturn(Future.successful(Right(expected)))

        val request = FakeRequest(GET, routes.ApplicationsController.getApplication(id, enrich = true).url)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe Json.toJson(expected.makePublic())

        verify(fixture.applicationsService).findById(ArgumentMatchers.eq(id), ArgumentMatchers.eq(true))(any())
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

        val request = FakeRequest(GET, routes.ApplicationsController.getApplication(id, enrich = false).url)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe Json.toJson(expected)

        verify(fixture.applicationsService).findById(ArgumentMatchers.eq(id), ArgumentMatchers.eq(false))(any())
      }
    }
  }

  "deleteApplication" - {
    "must delete the application and return 204 No Content" in {
      val id = "test-id"
      val fixture = buildFixture()

      running(fixture.application) {
        when(fixture.applicationsService.delete(ArgumentMatchers.eq(id), ArgumentMatchers.eq(userEmail))(any()))
          .thenReturn(Future.successful(Right(())))

        val request = FakeRequest(POST, routes.ApplicationsController.deleteApplication(id).url)
          .withBody(userEmailRequestBody)
          .withHeaders(CONTENT_TYPE -> "application/json")
        val result = route(fixture.application, request).value

        status(result) mustBe NO_CONTENT
        verify(fixture.applicationsService).delete(any(), ArgumentMatchers.eq(userEmail))(any())
      }
    }

    "must return 404 Not Found when the application does not exist" in {
      val id = "test-id"
      val fixture = buildFixture()

      running(fixture.application) {
        when(fixture.applicationsService.delete(ArgumentMatchers.eq(id), any())(any()))
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
        when(fixture.applicationsService.delete(ArgumentMatchers.eq(id), any())(any()))
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
        when(fixture.applicationsService.delete(ArgumentMatchers.eq(id), any())(any()))
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
        when(fixture.applicationsService.delete(ArgumentMatchers.eq(id), any())(any()))
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
      val api = AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))
      val json = Json.toJson(api)
      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.addApi(ArgumentMatchers.eq(id), ArgumentMatchers.eq(api))(any())).thenReturn(Future.successful(Right(())))

        val request = FakeRequest(PUT, routes.ApplicationsController.addApi(id).url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
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
        when(fixture.applicationsService.addApi(any[String](), any())(any())).thenReturn(Future.successful(Right(())))

        val request = FakeRequest(PUT, routes.ApplicationsController.addApi(id).url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.toJson("{}"))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_REQUEST
      }
    }

    "must return 404 Not Found when adding new scopes but the application does not exist in the repository" in {
      val id = "id"
      val api = AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))
      val json = Json.toJson(api)
      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.addApi(any[String](), any())(any())).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(id))))

        val request = FakeRequest(PUT, routes.ApplicationsController.addApi(id).url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.NOT_FOUND
      }
    }

    "must return 500 Internal Server Error for unexpected application exceptions" in {
      val api = AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))
      val json = Json.toJson(api)
      val fixture = buildFixture()
      running(fixture.application) {
        when(fixture.applicationsService.addApi(any[String](), any())(any())).thenReturn(Future.successful(Left(UnexpectedApplicationsException)))

        val request = FakeRequest(PUT, routes.ApplicationsController.addApi("id").url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        val result = route(fixture.application, request).value
        status(result) mustBe INTERNAL_SERVER_ERROR
      }
    }
  }

  "add credential" - {
    "must return 201 and a credential" in {
      val id = "app-id-1"
      val fixture = buildFixture()
      val credential = Credential("clientId", LocalDateTime.now, Some("secret-1234"), Some("1234"))

      running(fixture.application) {
        when(fixture.applicationsService.addCredential(ArgumentMatchers.eq(id), ArgumentMatchers.eq(Primary))(any())).thenReturn(Future.successful(Right(credential)))

        val request = FakeRequest(POST, routes.ApplicationsController.addCredential(id, Primary).url)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.CREATED
        contentAsJson(result) mustBe Json.toJson(credential)
      }
    }

   "must return 404 Not Found when adding credential but the application does not exist in the repository" in {
      val id = "id"
      val fixture = buildFixture()

      running(fixture.application) {
        when(fixture.applicationsService.addCredential(ArgumentMatchers.eq(id), ArgumentMatchers.eq(Primary))(any())).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(id))))

        val request = FakeRequest(POST, routes.ApplicationsController.addCredential(id, Primary).url)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.NOT_FOUND
      }
    }

    "must return 500 Internal Server Error for unexpected application exceptions" in {
      val id = "id"
      val fixture = buildFixture()

      running(fixture.application) {
        when(fixture.applicationsService.addCredential(ArgumentMatchers.eq(id), ArgumentMatchers.eq(Primary))(any())).thenReturn(Future.successful(Left(UnexpectedApplicationsException)))

        val request = FakeRequest(POST, routes.ApplicationsController.addCredential(id, Primary).url)

        val result = route(fixture.application, request).value
        status(result) mustBe Status.INTERNAL_SERVER_ERROR
      }
    }

    "must return 409 Conflict when the application already has 5 credentials" in {
      val id = "id"
      val fixture = buildFixture()

      running(fixture.application) {
        when(fixture.applicationsService.addCredential(ArgumentMatchers.eq(id), ArgumentMatchers.eq(Primary))(any())).thenReturn(Future.successful(Left(ApplicationCredentialLimitException("too many credentials"))))

        val request = FakeRequest(POST, routes.ApplicationsController.addCredential(id, Primary).url)

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
        val request = FakeRequest(DELETE, routes.ApplicationsController.deleteCredential(applicationId, Primary, clientId).url)
        val result = route(fixture.application, request).value

        status(result) mustBe NO_CONTENT
        verify(fixture.applicationsService).deleteCredential(ArgumentMatchers.eq(applicationId), ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId))(any())
      }
    }

    "must return 404 Not Found when the application does not exist" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val clientId = "test-client-id"

      when(fixture.applicationsService.deleteCredential(any(), any(), any())(any())).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      running(fixture.application) {
        val request = FakeRequest(DELETE, routes.ApplicationsController.deleteCredential(applicationId, Primary, clientId).url)
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
        val request = FakeRequest(DELETE, routes.ApplicationsController.deleteCredential(applicationId, Primary, clientId).url)
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
        val request = FakeRequest(DELETE, routes.ApplicationsController.deleteCredential(applicationId, Primary, clientId).url)
        val result = route(fixture.application, request).value

        status(result) mustBe BAD_GATEWAY
      }
    }

    "must return 409 Conflict when an attempt is made to delete the last credential" in {
      val fixture = buildFixture()
      val applicationId = "test-application-id"
      val clientId = "test-client-id"

      when(fixture.applicationsService.deleteCredential(any(), any(), any())(any())).thenReturn(Future.successful(Left(ApplicationCredentialLimitException.forId(applicationId, Primary))))

      running(fixture.application) {
        val request = FakeRequest(DELETE, routes.ApplicationsController.deleteCredential(applicationId, Primary, clientId).url)
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
        val request = FakeRequest(DELETE, routes.ApplicationsController.deleteCredential(applicationId, Primary, clientId).url)
        val result = route(fixture.application, request).value

        status(result) mustBe INTERNAL_SERVER_ERROR
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

  case object UnexpectedApplicationsException extends ApplicationsException("unexpected-message", null)

}
