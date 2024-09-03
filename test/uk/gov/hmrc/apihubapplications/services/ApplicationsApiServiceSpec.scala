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

import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.{never, verify, verifyNoInteractions, when}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.connectors.{EmailConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses._
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.CallError
import uk.gov.hmrc.apihubapplications.models.exception.{ApiNotFoundException, ApplicationNotFoundException, IdmsException, TeamNotFoundException}
import uk.gov.hmrc.apihubapplications.models.requests.AddApiRequest
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.ScopeFixer
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future

class ApplicationsApiServiceSpec extends AsyncFreeSpec with Matchers with MockitoSugar with EitherValues {

  import ApplicationsApiServiceSpec._

  "addApi" - {

    "must return ApplicationNotFoundException if application not initially found" in {
      val fixture = buildFixture
      import fixture._

      val testAppId = "test-app-id"
      when(searchService.findById(eqTo(testAppId), eqTo(true))(any)).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(testAppId))))

      service.addApi(testAppId, AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1")))(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationNotFoundException.forId(testAppId))
      }
    }

    "must update the application with the new Api" in {
      val fixture = buildFixture
      import fixture._

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

      val api = AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))
      val updatedApp = app.copy(
        apis = Seq(Api(api.id, api.endpoints)))

      when(searchService.findById(eqTo(testAppId), eqTo(true))(any)).thenReturn(Future.successful(Right(app)))
      when(repository.update(eqTo(updatedApp))).thenReturn(Future.successful(Right(())))
      when(idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      service.addApi(testAppId, api)(HeaderCarrier()) map {
        actual =>
          verify(repository).update(eqTo(updatedApp))
          actual mustBe Right(())
      }
    }

    "must update the application when it has already had the API added (add endpoints)" in {
      val fixture = buildFixture
      import fixture._

      val api = AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))

      val testAppId = "test-app-id"

      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      ).setApis(
        Seq(
          Api(api.id, Seq(Endpoint("GET", "/bar/foo")))
        )
      )

      val updatedApp = app.setApis(Seq(Api(api.id, api.endpoints)))

      when(searchService.findById(eqTo(testAppId), eqTo(true))(any)).thenReturn(Future.successful(Right(app)))
      when(repository.update(eqTo(updatedApp))).thenReturn(Future.successful(Right(())))
      when(idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      service.addApi(testAppId, api)(HeaderCarrier()) map {
        actual =>
          verify(repository).update(eqTo(updatedApp))
          actual mustBe Right(())
      }
    }

    "must update idms with new secondary scope when additional scopes are required" in {
      val fixture = buildFixture
      import fixture._

      val testAppId = "test-app-id"
      val testClientId = "test-client-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments().copy(secondary = Environment(Seq.empty, Seq(Credential(testClientId, LocalDateTime.now(clock), None, None))))
      )

      when(searchService.findById(eqTo(testAppId), eqTo(true))(any)).thenReturn(Future.successful(Right(app)))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right({})))

      service.addApi(testAppId, AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1", "test-scope-2")))(HeaderCarrier()).map {
        actual =>
          verify(idmsConnector).addClientScope(eqTo(Secondary), eqTo(testClientId), eqTo("test-scope-1"))(any)
          verify(idmsConnector).addClientScope(eqTo(Secondary), eqTo(testClientId), eqTo("test-scope-2"))(any)

          actual mustBe Right(())
      }
    }

    "should update IDMS with new secondary scopes even when they are already present (self-healing)" in {
      val fixture = buildFixture
      import fixture._

      val scope1 = "test-scope-1"
      val scope2 = "test-scope-2"

      val testAppId = "test-app-id"
      val testClientId = "test-client-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      ).setSecondaryCredentials(
        Seq(
          Credential(testClientId, LocalDateTime.now(clock), None, None)
        )
      )

      val newApi = AddApiRequest(
        id = "api_id",
        endpoints = Seq(Endpoint("GET", "/foo/bar")),
        scopes = Seq(scope1, scope2)
      )

      when(searchService.findById(eqTo(testAppId), eqTo(true))(any)).thenReturn(Future.successful(Right(app)))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      service.addApi(testAppId, newApi)(HeaderCarrier()) map {
        actual =>
          verify(idmsConnector).addClientScope(eqTo(Secondary), eqTo(testClientId), eqTo(scope1))(any)
          verify(idmsConnector).addClientScope(eqTo(Secondary), eqTo(testClientId), eqTo(scope2))(any)
          actual mustBe Right(())
      }

    }

    "must handle idms fail and return error for secondary scope" in {
      val fixture = buildFixture
      import fixture._

      val testScopeId = "test-scope-1"

      val testAppId = "test-app-id"
      val testClientId = "test-client-id"
      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments().copy(secondary = Environment(Seq.empty, Seq(Credential(testClientId, LocalDateTime.now(clock), None, None))))
      )

      val exception = IdmsException("Bad thing", CallError)

      when(searchService.findById(eqTo(testAppId), eqTo(true))(any)).thenReturn(Future.successful(Right(app)))
      when(idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Left(exception)))

      service.addApi(testAppId, AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1")))(HeaderCarrier()) map {
        actual =>
          verify(idmsConnector).addClientScope(eqTo(Secondary), eqTo(testClientId), eqTo(testScopeId))(any)
          verify(repository, never).update(any)
          actual mustBe Left(exception)
      }

    }

    "must return ApplicationNotFoundException if application not found whilst updating it with new api" in {
      val fixture = buildFixture
      import fixture._

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

      val api = AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1"))
      val updatedApp = app.copy(
        apis = Seq(Api(api.id, api.endpoints)))

      when(searchService.findById(eqTo(testAppId), eqTo(true))(any)).thenReturn(Future.successful(Right(app)))
      when(repository.update(eqTo(updatedApp))).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(testAppId))))
      when(idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      service.addApi(testAppId, api)(HeaderCarrier()) map {
        actual =>
          actual mustBe Left(ApplicationNotFoundException.forId(testAppId))
      }
    }

  }

  "removeApi" - {
    val applicationId = "test-application-id"
    val apiId = "test-api-id"

    val api = Api(apiId, Seq.empty)

    val onceUponATime = LocalDateTime.now(clock).minusDays(1)

    val baseApplication = Application(
      id = Some(applicationId),
      name = "test-app-name",
      created = onceUponATime,
      createdBy = Creator("test-email"),
      lastUpdated = onceUponATime,
      teamMembers = Seq(TeamMember(email = "test-email")),
      environments = Environments()
    )

    "must remove scopes, cancel any pending access requests, and update the API in MongoDb" in {
      val fixture = buildFixture
      import fixture._

      val application = baseApplication.addApi(api)
      val updated = application
        .removeApi(apiId)
        .updated(clock)

      when(searchService.findById(eqTo(applicationId), eqTo(true))(any)).thenReturn(Future.successful(Right(application)))
      when(scopeFixer.fix(any)(any)).thenReturn(Future.successful(Right(updated)))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(any, any)).thenReturn(Future.successful(Right(())))

      service.removeApi(applicationId, apiId)(HeaderCarrier()).map {
        result =>
          verify(scopeFixer).fix(eqTo(updated))(any)
          verify(repository).update(eqTo(updated))
          verify(accessRequestsService).cancelAccessRequests(eqTo(applicationId), eqTo(apiId))
          result.value mustBe ()
      }
    }

    "must return ApplicationNotFoundException when the application cannot be found" in {
      val fixture = buildFixture
      import fixture._

      when(searchService.findById(eqTo(applicationId), eqTo(true))(any))
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
      import fixture._

      when(searchService.findById(eqTo(applicationId), eqTo(true))(any)).thenReturn(Future.successful(Right(baseApplication)))

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
      import fixture._

      val application = baseApplication.addApi(api)
      val expected = IdmsException.clientNotFound("test-client-id")

      when(searchService.findById(eqTo(applicationId), eqTo(true))(any)).thenReturn(Future.successful(Right(application)))
      when(scopeFixer.fix(any)(any)).thenReturn(Future.successful(Left(expected)))

      service.removeApi(applicationId, apiId)(HeaderCarrier()).map {
        result =>
          verifyNoInteractions(fixture.accessRequestsService)
          verify(repository, never).update(any)
          result mustBe Left(expected)
      }
    }
  }

  "changeOwningTeam" - {
    val applicationId = "test-application-id"
    val teamId = "test-team-id"
    val oldTeamId = "test-old-team-id"

    val team = Team("team-name", Seq.empty, clock)

    val onceUponATime = LocalDateTime.now(clock).minusDays(1)

    val baseApplication = Application(
      id = Some(applicationId),
      name = "test-app-name",
      created = onceUponATime,
      createdBy = Creator("test-email"),
      lastUpdated = onceUponATime,
      teamMembers = Seq(TeamMember(email = "test-email")),
      environments = Environments()
    )

    "must remove scopes, cancel any pending access requests, send emails to both the old and new team and update the application in MongoDb" in {
      val fixture = buildFixture
      import fixture._

      val application = baseApplication.setTeamId(oldTeamId)
      val updated = application
        .setTeamId(teamId)
        .updated(clock)

      when(searchService.findById(eqTo(applicationId), eqTo(true), eqTo(true))(any)).thenReturn(Future.successful(Right(application)))
      when(repository.update(any)).thenReturn(Future.successful(Right(())))
      when(teamsService.findById(any)).thenReturn(Future.successful(Right(team)))
      when(emailConnector.sendApplicationOwnershipChangedEmailToOldTeamMembers(any, any, any)(any))
        .thenReturn(Future.successful((Right(()))))
      when(emailConnector.sendApplicationOwnershipChangedEmailToNewTeamMembers(any, any)(any))
        .thenReturn(Future.successful((Right(()))))

      service.changeOwningTeam(applicationId, teamId)(HeaderCarrier()).map {
        result =>
          verify(repository).update(eqTo(updated))
          verify(teamsService).findById(eqTo(teamId))
          verify(emailConnector).sendApplicationOwnershipChangedEmailToOldTeamMembers(any, any, any)(any)
          verify(emailConnector).sendApplicationOwnershipChangedEmailToNewTeamMembers(any, any)(any)
          result.value mustBe ()
      }
    }

    "must return ApplicationNotFoundException when the application cannot be found" in {
      val fixture = buildFixture
      import fixture._

      when(searchService.findById(eqTo(applicationId), eqTo(true), eqTo(true))(any))
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
      import fixture._

      when(searchService.findById(eqTo(applicationId), eqTo(true), eqTo(true))(any)).thenReturn(Future.successful(Right(baseApplication)))
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
      import fixture._

      val expected = IdmsException.clientNotFound("test-client-id")

      when(searchService.findById(eqTo(applicationId), eqTo(true), eqTo(true))(any)).thenReturn(Future.successful(Left(expected)))
      when(teamsService.findById(any)).thenReturn(Future.successful(Right(team)))

      service.changeOwningTeam(applicationId, teamId)(HeaderCarrier()).map {
        result =>
          verifyNoInteractions(fixture.accessRequestsService)
          verify(repository, never).update(any)
          result mustBe Left(expected)
      }
    }
  }

  private case class Fixture(
    searchService: ApplicationsSearchService,
    accessRequestsService: AccessRequestsService,
    teamsService: TeamsService,
    repository: ApplicationsRepository,
    idmsConnector: IdmsConnector,
    emailConnector: EmailConnector,
    scopeFixer: ScopeFixer,
    service: ApplicationsApiService
  )

  private def buildFixture: Fixture = {
    val searchService = mock[ApplicationsSearchService]
    val accessRequestsService = mock[AccessRequestsService]
    val teamsService = mock[TeamsService]
    val repository = mock[ApplicationsRepository]
    val idmsConnector = mock[IdmsConnector]
    val emailConnector = mock[EmailConnector]
    val scopeFixer = mock[ScopeFixer]
    val service = new ApplicationsApiServiceImpl(searchService, accessRequestsService, teamsService, repository, idmsConnector, emailConnector, scopeFixer, clock)
    Fixture(searchService, accessRequestsService, teamsService, repository, idmsConnector, emailConnector, scopeFixer, service)
  }

}

object ApplicationsApiServiceSpec {

  val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

}
