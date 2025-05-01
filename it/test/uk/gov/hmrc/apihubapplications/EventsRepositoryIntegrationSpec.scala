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

package uk.gov.hmrc.apihubapplications

import org.bson.types.ObjectId
import org.mongodb.scala.model.Filters
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.{EitherValues, OptionValues}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.apihubapplications.models.event.*
import uk.gov.hmrc.apihubapplications.models.exception.EventNotFoundException
import uk.gov.hmrc.apihubapplications.repositories.EventsRepository
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.ExecutionContext

class EventsRepositoryIntegrationSpec
  extends AnyFreeSpec
    with Matchers
    with DefaultPlayMongoRepositorySupport[SensitiveEvent]
    with MdcTesting
    with OptionValues
    with EitherValues {

  import EventsRepositoryIntegrationSpec.*

  private lazy val playApplication = {
    new GuiceApplicationBuilder()
      .overrides(
        bind[MongoComponent].toInstance(mongoComponent)
      )
      .build()
  }

  private implicit lazy val executionContext: ExecutionContext = {
    playApplication.injector.instanceOf[ExecutionContext]
  }

  override protected val repository: EventsRepository = {
    playApplication.injector.instanceOf[EventsRepository]
  }

  "insert" - {
    "must persist an Event in MongoDb" in {
      setMdcData()

      val result = repository
        .insert(event1)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data.id mustBe defined
      result.mdcData mustBe testMdcData

      val persisted = find(Filters.equal("_id", new ObjectId(result.data.id.value))).futureValue.head.decryptedValue
      persisted mustEqual result.data
    }
  }


    "findByUser" - {
      "must retrieve all users events from MongoDb" in {
        setMdcData()

        val saved1 = repository.insert(event1).futureValue
        val saved2 = repository.insert(event2).futureValue
        val saved3 = repository.insert(event3).futureValue

        val result = repository
          .findByUser(user)
          .map(ResultWithMdcData(_))
          .futureValue

        result.data must contain theSameElementsAs Set(saved1, saved2)
        result.mdcData mustBe testMdcData
      }
    }

  "findById" - {
    "must find the event" in {
      setMdcData()

      val saved1 = repository.insert(event1).futureValue
      val saved2 = repository.insert(event2).futureValue
      val saved3 = repository.insert(event3).futureValue

      val result = repository
        .findById(saved1.id.get)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Right(saved1)
      result.mdcData mustBe testMdcData
    }

    "must raise an exception when not found" in {
      setMdcData()

      val saved1 = repository.insert(event1).futureValue
      val saved2 = repository.insert(event2).futureValue
      val saved3 = repository.insert(event3).futureValue

      val missingId = UUID.randomUUID().toString
      val result = repository
        .findById(missingId)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data mustBe Left(EventNotFoundException.forId(missingId))
      result.mdcData mustBe testMdcData
    }
  }

  "findByEntity" - {
    "must retrieve all users events from MongoDb" in {
      setMdcData()

      val saved1 = repository.insert(event1).futureValue
      val saved2 = repository.insert(event2).futureValue
      val saved3 = repository.insert(event3).futureValue

      val result = repository
        .findByEntity(Application, entityId1)
        .map(ResultWithMdcData(_))
        .futureValue

      result.data must contain theSameElementsAs Set(saved1)
      result.mdcData mustBe testMdcData
    }
  }

}

object EventsRepositoryIntegrationSpec {

  val entityId1 = UUID.randomUUID().toString
  val entityId2 = UUID.randomUUID().toString
  val entityId3 = UUID.randomUUID().toString
  val user = "a.user@digital.hmrc.gov.uk"
  val event1 = Event(
    id = None,
    entityId = entityId1,
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

  val event2 = event1.copy(entityId = entityId1, entityType = Team)

  val event3 = event1.copy(entityId = entityId2, entityType = Team, user = "a.different.user@digital.hmrc.gov.uk")
}
