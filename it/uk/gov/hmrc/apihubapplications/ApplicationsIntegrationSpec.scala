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
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.{Application => GuideApplication}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import uk.gov.hmrc.apihubapplications.models.application.{Application, Environment, NewApplication}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.testhelpers.ApplicationGenerator
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global

class ApplicationsIntegrationSpec
  extends AnyWordSpec
     with Matchers
     with OptionValues
     with GuiceOneServerPerSuite
     with DefaultPlayMongoRepositorySupport[Application]
     with ScalaCheckPropertyChecks
     with ApplicationGenerator {

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
        val storedApplication = storedApplications.head

        responseApplication shouldBe storedApplication
        storedApplication.name shouldBe newApplication.name
        storedApplication.createdBy shouldBe newApplication.createdBy
        storedApplication.created shouldBe storedApplication.lastUpdated
        storedApplication.teamMembers shouldBe empty
        storedApplication.environments.dev shouldBe Environment(Seq.empty, Seq.empty)
        storedApplication.environments.test shouldBe Environment(Seq.empty, Seq.empty)
        storedApplication.environments.preProd shouldBe Environment(Seq.empty, Seq.empty)
        storedApplication.environments.prod shouldBe Environment(Seq.empty, Seq.empty)
      }
    }
  }

  "GET all application" should {
    "respond with 200 status and a list of applications" in {
      forAll { (application1: Application, application2: Application) =>
        deleteAll().futureValue

        insert(application1).futureValue
        insert(application2).futureValue

        val storedApplications: Seq[Application] = findAll().futureValue

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
  }

  "GET application by ID" should {
    "respond with 200 status and the found application" in {
      val applicationWithIdGenerator = applicationGenerator.arbitrary.suchThat(_.id.isDefined)

      forAll (applicationWithIdGenerator) { (application: Application) =>
        deleteAll().futureValue

        insert(application).futureValue

        val storedApplication = findAll().futureValue.head

        val response =
          wsClient
            .url(s"$baseUrl/api-hub-applications/applications/${application.id.get}")
            .addHttpHeaders(("Accept", "application/json"))
            .get()
            .futureValue

        response.status shouldBe 200
        response.json shouldBe Json.toJson(storedApplication)
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

}
