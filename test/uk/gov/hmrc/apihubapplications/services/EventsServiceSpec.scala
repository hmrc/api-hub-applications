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

package uk.gov.hmrc.apihubapplications.services

import org.mockito.ArgumentMatchers.{anyString, eq as eqTo}
import org.mockito.Mockito.{verify, verifyNoInteractions, when}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.config.AppConfig
import uk.gov.hmrc.apihubapplications.models.event.*
import uk.gov.hmrc.apihubapplications.repositories.EventsRepository

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class EventsServiceSpec
  extends AsyncFreeSpec
  with Matchers
  with MockitoSugar
  with EitherValues {

  import EventsServiceSpec.*

  "log" - {
    "must log the event when the feature flag is enabled" in {
      val fixture = buildFixture()
      when(fixture.appConfig.eventsEnabled).thenReturn(true)
      val saved = event1.copy(id = Some("some id"))
      when(fixture.repository.insert(eqTo(event1))).thenReturn(Future.successful(saved))

      fixture.service.log(event1).map {
        result =>
          verify(fixture.repository).insert(event1)
          result mustBe()
      }
    }

    "must do nothing when the feature flag is disabled" in {
      val fixture = buildFixture()
      when(fixture.appConfig.eventsEnabled).thenReturn(false)

      fixture.service.log(event1).map {
        result => verifyNoInteractions(fixture.repository)
        result mustBe()
      }
    }
  }

  "find by user" - {
    "must delegate to the repository" in {
      val fixture = buildFixture()
      val user = "a.user@digital.hmrc.gov.uk"
      when(fixture.repository.findByUser(user)).thenReturn(Future.successful(Seq(event1, event2)))

      fixture.service.findByUser(user).map(
        result => result must contain theSameElementsAs Seq(event1, event2)
      )
    }
  }

  "find by id" - {
    "must delegate to the repository" in {
      val fixture = buildFixture()

      when(fixture.repository.findById(anyString())).thenReturn(Future.successful(Right(event1)))

      fixture.service.findById("an id").map(
        result => result mustBe Some(event1)
      )
    }
  }

  "find by entity" - {
    "must delegate to the repository" in {
      val fixture = buildFixture()

      val entityId = UUID.randomUUID().toString
      val entityType = Team

      when(fixture.repository.findByEntity(entityId, entityType)).thenReturn(Future.successful(Seq(event1, event2)))

      fixture.service.findByEntity(entityId, entityType).map(
        result => result must contain theSameElementsAs Seq(event1,event2)
      )
    }
  }


  private case class Fixture(
    repository: EventsRepository,
    service: EventsService,
    appConfig: AppConfig
  )

  private def buildFixture(): Fixture = {
    val repository = mock[EventsRepository]
    val appConfig = mock[AppConfig]

    val service = new EventsService(repository, appConfig)

    Fixture(repository, service, appConfig)
  }

}

object EventsServiceSpec {

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

}
