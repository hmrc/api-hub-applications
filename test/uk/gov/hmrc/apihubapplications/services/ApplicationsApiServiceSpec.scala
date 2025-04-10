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

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{never, verify, verifyNoInteractions, when}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.connectors.EmailConnector
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.exception.{ApiNotFoundException, ApplicationNotFoundException, IdmsException, TeamNotFoundException}
import uk.gov.hmrc.apihubapplications.models.requests.AddApiRequest
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.ScopeFixer
import uk.gov.hmrc.apihubapplications.testhelpers.{AccessRequestGenerator, FakeHipEnvironments}
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future

class ApplicationsApiServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar with EitherValues with AccessRequestGenerator {

  import ApplicationsApiServiceSpec.*

  "addApi" - {

    "must return ApplicationNotFoundException if application not initially found" in {
      val fixture = buildFixture
      import fixture.*

      val testAppId = "test-app-id"
      when(searchService.findById(eqTo(testAppId))(any)).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(testAppId))))

      service.addApi(testAppId, AddApiRequest("api_id", "api_title", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1")))(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationNotFoundException.forId(testAppId))
      }
    }

    "must update the application with the new Api" in {
      val fixture = buildFixture
      import fixture.*

      val testAppId = "test-app-id"

      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        credentials = Set.empty
      )

      val api = AddApiRequest("api_id", "api_title", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))
//      val appWithScopesAdded = app.setScopes(FakeHipEnvironments.secondaryEnvironment, Seq(Scope("test-scope-1")))
      val appWithApisAdded = app.setApis(apis = Seq(Api(api.id, api.title, api.endpoints)))

      when(searchService.findById(eqTo(testAppId))(any)).thenReturn(Future.successful(Right(app)))
      when(scopeFixer.fix(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(repository.update(eqTo(appWithApisAdded))).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.getAccessRequests(eqTo(Some(testAppId)), eqTo(None))).thenReturn(Future.successful(Seq.empty))

      service.addApi(testAppId, api)(HeaderCarrier()) map {
        actual =>
          verify(scopeFixer).fix(eqTo(appWithApisAdded), eqTo(Seq.empty))(any)
          verify(repository).update(eqTo(appWithApisAdded))
          actual mustBe Right(())
      }
    }

    "must update the application when it has already had the API added (add endpoints)" in {
      val fixture = buildFixture
      import fixture.*

      val api = Api("api_id", "api_title", Seq(Endpoint("GET", "/bar/foo")))
      val addApiRequest = AddApiRequest(api.id, api.title, api.endpoints, Seq("test-scope-1"))
      val testAppId = "test-app-id"

      val appWithApiAlreadyAdded = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        credentials = Set.empty
      ).setApis(
        Seq(api)
      )

