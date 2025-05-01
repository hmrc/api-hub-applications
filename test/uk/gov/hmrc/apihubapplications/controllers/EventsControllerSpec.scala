/*
 * Copyright 2024 HM Revenue & Customs
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

import org.mockito.Mockito.when
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Application as PlayApplication
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.event.*
import uk.gov.hmrc.apihubapplications.services.{ApplicationsSearchService, EventsService}
import uk.gov.hmrc.apihubapplications.utils.CryptoUtils

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class EventsControllerSpec
  extends AnyFreeSpec
  with Matchers
  with MockitoSugar
  with OptionValues
  with CryptoUtils {

  import EventsControllerSpec.*

  "findById" - {
    "must call the service and return the saved event as JSON" in {
      val fixture = buildFixture()

      val id = UUID.randomUUID().toString
      when(fixture.eventsService.findById(id)).thenReturn(Future.successful(Some(event1)))

      running(fixture.application) {
        val request = FakeRequest(routes.EventsController.findById(id))

        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(event1)
      }
    }
  }


  "findByUser" - {
    "must call the service and return the saved event as JSON" in {
      val fixture = buildFixture()

      val user = "a.user@digital.hmrc.gov.uk"
      when(fixture.eventsService.findByUser(user)).thenReturn(Future.successful(Seq(event1, event2)))

      running(fixture.application) {
        val request = FakeRequest(routes.EventsController.findByUser(user))

        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(Seq(event1, event2))
      }
    }
  }


  "findByEntity" - {
    "must call the service and return the saved event as JSON" in {
      val fixture = buildFixture()

      val entityId = UUID.randomUUID().toString
      val entityType = "team"
      when(fixture.eventsService.findByEntity(entityId, Team)).thenReturn(Future.successful(Seq(event1, event3)))

      running(fixture.application) {
        val request = FakeRequest(routes.EventsController.findByEvent(entityType, entityId))

        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(Seq(event1, event3))
      }
    }
  }

  private case class Fixture(application: PlayApplication, eventsService: EventsService)

  private def buildFixture(): Fixture = {
    val eventsService = mock[EventsService]
    val applicationsService = mock[ApplicationsSearchService]

    val application = new GuiceApplicationBuilder()
      .overrides(
        bind[ControllerComponents].toInstance(stubControllerComponents()),
        bind[EventsService].toInstance(eventsService),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction])
      )
      .build()

    Fixture(application, eventsService)
  }

}

object EventsControllerSpec {

  val event1 = Event(
    id = None,
    entityId = UUID.randomUUID().toString,
    entityType = Application,
    eventType = Created,
    user = "a.user@digital.hmrc.gov.uk",
    timestamp = LocalDateTime.now(),
    description = "an application",
    detail = "some detail",
    parameters = Json.toJson("""
           {
            "someKey": {
              "a": "x",
              "b": 1
              }
           }
      """))

  val event2 = event1.copy(entityId = UUID.randomUUID().toString)
  val event3 = event1.copy(eventType = Deleted)
}
