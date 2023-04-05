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

import org.mockito.ArgumentMatchers.any
import org.mockito.captor.ArgCaptor
import org.mockito.{ArgumentMatchers, MockitoSugar}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application.{Primary, _}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.testhelpers.ApplicationGenerator

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import java.util.UUID
import scala.concurrent.Future

class ApplicationsServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar with ApplicationGenerator with ScalaFutures {

  private val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

  "registerApplication" - {
    "must build the correct application and submit it to the repository" in {
      val repository = mock[ApplicationsRepository]
      val service = new ApplicationsService(repository, clock)

      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")
      val newApplication = NewApplication(
        "test-name",
        Creator(email = teamMember1.email),
        Seq(teamMember1, teamMember2)
      )

      val application = Application(
        id = None,
        name = newApplication.name,
        created = LocalDateTime.now(clock),
        createdBy = newApplication.createdBy,
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(teamMember1, teamMember2),
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

    "must add the creator as a team member if they are not already one" in {
      val repository = mock[ApplicationsRepository]
      val service = new ApplicationsService(repository, clock)

      val creator = Creator("test-email")
      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")
      val newApplication = NewApplication("test-name", creator, Seq(teamMember1, teamMember2))

      val expected = Application(
        id = None,
        name = newApplication.name,
        created = LocalDateTime.now(clock),
        createdBy = creator,
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(teamMember1, teamMember2, TeamMember(creator.email)),
        environments = Environments()
      )

      when(repository.insert(any()))
        .thenReturn(Future.successful(expected.copy(id = Some("id"))))

      service.registerApplication(newApplication) map {
        actual =>
          val captor = ArgCaptor[Application]
          verify(repository).insert(captor.capture)
          captor.value mustBe expected
          succeed
      }
    }
  }

  "findAll" - {
    "must return all applications from the repository" in {
      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"), Seq.empty)
      )

      val repository = mock[ApplicationsRepository]
      when(repository.findAll()).thenReturn(Future.successful(applications))

      val service = new ApplicationsService(repository, clock)
      service.findAll() map {
        actual =>
          actual mustBe applications
          verify(repository).findAll()
          succeed
      }
    }

    "must return all applications from the repository for named team member" in {
      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq(TeamMember("test-email-1"))),
      )

      val repository = mock[ApplicationsRepository]
      when(repository.filter("test-email-1")).thenReturn(Future.successful(applications))

      val service = new ApplicationsService(repository, clock)
      service.filter("test-email-1") map {
        actual =>
          actual mustBe applications
          verify(repository).filter("test-email-1")
          succeed
      }
    }

  }

 "get apps where prod env had pending scopes" -{
   "get pending scopes" in {
     val appWithProdPending = Application(Some(UUID.randomUUID().toString), "test-app-name", Creator("test@email.com"), Seq.empty)
                                              .addScopes(Prod, Seq("test-scope-1"))
     val appWithoutPending = Application(Some(UUID.randomUUID().toString), "test-app-name", Creator("test@email.com"), Seq.empty)
                                              .addScopes(Dev, Seq("test-scope-2"))

     val repository = mock[ApplicationsRepository]
     when(repository.findAll()).thenReturn(Future.successful(Seq(appWithProdPending,appWithoutPending)))

     val service = new ApplicationsService(repository, clock)
     service.getApplicationsWithPendingScope() map {
       actual =>
         actual mustBe Seq(appWithProdPending)
         verify(repository).findAll()
         succeed
     }
   }
 }

  "addScopes" - {

    "must add new scopes to Application and return true" in {
      val repository = mock[ApplicationsRepository]
      val service = new ApplicationsService(repository, clock)

      val newScopes = Seq(
       NewScope("test-name-1", Seq(Primary)),
       NewScope("test-name-2", Seq(Secondary, Primary))
      )

      val testAppId = "test-app-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      )

      val updatedApp = app
        .addScopes(Primary, Seq("test-name-1"))
        .addScopes(Secondary, Seq("test-name-2"))
        .addScopes(Primary, Seq("test-name-2"))

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Some(app)))
      when(repository.update(ArgumentMatchers.eq(updatedApp))).thenReturn(Future.successful(true))

      service.addScopes(testAppId, newScopes) map {
        actual =>
          actual mustBe true
      }
    }

    "must return false if application not found whilst updating it with new scopes" in {
      val repository = mock[ApplicationsRepository]
      val service = new ApplicationsService(repository, clock)

      val newScopes = Seq(
        NewScope("test-name-1", Seq(Primary)),
        NewScope("test-name-2", Seq(Secondary, Primary))
      )

      val testAppId = "test-app-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      )

      val updatedApp = app
        .addScopes(Primary, Seq("test-name-1"))
        .addScopes(Secondary, Seq("test-name-2"))
        .addScopes(Primary, Seq("test-name-2"))

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Some(app)))
      when(repository.update(ArgumentMatchers.eq(updatedApp))).thenReturn(Future.successful(false))

      service.addScopes(testAppId, newScopes) map {
        actual =>
          actual mustBe false
      }
    }

    "must return false if application not initially found" in {
      val repository = mock[ApplicationsRepository]
      val service = new ApplicationsService(repository, clock)

      val testAppId = "test-app-id"
      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(None))

      service.addScopes(testAppId, Seq.empty) map {
        actual =>
          actual mustBe false
      }
    }
  }

  "set scope status to APPROVED on prod when current scope status is PENDING" - {
    "must set scopes for an application" in {
      val repository = mock[ApplicationsRepository]
      val service = new ApplicationsService(repository, clock)

      val envs = Environments(
        Environment(Seq.empty, Seq.empty),
        Environment(Seq.empty, Seq.empty),
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

      service.setPendingProdScopeStatusToApproved(testAppId, "test-scope-1") map {
        actual =>
          actual mustBe Some(true)
      }
    }
    "must return None if application and/or scope name do not exist" in {
      val repository = mock[ApplicationsRepository]
      val service = new ApplicationsService(repository, clock)

      val testAppId = "test-app-id"
      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(None))

      service.setPendingProdScopeStatusToApproved(testAppId, "test-name-2") map {
        actual =>
          actual mustBe None
      }
    }
    "must return Some(False) when trying to set scope status to APPROVED on prod env when existing status is not PENDING" in {
      val repository = mock[ApplicationsRepository]
      val service = new ApplicationsService(repository, clock)
      val scopeName = "test-scope-1"
      val envs = Environments(
        Environment(Seq.empty, Seq.empty),
        Environment(Seq.empty, Seq.empty),
        Environment(Seq.empty, Seq.empty),
        Environment(Seq.empty, Seq.empty),
        Environment(Seq.empty, Seq.empty),
        Environment(Seq(Scope(scopeName, Denied)), Seq.empty)
      )
      val testAppId = "test-app-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = envs
      )


      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Some(app)))

      service.setPendingProdScopeStatusToApproved(testAppId, scopeName) map {
        actual =>
          actual mustBe Some(false)
      }
    }

  }

}
