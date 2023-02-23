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

package uk.gov.hmrc.apihubapplications.services

import org.mockito.{ArgumentMatchers, MockitoSugar}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.requests.UpdateScopeStatus
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import java.util.UUID
import scala.concurrent.Future

class ApplicationsServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar {

  "registerApplication" - {
    "must build the correct application and submit it to the repository" in {
      val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
      val repository = mock[ApplicationsRepository]
      val service = new ApplicationsService(repository, clock)
      val newApplication = NewApplication("test-name", Creator(email = "test-email"))

      val application = Application(
        id = None,
        name = newApplication.name,
        created = LocalDateTime.now(clock),
        createdBy = newApplication.createdBy,
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = newApplication.createdBy.email)),
        environments = Environments()
      )

      val expected = application.copy(id = Some("test-id"))
      when(repository.insert(ArgumentMatchers.eq(application)))
        .thenReturn(Future.successful(expected))

      service.registerApplication(newApplication) map {
        actual =>
          actual mustBe expected
      }
    }
  }

  "findAll" - {
    "must return all applications from the repository" in {
      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1")),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"))
      )

      val repository = mock[ApplicationsRepository]
      when(repository.findAll()).thenReturn(Future.successful(applications))

      val service = new ApplicationsService(repository, Clock.systemDefaultZone())
      service.findAll() map {
        actual =>
          actual mustBe applications
          verify(repository).findAll()
          succeed
      }
    }
  }

 "get apps where pros env had pending scopes" -{
   "get pending scopes" in {
     val appWithProdPending = Application(Some(UUID.randomUUID().toString), "test-app-name", Creator("test@email.com"))
                                              .addScopes(Prod, Seq("test-scope-1"))
     val appWithoutPending = Application(Some(UUID.randomUUID().toString), "test-app-name", Creator("test@email.com"))
                                              .addScopes(Dev, Seq("test-scope-2"))

     val repository = mock[ApplicationsRepository]
     when(repository.findAll()).thenReturn(Future.successful(Seq(appWithProdPending,appWithoutPending)))

     val service = new ApplicationsService(repository, Clock.systemDefaultZone())
     service.getApplicationsWithPendingScope() map {
       actual =>
         actual mustBe Seq(appWithProdPending)
         verify(repository).findAll()
         succeed
     }
   }
 }

  "addScopes" - {
    "must add new scopes to Application" in {
      val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
      val repository = mock[ApplicationsRepository]
      val service = new ApplicationsService(repository, clock)

      val newScopes = Seq(
       NewScope("test-name-1", Seq(Prod)),
       NewScope("test-name-2", Seq(Dev, Test))
      )

      val testAppId = "test-app-id"
      val app  = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      )

      val updatedApp = app
        .addScopes(Prod, Seq("test-name-1"))
        .addScopes(Dev, Seq("test-name-2"))
        .addScopes(Test, Seq("test-name-2"))
      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Some(app)))
      when(repository.update(ArgumentMatchers.eq(updatedApp))).thenReturn(Future.successful(true))

      service.addScopes(testAppId, newScopes) map {
        actual =>
          actual mustBe Some(true)
      }
    }
    "must return None if application not found" in {
      val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
      val repository = mock[ApplicationsRepository]
      val service = new ApplicationsService(repository, clock)

      val newScopes = Seq(
        NewScope("test-name-1", Seq(Prod)),
        NewScope("test-name-2", Seq(Dev, Test))
      )

      val testAppId = "test-app-id"
      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(None))

      service.addScopes(testAppId, newScopes) map {
        actual =>
          actual mustBe None
      }
    }
  }

  "set scope status" - {
    "must set scopes for an application" in {
      val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
      val repository = mock[ApplicationsRepository]
      val service = new ApplicationsService(repository, clock)

      val envs = Environments(
        Environment(Seq.empty, Seq.empty),
        Environment(Seq.empty, Seq.empty),
        Environment(Seq.empty, Seq.empty),
        Environment(Seq(Scope("test-scope-1", Pending)), Seq.empty)
      )

      val testAppId = "test-app-id"
      val app  = Application(
        id = Some(testAppId),
        name = testAppId,
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = envs
      )

      val updatedApp = app.setProdScopes(Seq(Scope("test-scope-1", Approved)))
      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Some(app)))
      when(repository.update(ArgumentMatchers.eq(updatedApp))).thenReturn(Future.successful(true))

      service.setScope(testAppId, "prod", "test-scope-1", UpdateScopeStatus(Approved)) map {
        actual =>
          actual mustBe Some(true)
      }
    }
    "must return None if application not found" in {
      val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
      val repository = mock[ApplicationsRepository]
      val service = new ApplicationsService(repository, clock)

      val testAppId = "test-app-id"
      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(None))

      service.setScope(testAppId, "prod", "test-name-2", UpdateScopeStatus(Approved)) map {
        actual =>
          actual mustBe Some(false)
      }
    }
  }

}
