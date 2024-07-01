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
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.captor.ArgCaptor
import org.mockito.{ArgumentCaptor, ArgumentMatchers, Mockito, MockitoSugar}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{EitherValues, OptionValues}
import play.api.http.Status.INTERNAL_SERVER_ERROR
import uk.gov.hmrc.apihubapplications.connectors.{EmailConnector, IdmsConnector}
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequestLenses.AccessRequestLensOps
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, Rejected}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.CallError
import uk.gov.hmrc.apihubapplications.models.exception._
import uk.gov.hmrc.apihubapplications.models.idms._
import uk.gov.hmrc.apihubapplications.models.requests.AddApiRequest
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.ScopeFixer
import uk.gov.hmrc.apihubapplications.testhelpers.ApplicationGenerator
import uk.gov.hmrc.http.HeaderCarrier

import java.time._
import scala.concurrent.Future

class ApplicationsServiceSpec
  extends AsyncFreeSpec
    with Matchers
    with MockitoSugar
    with ApplicationGenerator
    with ScalaFutures
    with OptionValues
    with EitherValues
    with TableDrivenPropertyChecks {

  import ApplicationsServiceSpec._

  "registerApplication" - {
    "must build the correct application, submit it to the repository and return it" in {
      val fixture = buildFixture
      import fixture._

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

      val primaryClientResponse = ClientResponse("primary-client-id", "test-secret-1234")
      when(idmsConnector.createClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(Client(newApplication)))(any()))
        .thenReturn(Future.successful(Right(primaryClientResponse)))

      val secondaryClientResponse = ClientResponse("secondary-client-id", "test-secret-5678")
      when(idmsConnector.createClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(Client(newApplication)))(any()))
        .thenReturn(Future.successful(Right(secondaryClientResponse)))

      when(emailConnector.sendAddTeamMemberEmail(any())(any()))
        .thenReturn(Future.successful(Right(())))

      when(emailConnector.sendApplicationCreatedEmailToCreator(any())(any()))
        .thenReturn(Future.successful(Right(())))

      val applicationWithCreds = application
        .setPrimaryCredentials(Seq(primaryClientResponse.asNewHiddenCredential(clock)))
        .setSecondaryCredentials(Seq(secondaryClientResponse.asNewCredential(clock)))

      val saved = applicationWithCreds.copy(id = Some("test-id"))

      when(repository.insert(ArgumentMatchers.eq(applicationWithCreds)))
        .thenReturn(Future.successful(saved))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        actual =>
          actual mustBe Right(saved)
      }
    }

    "must return IdmsException and not persist in MongoDb if the primary credentials fail" in {
      val fixture = buildFixture
      import fixture._

      val newApplication = NewApplication(
        "test-name",
        Creator(email = "test-email"),
        Seq.empty
      )

      when(idmsConnector.createClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(Client(newApplication)))(any()))
        .thenReturn(Future.successful(Left(IdmsException("test-message", CallError))))

      val secondaryClientResponse = ClientResponse("secondary-client-id", "test-secret-5678")
      when(idmsConnector.createClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(Client(newApplication)))(any()))
        .thenReturn(Future.successful(Right(secondaryClientResponse)))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        actual =>
          actual.left.value mustBe a[IdmsException]
          verifyZeroInteractions(repository.insert(any()))
          succeed
      }
    }

    "must return IdmsException and not persist in MongoDb if the secondary credentials fail" in {
      val fixture = buildFixture
      import fixture._

      val newApplication = NewApplication(
        "test-name",
        Creator(email = "test-email"),
        Seq.empty
      )

      val primaryClientResponse = ClientResponse("primary-client-id", "test-secret-1234")
      when(idmsConnector.createClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(Client(newApplication)))(any()))
        .thenReturn(Future.successful(Right(primaryClientResponse)))

      when(idmsConnector.createClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(Client(newApplication)))(any()))
        .thenReturn(Future.successful(Left(IdmsException("test-message", CallError))))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        actual =>
          actual.left.value mustBe a[IdmsException]
          verifyZeroInteractions(repository.insert(any()))
          succeed
      }
    }

    "must add the creator as a team member if they are not already one" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")
      val newApplication = NewApplication("test-name", creator, Seq(teamMember1, teamMember2))

      val clientResponse = ClientResponse("test-client-id", "test-secret-1234")

      val expected = Application(
        id = None,
        name = newApplication.name,
        created = LocalDateTime.now(clock),
        createdBy = creator,
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(teamMember1, teamMember2, TeamMember(creator.email)),
        environments = Environments()
      ).setSecondaryCredentials(Seq(clientResponse.asNewCredential(clock)))

      when(idmsConnector.createClient(any(), any())(any()))
        .thenReturn(Future.successful(Right(clientResponse)))

      when(repository.insert(any()))
        .thenReturn(Future.successful(expected.copy(id = Some("id"))))

      when(emailConnector.sendAddTeamMemberEmail(any())(any()))
        .thenReturn(Future.successful(Right(())))

      when(emailConnector.sendApplicationCreatedEmailToCreator(any())(any()))
        .thenReturn(Future.successful(Right(())))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        _ =>
          val captor = ArgCaptor[Application]
          verify(repository).insert(captor.capture)
          captor.value.teamMembers mustBe expected.teamMembers
          succeed
      }
    }

    "must email team members" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")
      val newApplication = NewApplication("test-name", creator, Seq(teamMember1, teamMember2))

      when(idmsConnector.createClient(any(), any())(any()))
        .thenReturn(Future.successful(Right(ClientResponse("test-client-id", "test-secret-1234"))))

      when(repository.insert(any()))
        .thenAnswer((application: Application) => Future.successful(application.copy(id = Some("id"))))

      when(emailConnector.sendAddTeamMemberEmail(any())(any()))
        .thenReturn(Future.successful(Right(())))

      when(emailConnector.sendApplicationCreatedEmailToCreator(any())(any()))
        .thenReturn(Future.successful(Right(())))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        _ =>
          val captor = ArgCaptor[Application]
          verify(repository).insert(captor.capture)
          val expected = captor.value.copy(id = Some("id"))
          verify(emailConnector).sendAddTeamMemberEmail(ArgumentMatchers.eq(expected))(any())
          succeed
      }
    }

    "must email application creator" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val newApplication = NewApplication("test-name", creator, Seq.empty)

      when(idmsConnector.createClient(any(), any())(any()))
        .thenReturn(Future.successful(Right(ClientResponse("test-client-id", "test-secret-1234"))))

      when(repository.insert(any()))
        .thenAnswer((application: Application) => Future.successful(application.copy(id = Some("id"))))

      when(emailConnector.sendAddTeamMemberEmail(any())(any()))
        .thenReturn(Future.successful(Right(())))

      when(emailConnector.sendApplicationCreatedEmailToCreator(any())(any()))
        .thenReturn(Future.successful(Right(())))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        _ =>
          val captor = ArgCaptor[Application]
          verify(repository).insert(captor.capture)
          val expected = captor.value.copy(id = Some("id"))
          verify(emailConnector).sendApplicationCreatedEmailToCreator(ArgumentMatchers.eq(expected))(any())
          succeed
      }
    }

    "must not throw an exception when the Email API errors" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")
      val newApplication = NewApplication("test-name", creator, Seq(teamMember1, teamMember2))

      when(idmsConnector.createClient(any(), any())(any()))
        .thenReturn(Future.successful(Right(ClientResponse("test-client-id", "test-secret-1234"))))

      when(repository.insert(any()))
        .thenAnswer((application: Application) => Future.successful(application.copy(id = Some("id"))))

      when(emailConnector.sendAddTeamMemberEmail(any())(any()))
        .thenReturn(Future.successful(Left(EmailException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      when(emailConnector.sendApplicationCreatedEmailToCreator(any())(any()))
        .thenReturn(Future.successful(Left(EmailException.unexpectedResponse(INTERNAL_SERVER_ERROR))))

      service.registerApplication(newApplication)(HeaderCarrier()) map {
        result =>
          result.isRight mustBe true
      }
    }
  }

  "findAll" - {
    "must return all applications from the repository" in {
      val fixture = buildFixture
      import fixture._

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"), Seq.empty)
      )

      when(repository.findAll(any(), any())).thenReturn(Future.successful(applications))

      service.findAll(None, false) map {
        actual =>
          actual mustBe applications
          verify(repository).findAll(ArgumentMatchers.eq(None), ArgumentMatchers.eq(false))
          succeed
      }
    }

    "must return all applications from the repository for named team member" in {
      val fixture = buildFixture
      import fixture._

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq(TeamMember("test-email-1"))),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-1"), Seq(TeamMember("test-email-1")))
      )

      when(repository.findAll(any(), any())).thenReturn(Future.successful(applications))
      when(teamsService.findAll(any())).thenReturn(Future.successful(Seq.empty))

      service.findAll(Some("test-email-1"), false) map {
        actual =>
          actual mustBe applications
          verify(repository).findAll(ArgumentMatchers.eq(Some("test-email-1")), ArgumentMatchers.eq(false))
          succeed
      }
    }

    "must return deleted applications when requested" in {
      val fixture = buildFixture
      import fixture._

      val deleted = Deleted(LocalDateTime.now(clock), "test-deleted-by")

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty).delete(deleted),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"), Seq.empty).delete(deleted)
      )

      when(repository.findAll(any(), any())).thenReturn(Future.successful(applications))

      service.findAll(None, true) map {
        actual =>
          actual mustBe applications
          verify(repository).findAll(ArgumentMatchers.eq(None), ArgumentMatchers.eq(true))
          succeed
      }
    }
  }

  "findAllUsingApi" - {
    "must return all applications from the repository that have a given API and are not deleted" in {
      val fixture = buildFixture
      import fixture._

      val apiId = "test-api"
      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty).addApi(Api(apiId)),
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty).addApi(Api(apiId)),
      )

      when(repository.findAllUsingApi(any(), any())).thenReturn(Future.successful(applications))

      service.findAllUsingApi(apiId, false) map {
        actual =>
          actual mustBe applications
          verify(repository).findAllUsingApi(ArgumentMatchers.eq(apiId), ArgumentMatchers.eq(false))
          succeed
      }
    }

    "must return deleted applications when requested" in {
      val fixture = buildFixture
      import fixture._

      val deleted = Deleted(LocalDateTime.now(clock), "test-deleted-by")
      val apiId = "test-api"

      val applications = Seq(
        Application(Some("test-id-1"), "test-name-1", Creator("test-email-1"), Seq.empty).addApi(Api(apiId)).delete(deleted),
        Application(Some("test-id-2"), "test-name-2", Creator("test-email-2"), Seq.empty).addApi(Api(apiId)).delete(deleted)
      )

      when(repository.findAllUsingApi(any(), any())).thenReturn(Future.successful(applications))

      service.findAllUsingApi(apiId, true) map {
        actual =>
          actual mustBe applications
          verify(repository).findAllUsingApi(ArgumentMatchers.eq(apiId), ArgumentMatchers.eq(true))
          succeed
      }
    }
  }

  "findById" - {
    "must return the application when it exists" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val primaryClientId = "test-primary-client-id"
      val secondaryClientId = "test-secondary-client-id"
      val secondaryClientSecret = "test-secondary-secret-1234"
      val scope1 = "test-scope-1"
      val scope2 = "test-scope-2"
      val scope3 = "test-scope-3"
      val scope4 = "test-scope-4"

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock)
        .setPrimaryCredentials(Seq(Credential(primaryClientId, LocalDateTime.now(clock), None, None)))
        .setSecondaryCredentials(Seq(Credential(secondaryClientId, LocalDateTime.now(clock), None, None)))

      when(repository.findById(ArgumentMatchers.eq(id)))
        .thenReturn(Future.successful(Right(application)))

      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(secondaryClientId))(any()))
        .thenReturn(Future.successful(Right(ClientResponse(secondaryClientId, secondaryClientSecret))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(secondaryClientId))(any()))
        .thenReturn(Future.successful(Right(Seq(ClientScope(scope1), ClientScope(scope2)))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(primaryClientId))(any()))
        .thenReturn(Future.successful(Right(Seq(ClientScope(scope3), ClientScope(scope4)))))

      val expected = application
        .setSecondaryCredentials(Seq(Credential(secondaryClientId, LocalDateTime.now(clock), Some(secondaryClientSecret), Some("1234"))))
        .setSecondaryScopes(Seq(Scope(scope1), Scope(scope2)))
        .setPrimaryScopes(Seq(Scope(scope3), Scope(scope4)))

      service.findById(id, enrich = true)(HeaderCarrier()).map {
        result =>
          result mustBe Right(expected)
      }
    }

    "must return ApplicationNotFoundException when the application does not exist" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"

      when(repository.findById(ArgumentMatchers.eq(id)))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(id))))

      service.findById(id, enrich = true)(HeaderCarrier()).map(
        result =>
          result mustBe Left(ApplicationNotFoundException.forId(id))
      )
    }

    "must not return IdmsException when that is returned from the IDMS connector and return issues instead" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId = "test-client-id"

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock)
        .setSecondaryCredentials(Seq(Credential(clientId, LocalDateTime.now(clock), None, None)))

      val application1WithIssues = application.copy(issues = Seq("Secondary credential not found. test-message"))

      when(repository.findById(ArgumentMatchers.eq(id)))
        .thenReturn(Future.successful(Right(application)))

      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(clientId))(any()))
        .thenReturn(Future.successful(Left(IdmsException("test-message", CallError))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(clientId))(any()))
        .thenReturn(Future.successful(Right(Seq.empty)))

      service.findById(id, enrich = true)(HeaderCarrier()).map {
        result => result mustBe Right(application1WithIssues)
      }
    }

    "must not enrich with IDMS data unless asked to" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"

      val application = Application(Some(id), "test-name", Creator("test-creator"), Seq.empty, clock)
        .setPrimaryCredentials(Seq(Credential("test-primary-client-id", LocalDateTime.now(clock), None, None)))
        .setSecondaryCredentials(Seq(Credential("test-secondary-client-id", LocalDateTime.now(clock), None, None)))

      when(repository.findById(ArgumentMatchers.eq(id)))
        .thenReturn(Future.successful(Right(application)))

      service.findById(id, enrich = false)(HeaderCarrier()).map {
        result =>
          result mustBe Right(application)
          verifyZeroInteractions(idmsConnector)
          succeed
      }
    }
  }

  "delete" - {
    "must soft delete the application and leave in the repository" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      val captor: ArgumentCaptor[Application] = ArgumentCaptor.forClass(classOf[Application])
      when(repository.update(captor.capture())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteAllClients(ArgumentMatchers.eq(application))(any())).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(())))
      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(repository).update(any())
          val captured = captor.getValue
          captured.deleted mustBe Some(Deleted(LocalDateTime.now(clock), currentUser))
          succeed
      }
    }

    "must email current user and team members" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")

      val id = "test-id"
      val application = Application(Some(id), "test-description", creator, Seq(teamMember1, teamMember2))

      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteAllClients(ArgumentMatchers.eq(application))(any())).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(())))

      service.delete(application.id.get, currentUser)(HeaderCarrier()) map {
        _ =>
          verify(emailConnector).sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())
          verify(emailConnector).sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())
          succeed
      }
    }

    "must tolerate email failure and return success" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")

      val id = "test-id"
      val application = Application(Some(id), "test-description", creator, Seq(teamMember1, teamMember2))

      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), any())(any())).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), any())(any())).thenReturn(Future.successful(Left(EmailException.unexpectedResponse(500))))
      when(idmsConnector.deleteAllClients(ArgumentMatchers.eq(application))(any())).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(())))

      service.delete(application.id.get, currentUser)(HeaderCarrier()).map {
        result =>
          result mustBe Right(())
          succeed
      }
    }

    "must not send email when delete operation fails" in {
      val fixture = buildFixture
      import fixture._

      val creator = Creator("test-email")
      val teamMember1 = TeamMember("test-email-1")
      val teamMember2 = TeamMember("test-email-2")

      val id = "test-id"
      val application = Application(Some(id), "test-description", creator, Seq(teamMember1, teamMember2))

      val notFoundException = ApplicationNotFoundException("Not found")
      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Left(notFoundException)))

      service.delete(application.id.get, currentUser)(HeaderCarrier()).map {
        result =>
          result mustBe Left(notFoundException)
          verifyNoInteractions(emailConnector)
          succeed
      }
    }

    "must delete all clients from IDMS" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId = "test-client-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setPrimaryCredentials(Seq(Credential(clientId, LocalDateTime.now(clock), None, None)))

      val currentUser = "user@hmrc.gov.uk"
      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteAllClients(any())(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(())))

      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(idmsConnector).deleteAllClients(any())(any())
          succeed
      }
    }

    "must cancel all access requests" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId = "test-client-id"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setPrimaryCredentials(Seq(Credential(clientId, LocalDateTime.now(clock), None, None)))

      val currentUser = "user@hmrc.gov.uk"
      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteAllClients(any())(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))

      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Right(())
          verify(accessRequestsService).cancelAccessRequests(ArgumentMatchers.eq(id))
          succeed
      }
    }

    "must return ApplicationNotFoundException when the application does not exist" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      when(repository.findById(ArgumentMatchers.eq(id)))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(id))))

      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(ApplicationNotFoundException.forId(id))
      }
    }

    "must return IdmsException when that is returned from any call to the IDMS connector and not update the repo" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId1 = "test-client-id-1"
      val clientId2 = "test-client-id-2"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setPrimaryCredentials(Seq(Credential(clientId1, LocalDateTime.now(clock), None, None)))
        .setSecondaryCredentials(Seq(Credential(clientId2, LocalDateTime.now(clock), None, None)))

      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteAllClients(ArgumentMatchers.eq(application))(any()))
        .thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))
      when(accessRequestsService.cancelAccessRequests(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(())))

      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(IdmsException.unexpectedResponse(500))
          verify(repository, times(0)).update(any())
          succeed
      }
    }

    "must return ApplicationsException when that is returned from any call to the access requests service and not update the repo" in {
      val fixture = buildFixture
      import fixture._

      val id = "test-id"
      val clientId1 = "test-client-id-1"
      val clientId2 = "test-client-id-2"
      val application = Application(Some(id), "test-description", Creator("test-email"), Seq.empty)
        .setPrimaryCredentials(Seq(Credential(clientId1, LocalDateTime.now(clock), None, None)))
        .setSecondaryCredentials(Seq(Credential(clientId2, LocalDateTime.now(clock), None, None)))

      when(repository.findById(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Right(application)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToTeam(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(emailConnector.sendApplicationDeletedEmailToCurrentUser(ArgumentMatchers.eq(application), ArgumentMatchers.eq(currentUser))(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.deleteAllClients(ArgumentMatchers.eq(application))(any())).thenReturn(Future.successful(Right(())))
      val notUpdatedException = NotUpdatedException.forId(id)
      when(accessRequestsService.cancelAccessRequests(ArgumentMatchers.eq(id))).thenReturn(Future.successful(Left(notUpdatedException)))

      service.delete(id, currentUser)(HeaderCarrier()).map {
        actual =>
          actual mustBe Left(notUpdatedException)
          verify(repository, times(0)).update(any())
          succeed
      }
    }
  }

  "addApi" - {

    "must return ApplicationNotFoundException if application not initially found" in {
      val fixture = buildFixture
      import fixture._

      val testAppId = "test-app-id"
      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(testAppId))))

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

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(ArgumentMatchers.eq(updatedApp))).thenReturn(Future.successful(Right(())))
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      service.addApi(testAppId, api)(HeaderCarrier()) map {
        actual =>
          verify(repository).update(ArgumentMatchers.eq(updatedApp))
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

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(ArgumentMatchers.eq(updatedApp))).thenReturn(Future.successful(Right(())))
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      service.addApi(testAppId, api)(HeaderCarrier()) map {
        actual =>
          verify(repository).update(ArgumentMatchers.eq(updatedApp))
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

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any()))
        .thenReturn(Future.successful(Right(ClientResponse(testClientId, "test-secret"))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any()))
        .thenReturn(Future.successful(Right(Seq.empty)))
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right({})))

      service.addApi(testAppId, AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1", "test-scope-2")))(HeaderCarrier()).map {
        actual =>
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq("test-scope-1"))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq("test-scope-2"))(any())

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

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any()))
        .thenReturn(Future.successful(Right(ClientResponse(testClientId, "test-secret"))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any()))
        .thenReturn(Future.successful(Right(Seq(ClientScope("scope1"), ClientScope("scope2")))))
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      service.addApi(testAppId, newApi)(HeaderCarrier()) map {
        actual =>
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(scope1))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(scope2))(any())
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

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any()))
        .thenReturn(Future.successful(Right(ClientResponse(testClientId, "test-secret"))))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId))(any()))
        .thenReturn(Future.successful(Right(Seq.empty)))
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Left(exception)))

      service.addApi(testAppId, AddApiRequest("api_id", Seq(Endpoint("GET", "/foo/bar")), Seq("test-scope-1")))(HeaderCarrier()) map {
        actual =>
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId), ArgumentMatchers.eq(testScopeId))(any())
          verify(repository, never).update(any())
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

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))
      when(repository.update(ArgumentMatchers.eq(updatedApp))).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(testAppId))))
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

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

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))
      when(scopeFixer.fix(any())(any())).thenReturn(Future.successful(Right(updated)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))
      when(accessRequestsService.cancelAccessRequests(any(), any())).thenReturn(Future.successful(Right(())))

      service.removeApi(applicationId, apiId)(HeaderCarrier()).map {
        result =>
          verify(scopeFixer).fix(ArgumentMatchers.eq(updated))(any())
          verify(repository).update(ArgumentMatchers.eq(updated))
          verify(accessRequestsService).cancelAccessRequests(ArgumentMatchers.eq(applicationId), ArgumentMatchers.eq(apiId))
          result.value mustBe ()
      }
    }

    "must return ApplicationNotFoundException when the application cannot be found" in {
      val fixture = buildFixture
      import fixture._

      when(repository.findById(ArgumentMatchers.eq(applicationId)))
        .thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      service.removeApi(applicationId, apiId)(HeaderCarrier()).map {
        result =>
          verifyZeroInteractions(fixture.scopeFixer)
          verifyZeroInteractions(fixture.accessRequestsService)
          verify(repository, never).update(any())
          result mustBe Left(ApplicationNotFoundException.forId(applicationId))
      }
    }

    "must return ApiNotFoundException when the API has not been linked with the application" in {
      val fixture = buildFixture
      import fixture._

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(baseApplication)))

      service.removeApi(applicationId, apiId)(HeaderCarrier()).map {
        result =>
          verifyZeroInteractions(fixture.scopeFixer)
          verifyZeroInteractions(fixture.accessRequestsService)
          verify(repository, never).update(any())
          result mustBe Left(ApiNotFoundException.forApplication(applicationId, apiId))
      }
    }

    "must return any exceptions encountered" in {
      val fixture = buildFixture
      import fixture._

      val application = baseApplication.addApi(api)
      val expected = IdmsException.clientNotFound("test-client-id")

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))
      when(scopeFixer.fix(any())(any())).thenReturn(Future.successful(Left(expected)))

      service.removeApi(applicationId, apiId)(HeaderCarrier()).map {
        result =>
          verifyZeroInteractions(fixture.accessRequestsService)
          verify(repository, never).update(any())
          result mustBe Left(expected)
      }
    }
  }

  "addCredential" - {

    "must create a new secondary credential and copy the previous secondary master scopes" in {
      val fixture = buildFixture
      import fixture._

      val testAppId = "test-app-id"
      val oldTestClientId = "test-client-id"
      val newTestClientId = "new-test-client-id"
      val oldSecret = "test-secret-9876"
      val newSecret = "test-secret-1234"
      val scopeName = "test-scope"
      val existingCredential = Credential(oldTestClientId, LocalDateTime.now(clock).minus(Duration.ofDays(1)), Some(oldSecret), Some("9876"))
      val expectedCredential = Credential(newTestClientId, LocalDateTime.now(clock), Some("test-secret-1234"), Some("1234"))

      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments(primary = Environment(), secondary = Environment(Seq(Scope(scopeName)), Seq(existingCredential)))
      )

      val existingClientResponse = ClientResponse(oldTestClientId, oldSecret)
      val newClientResponse = ClientResponse(newTestClientId, newSecret)

      val expectedClient = Client(app.name, app.name)
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(oldTestClientId))(any())).thenReturn(Future.successful(Right(existingClientResponse)))
      when(idmsConnector.createClient(ArgumentMatchers.eq(Secondary), any())(any())).thenReturn(Future.successful(Right(newClientResponse)))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(oldTestClientId))(any())).thenReturn(Future.successful(Right(Seq(ClientScope(scopeName)))))
      when(idmsConnector.addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(newTestClientId), ArgumentMatchers.eq(scopeName))(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(newTestClientId))(any())).thenReturn(Future.successful(Right(newClientResponse)))

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))

      val updatedApp = app
        .addSecondaryCredential(expectedCredential)
        .copy(lastUpdated = LocalDateTime.now(clock))

      when(repository.update(any())).thenReturn(Future.successful(Right(())))

      service.addCredential(testAppId, Secondary)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector).createClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(expectedClient))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(newTestClientId), ArgumentMatchers.eq(scopeName))(any())
          verify(repository).update(updatedApp)
          newCredential mustBe Right(expectedCredential)
      }
    }

    "must create a new primary credential and copy the previous primary master scopes where the existing credential is not hidden" in {
      val fixture = buildFixture
      import fixture._

      val testAppId = "test-app-id"
      val oldTestClientId = "test-client-id"
      val newTestClientId = "new-test-client-id"
      val newSecret = "test-secret-1234"
      val scopeName = "test-scope"
      val existingCredential = Credential(oldTestClientId, LocalDateTime.now(clock).minus(Duration.ofDays(1)), None, Some("9876"))
      val expectedCredential = Credential(newTestClientId, LocalDateTime.now(clock), Some(newSecret), Some("1234"))

      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments(primary = Environment(Seq(Scope(scopeName)), Seq(existingCredential)), secondary = Environment())
      )

      val newClientResponse = ClientResponse(newTestClientId, newSecret)
      val expectedClient = Client(app.name, app.name)

      when(idmsConnector.createClient(ArgumentMatchers.eq(Primary), any())(any())).thenReturn(Future.successful(Right(newClientResponse)))
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(oldTestClientId))(any())).thenReturn(Future.successful(Right(Seq(ClientScope(scopeName)))))
      when(idmsConnector.addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(newTestClientId), ArgumentMatchers.eq(scopeName))(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(newTestClientId))(any())).thenReturn(Future.successful(Right(newClientResponse)))

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))

      val updatedApp = app
        .addPrimaryCredential(expectedCredential)
        .copy(lastUpdated = LocalDateTime.now(clock))

      when(repository.update(any())).thenReturn(Future.successful(Right(())))

      service.addCredential(testAppId, Primary)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector).createClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(expectedClient))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(newTestClientId), ArgumentMatchers.eq(scopeName))(any())
          verify(repository).update(updatedApp)
          newCredential mustBe Right(expectedCredential)
      }
    }

    "must update existing primary credential and set a secret fragment where the existing credential is hidden" in {
      val fixture = buildFixture
      import fixture._

      val testAppId = "test-app-id"
      val testClientId = "test-client-id"
      val scopeName = "test-scope"

      val existingHiddenCredential = Credential(testClientId, LocalDateTime.now(clock).minus(Duration.ofDays(1)), None, None)
      val newSecret = "test-secret-1234"

      val expectedCredential = Credential(testClientId, LocalDateTime.now(clock), Some(newSecret), Some("1234"))
      val clientResponse = ClientResponse(testClientId, newSecret)

      val app = Application(
        id = Some(testAppId),
        name = "test-app-name",
        created = LocalDateTime.now(clock).minus(Duration.ofDays(1)),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments(primary = Environment(Seq(Scope(scopeName)), Seq(existingHiddenCredential)), secondary = Environment())
      )

      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(Seq(ClientScope(scopeName)))))
      when(idmsConnector.newSecret(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(Secret(newSecret))))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientId))(any())).thenReturn(Future.successful(Right(clientResponse)))

      when(repository.findById(ArgumentMatchers.eq(testAppId))).thenReturn(Future.successful(Right(app)))

      val updatedApp = app.setPrimaryCredentials(Seq(expectedCredential))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))

      service.addCredential(testAppId, Primary)(HeaderCarrier()) map {
        newCredential =>
          verify(idmsConnector, Mockito.times(0)).createClient(ArgumentMatchers.eq(Primary), any())(any())
          verify(idmsConnector, Mockito.times(0)).addClientScope(ArgumentMatchers.eq(Primary), any(), any())(any())
          verify(repository).update(updatedApp)
          newCredential mustBe Right(expectedCredential)
      }
    }

    "must return ApplicationCredentialLimitException if there are already 5 credentials" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      ).setPrimaryCredentials(
        (1 to 5).map(i => Credential(s"test-client-$i", LocalDateTime.now(clock), None, None))
      )

      when(idmsConnector.fetchClientScopes(any(), any())(any())).thenReturn(Future.successful(Right(Seq.empty)))
      when(repository.findById(any())).thenReturn(Future.successful(Right(application)))

      service.addCredential(applicationId, Primary)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApplicationCredentialLimitException.forApplication(application, Primary))
      }
    }
  }

  "deleteCredential" - {
    "must delete the correct credential from the correct environment" in {
      val environmentNames = Table(
        "Environment Name",
        Primary,
        Secondary
      )

      val applicationId = "test-id"
      val clientId = "test-client-id"

      forAll(environmentNames) {(environmentName: EnvironmentName) =>
        val fixture = buildFixture
        import fixture._

        val application = Application(
          id = Some(applicationId),
          name = "test-name",
          created = LocalDateTime.now(clock),
          createdBy = Creator("test-email"),
          lastUpdated = LocalDateTime.now(clock),
          teamMembers = Seq(TeamMember(email = "test-email")),
          environments = Environments()
        )
          .addCredential(Credential("other-credential-id", LocalDateTime.now(clock), None, None), environmentName)
          .addCredential(Credential(clientId, LocalDateTime.now(clock), None, None), environmentName)

        when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))
        when(idmsConnector.deleteClient(any(), any())(any())).thenReturn(Future.successful(Right(())))
        when(repository.update(any())).thenReturn(Future.successful(Right(())))

        service.deleteCredential(applicationId, environmentName, clientId)(HeaderCarrier()).map {
          result =>
            verify(idmsConnector).deleteClient(ArgumentMatchers.eq(environmentName), ArgumentMatchers.eq(clientId))(any())
            result mustBe Right(())
        }
      }
    }

    "must update the application in the repository" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-id"
      val clientId = "test-client-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock).minusDays(1),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minusDays(1),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      )
        .addPrimaryCredential(Credential(clientId, LocalDateTime.now(clock), None, None))
        .addPrimaryCredential(Credential("test-primary-client-id", LocalDateTime.now(clock), None, None))
        .addSecondaryCredential(Credential("test-secondary-client-id", LocalDateTime.now(clock), None, None))

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.deleteClient(any(), any())(any())).thenReturn(Future.successful(Right(())))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
        result =>
          val updated = application
            .copy(lastUpdated = LocalDateTime.now(clock))
            .removePrimaryCredential(clientId)
          verify(repository).update(ArgumentMatchers.eq(updated))
          result mustBe Right(())
      }
    }

    "must succeed when IDMS returns ClientNotFound" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-id"
      val clientId = "test-client-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      )
        .addPrimaryCredential(Credential("other-credential-id", LocalDateTime.now(clock), None, None))
        .addPrimaryCredential(Credential(clientId, LocalDateTime.now(clock), None, None))

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.deleteClient(any(), any())(any())).thenReturn(Future.successful(Left(IdmsException.clientNotFound(clientId))))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
        result =>
          result mustBe Right(())
      }
    }

    "must return ApplicationNotFoundException when the application does not exist" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-id"
      val clientId = "test-client-id"

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Left(ApplicationNotFoundException.forId(applicationId))))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApplicationNotFoundException.forId(applicationId))
      }
    }

    "must return CredentialNotFoundException when the credential does not exist in the application" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-id"
      val clientId = "test-client-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock).minusDays(1),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minusDays(1),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      )
        .addPrimaryCredential(Credential("other-credential-id", LocalDateTime.now(clock), None, None))

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
        result =>
          result mustBe Left(CredentialNotFoundException.forClientId(clientId))
      }
    }

    "must return IdmsException when this is returned from IDMS" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-id"
      val clientId = "test-client-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      )
        .addPrimaryCredential(Credential("other-credential-id", LocalDateTime.now(clock), None, None))
        .addPrimaryCredential(Credential(clientId, LocalDateTime.now(clock), None, None))

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.deleteClient(any(), any())(any())).thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
        result =>
          result mustBe Left(IdmsException.unexpectedResponse(500))
      }
    }

    "must return ApplicationCredentialLimitException when an attempt is made to delete the last credential" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-id"
      val clientId = "test-client-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock).minusDays(1),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minusDays(1),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      )
        .addPrimaryCredential(Credential(clientId, LocalDateTime.now(clock), None, None))

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))

      service.deleteCredential(applicationId, Primary, clientId)(HeaderCarrier()).map {
        result =>
          result mustBe Left(ApplicationCredentialLimitException.forApplication(application, Primary))
      }
    }
  }

  "addPrimaryAccess" - {
    "must add the new scopes to all primary credentials" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-application-id"
      val clientId1 = "test-client-id-1"
      val clientId2 = "test-client-id-2"
      val scope1 = "test-scope-1"
      val scope2 = "test-scope-2"
      val scope3 = "test-scope-3"

      val accessRequest = AccessRequest(
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Rejected,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(clock),
        requestedBy = "test-requested-by"
      )
        .addEndpoint("test-method-1", "test-path-1", Seq(scope1, scope2))
        .addEndpoint("test-method-2", "test-path-2", Seq(scope2, scope3))

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      ).setPrimaryCredentials(
        Seq(
          Credential(clientId1, LocalDateTime.now(clock), None, None),
          Credential(clientId2, LocalDateTime.now(clock), None, None)
        )
      )

      when(repository.findById(any())).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.addClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      service.addPrimaryAccess(accessRequest)(HeaderCarrier()).map {
        result =>
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId1) ,ArgumentMatchers.eq(scope1))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId1) ,ArgumentMatchers.eq(scope2))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId1) ,ArgumentMatchers.eq(scope3))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId2) ,ArgumentMatchers.eq(scope1))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId2) ,ArgumentMatchers.eq(scope2))(any())
          verify(idmsConnector).addClientScope(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(clientId2) ,ArgumentMatchers.eq(scope3))(any())
          verifyNoMoreInteractions(idmsConnector)
          result mustBe Right(())
      }
    }

    "must return an IdmsException if any IDMS call fails but others succeed" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-application-id"
      val clientId = "test-client-id"
      val scope1 = "test-scope-1"
      val scope2 = "test-scope-2"

      val accessRequest = AccessRequest(
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Rejected,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(clock),
        requestedBy = "test-requested-by"
      )
        .addEndpoint("test-method-1", "test-path-1", Seq(scope1, scope2))

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      ).setPrimaryCredentials(
        Seq(
          Credential(clientId, LocalDateTime.now(clock), None, None)
        )
      )

      val exception = IdmsException("test-message", CallError)

      when(repository.findById(any())).thenReturn(Future.successful(Right(application)))
      when(idmsConnector.addClientScope(any(), any(), ArgumentMatchers.eq(scope1))(any())).thenReturn(Future.successful(Right(())))
      when(idmsConnector.addClientScope(any(), any(), ArgumentMatchers.eq(scope2))(any())).thenReturn(Future.successful(Left(exception)))

      service.addPrimaryAccess(accessRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Left(exception)
      }
    }

    "must return application not found exception when it does not exist" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-application-id"

      val accessRequest = AccessRequest(
        applicationId = applicationId,
        apiId = "test-api-id",
        apiName = "test-api-name",
        status = Rejected,
        supportingInformation = "test-supporting-information",
        requested = LocalDateTime.now(clock),
        requestedBy = "test-requested-by"
      )

      val exception = ApplicationNotFoundException.forId(applicationId)

      when(repository.findById(any())).thenReturn(Future.successful(Left(exception)))

      service.addPrimaryAccess(accessRequest)(HeaderCarrier()).map {
        result =>
          result mustBe Left(exception)
      }
    }
  }

  "addTeamMember" - {
    "must add the team member to the application and pass it to the repository" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock).minusDays(1),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minusDays(1),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      ).addTeamMember(TeamMember("test-existing-team-member-email"))

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))
      when(repository.update(any())).thenReturn(Future.successful(Right(())))

      val teamMember = TeamMember("test-team-member-email")

      service.addTeamMember(applicationId, teamMember)(HeaderCarrier()).map {
        result =>
          val updated = application.addTeamMember(teamMember).updated(clock)
          verify(repository).update(ArgumentMatchers.eq(updated))
          result mustBe Right(())
      }
    }

    "must return TeamMemberExistsException if the team member already exists in the application" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-id"

      val application = Application(
        id = Some(applicationId),
        name = "test-name",
        created = LocalDateTime.now(clock).minusDays(1),
        createdBy = Creator("test-email"),
        lastUpdated = LocalDateTime.now(clock).minusDays(1),
        teamMembers = Seq(TeamMember(email = "test-email")),
        environments = Environments()
      ).addTeamMember(TeamMember("test-existing-team-member-email"))

      when(repository.findById(ArgumentMatchers.eq(applicationId))).thenReturn(Future.successful(Right(application)))

      val teamMember = TeamMember("test-existing-team-member-email")

      service.addTeamMember(applicationId, teamMember)(HeaderCarrier()).map {
        result =>
          result mustBe Left(TeamMemberExistsException.forId(applicationId))
      }
    }

    "must return any exception returned by the repository" in {
      val fixture = buildFixture
      import fixture._

      val applicationId = "test-id"
      val expected = ApplicationNotFoundException.forId(applicationId)

      when(repository.findById(ArgumentMatchers.eq(applicationId)))
        .thenReturn(Future.successful(Left(expected)))

      val teamMember = TeamMember("test-team-member-email")

      service.addTeamMember(applicationId, teamMember)(HeaderCarrier()).map {
        result =>
          result mustBe Left(expected)
      }
    }
  }

  private case class Fixture(
    repository: ApplicationsRepository,
    idmsConnector: IdmsConnector,
    emailConnector: EmailConnector,
    service: ApplicationsService,
    accessRequestsService: AccessRequestsService,
    scopeFixer: ScopeFixer,
    teamsService: TeamsService
  )

  private def buildFixture: Fixture = {
    val repository: ApplicationsRepository = mock[ApplicationsRepository]
    val idmsConnector: IdmsConnector = mock[IdmsConnector]
    val emailConnector: EmailConnector = mock[EmailConnector]
    val accessRequestsService: AccessRequestsService = mock[AccessRequestsService]
    val scopeFixer = mock[ScopeFixer]
    val teamsService = mock[TeamsService]
    val applicationsSearchService: ApplicationsSearchService = new ApplicationsSearchService(repository, idmsConnector, teamsService)
    val service: ApplicationsService = new ApplicationsService(repository, clock, idmsConnector, emailConnector, accessRequestsService, scopeFixer, applicationsSearchService)
    Fixture(repository, idmsConnector, emailConnector, service, accessRequestsService, scopeFixer, teamsService)
  }

}

object ApplicationsServiceSpec {

  val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
  val currentUser = "me@test.com"

}
