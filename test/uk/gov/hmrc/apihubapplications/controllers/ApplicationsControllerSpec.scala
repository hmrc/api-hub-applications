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
import org.mockito.{ArgumentMatchers, MockitoSugar}
import org.mockito.MockitoSugar.mock
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.{Application => PlayApplication}
import play.api.http.Status
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{ControllerComponents, Request}
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.apihubapplications.controllers.ApplicationsControllerSpec._
import uk.gov.hmrc.apihubapplications.models.Application
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class ApplicationsControllerSpec
  extends AnyFreeSpec
  with Matchers
  with MockitoSugar
  with OptionValues{



  "createApplication" - {
    "must return 201 Created for a valid request" in {
      val fixture = buildFixture()
      running(fixture.application) {
        val application = Application(None, "test-app")
        val json = Json.toJson(application)

        val request: Request[JsValue] = FakeRequest(POST, routes.ApplicationsController.createApplication.url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(json)

        val expected = application.copy(id = Some("test-id"))
        when(fixture.repository.insert(any())).thenReturn(Future.successful(expected))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.CREATED
        contentAsJson(result) mustBe Json.toJson(expected)

        verify(fixture.repository).insert(ArgumentMatchers.eq(application))
      }
    }

    "must return 400 Bad Request when the JSON is not a valid application" in {
      val fixture = buildFixture()
      running(fixture.application) {
        val request: Request[JsValue] = FakeRequest(POST, routes.ApplicationsController.createApplication.url)
          .withHeaders(
            CONTENT_TYPE -> "application/json"
          )
          .withBody(Json.obj())

        val result = route(fixture.application, request).value
        status(result) mustBe Status.BAD_REQUEST
      }}

  }
  "retrieve all Applications" - {
    "must return 200 and a JSON array representing all applications in db" in {
      val fixture = buildFixture()
      running(fixture.application) {
        val application1 = Application(Some("1"), "test-app-1")
        val application2 = Application(Some("2"), "test-app-2")

        val expected_apps = Seq(application1, application2)
        val expected_json = Json.toJson(expected_apps)

        val request = FakeRequest(GET, routes.ApplicationsController.getApplications.url)

        when(fixture.repository.findAll()).thenReturn(Future.successful(expected_apps))

        val result = route(fixture.application, request).value
        status(result) mustBe Status.OK
        contentAsJson(result) mustBe expected_json
      }}

  }

}

object ApplicationsControllerSpec {

  implicit val materializer: Materializer = Materializer(ActorSystem())

  case class Fixture(
    application: PlayApplication,
    repository: ApplicationsRepository
  )

  def buildFixture(): Fixture = {
    val repository = mock[ApplicationsRepository]
    val application = new GuiceApplicationBuilder()
      .overrides(
        bind[ControllerComponents].toInstance(Helpers.stubControllerComponents()),
        bind[ApplicationsRepository].toInstance(repository)
      )
      .build()

    Fixture(application, repository)
  }

}