//      val appWithScopesAdded = appWithApiAlreadyAdded.setScopes(FakeHipEnvironments.secondaryEnvironment, Seq(Scope("test-scope-1")))

      when(searchService.findById(eqTo(testAppId))(any)).thenReturn(Future.successful(Right(appWithApiAlreadyAdded)))
      when(scopeFixer.fix(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(repository.update(eqTo(appWithApiAlreadyAdded))).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.getAccessRequests(eqTo(Some(testAppId)), eqTo(None))).thenReturn(Future.successful(Seq.empty))

      service.addApi(testAppId, addApiRequest)(HeaderCarrier()) map {
        actual =>
          verify(scopeFixer).fix(eqTo(appWithApiAlreadyAdded), eqTo(Seq.empty))(any)
          verify(repository).update(eqTo(appWithApiAlreadyAdded))
          actual mustBe Right(())
      }
    }

    "must return ApplicationNotFoundException if application not found whilst updating it with new api" in {
      val fixture = buildFixture
      import fixture.*

      val testAppId = "test-app-id"

      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        credentials = Set.empty
      )

      val api = AddApiRequest("api_id", "api_title", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))
      val updatedApp = app.copy(
        apis = Seq(Api(api.id, api.title, api.endpoints)))

      when(searchService.findById(eqTo(testAppId))(any)).thenReturn(Future.successful(Right(app)))
      when(repository.update(eqTo(updatedApp))).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(testAppId))))
      when(scopeFixer.fix(any, any)(any)).thenReturn(Future.successful(Right(updatedApp)))

      service.addApi(testAppId, api)(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationNotFoundException.forId(testAppId))
      }
    }

  }

  "removeApi" - {
    val applicationId = "test-application-id"
    val apiId = "test-api-id"
    val apiTitle = "api_title"

    val api = Api(apiId, apiTitle, Seq.empty)

    val onceUponATime = LocalDateTime.now(clock).minusDays(1)

    val baseApplication = Application(
      id = Some(applicationId),
      name = "test-app-name",
      created = onceUponATime,
      createdBy = Creator("test-email"),
      lastUpdated = onceUponATime,
      teamMembers = Seq(TeamMember(email = "test-email")),
      credentials = Set.empty
    )

    "must remove scopes, cancel any pending access requests, and update the API in MongoDb" in {
      val fixture = buildFixture
      import fixture.*

      val application = baseApplication.addApi(api)
      val updated = application
        .removeApi(apiId)
        .updated(clock)

      when(searchService.findById(eqTo(applicationId))(any)).thenReturn(Future.successful(Right(application)))
      when(scopeFixer.fix(any, any)(any)).thenReturn(Future.successful(Right(())))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(any, any)).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.getAccessRequests(eqTo(Some(application.safeId)), eqTo(None))).thenReturn(Future.successful(Seq.empty))

      service.removeApi(applicationId, apiId)(HeaderCarrier()).map {
        result =>
          verify(scopeFixer).fix(eqTo(updated), eqTo(Seq.empty))(any)
          verify(repository).update(eqTo(updated))
          verify(accessRequestsService).cancelAccessRequests(eqTo(applicationId), eqTo(apiId))
          result.value mustBe()
      }
    }

    "must return ApplicationNotFoundException when the application cannot be found" in {
      val fixture = buildFixture
      import fixture.*

      when(searchService.findById(eqTo(applicationId))(any))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      service.removeApi(applicationId, apiId)(HeaderCarrier()).map {
        result =>
          verifyNoInteractions(fixture.scopeFixer)
          verifyNoInteractions(fixture.accessRequestsService)
          verify(repository, never).update(any)
          result mustBe Left(ApplicationNotFoundException.forId(applicationId))
      }
    }

    "must return ApiNotFoundException when the API has not been linked with the application" in {
      val fixture = buildFixture
      import fixture.*

      when(searchService.findById(eqTo(applicationId))(any)).thenReturn(Future.successful(Right(baseApplication)))

      service.removeApi(applicationId, apiId)(HeaderCarrier()).map {
        result =>
          verifyNoInteractions(fixture.scopeFixer)
          verifyNoInteractions(fixture.accessRequestsService)
          verify(repository, never).update(any)
          result mustBe Left(ApiNotFoundException.forApplication(applicationId, apiId))
      }
    }

    "must return any exceptions encountered" in {
      val fixture = buildFixture
      import fixture.*

      val application = baseApplication.addApi(api)

      val updated = application
        .removeApi(api.id)
        .updated(clock)

      val expected = IdmsException.clientNotFound("test-client-id")

      when(searchService.findById(eqTo(application.safeId))(any)).thenReturn(Future.successful(Right(application)))
      when(accessRequestsService.cancelAccessRequests(any, any)).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.getAccessRequests(eqTo(Some(application.safeId)), eqTo(None))).thenReturn(Future.successful(Seq.empty))
      when(repository.update(eqTo(updated))).thenReturn(Future.successful(Right(())))
      when(scopeFixer.fix(any, any)(any)).thenReturn(Future.successful(Left(expected)))

      service.removeApi(application.safeId, apiId)(HeaderCarrier()).map {
        result =>
          result mustBe Left(expected)
      }
    }
  }

  "changeOwningTeam" - {
    val applicationId = "test-application-id"
    val teamId = "test-team-id"
    val oldTeamId = "test-old-team-id"

    val team = Team("team-name", Seq.empty, clock = clock)

    val onceUponATime = LocalDateTime.now(clock).minusDays(1)

    val baseApplication = Application(
      id = Some(applicationId),
      name = "test-app-name",
      created = onceUponATime,
      createdBy = Creator("test-email"),
      lastUpdated = onceUponATime,
      teamMembers = Seq(TeamMember(email = "test-email")),
      credentials = Set.empty
    )

    "must remove scopes, cancel any pending access requests, send emails to both the old and new team and update the application in MongoDb" in {
      val fixture = buildFixture
      import fixture.*

      val application = baseApplication.setTeamId(oldTeamId)
      val updated = application
        .setTeamId(teamId)
        .updated(clock)

      when(searchService.findById(eqTo(applicationId), eqTo(true))(any)).thenReturn(Future.successful(Right(application)))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(teamsService.findById(any)).thenReturn(Future.successful(Right(team)))
      when(emailConnector.sendApplicationOwnershipChangedEmailToOldTeamMembers(any, any, any)(any))
        .thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationOwnershipChangedEmailToNewTeamMembers(any, any)(any))
        .thenReturn(Future.successful(Right(())))

      service.changeOwningTeam(applicationId, teamId)(HeaderCarrier()).map {
        result =>
          verify(repository).update(eqTo(updated))
          verify(teamsService).findById(eqTo(teamId))
          verify(emailConnector).sendApplicationOwnershipChangedEmailToOldTeamMembers(any, any, any)(any)
          verify(emailConnector).sendApplicationOwnershipChangedEmailToNewTeamMembers(any, any)(any)
          result.value mustBe()
      }
    }

    "must return ApplicationNotFoundException when the application cannot be found" in {
      val fixture = buildFixture
      import fixture.*

      when(searchService.findById(eqTo(applicationId), eqTo(true))(any))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      service.changeOwningTeam(applicationId, teamId)(HeaderCarrier()).map {
        result =>
          verifyNoInteractions(fixture.accessRequestsService)
          verify(repository, never).update(any)
          result mustBe Left(ApplicationNotFoundException.forId(applicationId))
      }
    }

    "must return TeamNotFoundException when the team is not found" in {
      val fixture = buildFixture
      import fixture.*

      when(searchService.findById(eqTo(applicationId), eqTo(true))(any)).thenReturn(Future.successful(Right(baseApplication)))
      when(teamsService.findById(any)).thenReturn(Future.successful(Left(TeamNotFoundException(""))))

      service.changeOwningTeam(applicationId, teamId)(HeaderCarrier()).map {
        result =>
          verifyNoInteractions(fixture.accessRequestsService)
          verify(repository, never).update(any)
          result mustBe Left(TeamNotFoundException.forId(teamId))
      }
    }

    "must return any exceptions encountered" in {
      val fixture = buildFixture
      import fixture.*

      val expected = IdmsException.clientNotFound("test-client-id")

      when(searchService.findById(eqTo(applicationId), eqTo(true))(any)).thenReturn(Future.successful(Left(expected)))
      when(teamsService.findById(any)).thenReturn(Future.successful(Right(team)))

      service.changeOwningTeam(applicationId, teamId)(HeaderCarrier()).map {
        result =>
          verifyNoInteractions(fixture.accessRequestsService)
          verify(repository, never).update(any)
          result mustBe Left(expected)
      }
    }
  }

  "removeTeamFromApplication" - {
    val applicationId = "test-application-id"
    val oldTeamId = "test-old-team-id"

    val onceUponATime = LocalDateTime.now(clock).minusDays(1)

    val baseApplication = Application(
      id = Some(applicationId),
      name = "test-app-name",
      created = onceUponATime,
      createdBy = Creator("test-email"),
      lastUpdated = onceUponATime,
      teamMembers = Seq(TeamMember(email = "test-email")),
      credentials = Set.empty
    )

    "must update the application in MongoDb" in {
      val fixture = buildFixture
      import fixture.*

      val application = baseApplication.setTeamId(oldTeamId)
      val updated = application
        .updated(clock)
        .copy(teamId = None, teamName = None, teamMembers = Seq.empty)

      when(searchService.findById(eqTo(applicationId), eqTo(true))(any)).thenReturn(Future.successful(Right(application)))
      val captor = ArgumentCaptor.forClass(classOf[Application])
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      service.removeOwningTeamFromApplication(applicationId)(HeaderCarrier()).map {
        result =>
          verify(repository).update(captor.capture())
          val actual: Application = captor.getValue
          actual.teamId mustBe None
          actual.teamMembers mustBe Seq.empty
          actual.teamName mustBe None
          result.value mustBe()
      }
    }

    "must return ApplicationNotFoundException when the application cannot be found" in {
      val fixture = buildFixture
      import fixture.*

      when(searchService.findById(eqTo(applicationId), eqTo(true))(any))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      service.removeOwningTeamFromApplication(applicationId)(HeaderCarrier()).map {
        result =>
          verifyNoInteractions(fixture.accessRequestsService)
          verify(repository, never).update(any)
          result mustBe Left(ApplicationNotFoundException.forId(applicationId))
      }
    }

    "must return any exceptions encountered" in {
      val fixture = buildFixture
      import fixture.*

      val expected = IdmsException.clientNotFound("test-client-id")

      when(searchService.findById(eqTo(applicationId), eqTo(true))(any)).thenReturn(Future.successful(Left(expected)))

      service.removeOwningTeamFromApplication(applicationId)(HeaderCarrier()).map {
        result =>
          verifyNoInteractions(fixture.accessRequestsService)
          verify(repository, never).update(any)
          result mustBe Left(expected)
      }
    }
  }

  "fixScopes" - {
    val applicationId = "test-application-id"

    val baseApplication = Application(
      id = Some(applicationId),
      name = "test-app-name",
      created = LocalDateTime.now(clock),
      createdBy = Creator("test-email"),
      lastUpdated = LocalDateTime.now(clock),
      teamMembers = Seq(TeamMember(email = "test-email")),
      credentials = Set.empty
    )

    "must pass the correct request to ScopeFixer and return the result" in {
      val fixture = buildFixture
      import fixture.*

      val accessRequests = Seq(sampleAccessRequest())

      when(searchService.findById(eqTo(applicationId))(any)).thenReturn(Future.successful(Right(baseApplication)))
      when(accessRequestsService.getAccessRequests(eqTo(Some(applicationId)), eqTo(None))).thenReturn(Future.successful(accessRequests))
      when(scopeFixer.fix(any, any)(any)).thenReturn(Future.successful(Right(())))

      service.fixScopes(applicationId)(HeaderCarrier()).map {
        result =>
          verify(scopeFixer).fix(eqTo(baseApplication), eqTo(accessRequests))(any)
          result.value mustBe ()
      }
    }

    "must return ApplicationNotFoundException when the application cannot be found" in {
      val fixture = buildFixture
      import fixture.*

      val expected = ApplicationNotFoundException.forId(applicationId)

      when(searchService.findById(eqTo(applicationId))(any)).thenReturn(Future.successful(Left(expected)))

      service.fixScopes(applicationId)(HeaderCarrier()).map {
        result =>
          result mustBe Left(expected)
      }
    }
  }

  private case class Fixture(
    searchService: ApplicationsSearchService,
    accessRequestsService: AccessRequestsService,
    teamsService: TeamsService,
    repository: ApplicationsRepository,
    emailConnector: EmailConnector,
    scopeFixer: ScopeFixer,
    service: ApplicationsApiService
  )

  private def buildFixture: Fixture = {
    val searchService = mock[ApplicationsSearchService]
    val accessRequestsService = mock[AccessRequestsService]
    val teamsService = mock[TeamsService]
    val repository = mock[ApplicationsRepository]
    val emailConnector = mock[EmailConnector]
    val scopeFixer = mock[ScopeFixer]
    val service = new ApplicationsApiServiceImpl(searchService, accessRequestsService, teamsService, repository, emailConnector, scopeFixer, clock)
    Fixture(searchService, accessRequestsService, teamsService, repository, emailConnector, scopeFixer, service)
  }

}

object ApplicationsApiServiceSpec {

  val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

}
