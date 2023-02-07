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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.OptionValues
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.{Application => GuideApplication}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator, Environment, Environments}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import testhelpers.{ApplicationBuilder, NewApplicationBuilder}
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global

class ApplicationsIntegrationSpec
  extends AnyWordSpec
     with Matchers
     with OptionValues
     with GuiceOneServerPerSuite
     with DefaultPlayMongoRepositorySupport[Application] {

  private val wsClient = app.injector.instanceOf[WSClient]
  private val baseUrl  = s"http://localhost:$port"

  override def fakeApplication(): GuideApplication =
    GuiceApplicationBuilder()
      .overrides(
        bind[ApplicationsRepository].toInstance(repository)
      )
      .configure("metrics.enabled" -> false)
      .build()

  override protected lazy val repository: ApplicationsRepository = {
    new ApplicationsRepository(mongoComponent)
  }

  "POST to register a new application" should {
    "respond with a 201 Created" in {
      val newApplication = NewApplicationBuilder()

      val response =
        wsClient
          .url(s"$baseUrl/api-hub-applications/applications")
          .addHttpHeaders(("Content", "application/json"))
          .post(Json.toJson(newApplication))
          .futureValue

      response.status shouldBe 201
    }

    "add a full Application to the database" in {
      val expectedAppName = "it test new app name"
      val expectedCreatedBy = Creator(email = "it-test-new-app-created-by@email.com")
      val newApplication = NewApplicationBuilder(name = expectedAppName, createdBy = expectedCreatedBy)

       wsClient
          .url(s"$baseUrl/api-hub-applications/applications")
          .addHttpHeaders(("Content", "application/json"))
          .post(Json.toJson(newApplication))
          .futureValue

      val applications = repository.findAll().futureValue
      applications.size shouldBe 1

      val actualApplication = applications.head
      val hexStringLength = 24
      actualApplication.id.value.length shouldBe hexStringLength


      val actualCreationTime = actualApplication.created
      val expectedApplication = ApplicationBuilder(
        id = actualApplication.id,
        name = expectedAppName,
        createdBy = expectedCreatedBy,
        created = actualCreationTime,
        lastUpdated = actualCreationTime,
        teamMembers = Seq.empty,
        environments = Environments(Environment(Seq.empty, Seq.empty), Environment(Seq.empty, Seq.empty), Environment(Seq.empty, Seq.empty), Environment(Seq.empty, Seq.empty))
      )

      actualApplication shouldBe expectedApplication
    }
  }

  "GET all application" should {
    "respond with 200 status" in {
      val insertedApp1 = repository.insert(ApplicationBuilder(name = "app1")).futureValue
      val insertedApp2 = repository.insert(ApplicationBuilder(name = "app2")).futureValue

      val response =
        wsClient
          .url(s"$baseUrl/api-hub-applications/applications")
          .addHttpHeaders(("Accept", "application/json"))
          .get()
          .futureValue

      response.status shouldBe 200

      response.json shouldBe Json.toJson(Seq(insertedApp1, insertedApp2))
    }
  }

}
