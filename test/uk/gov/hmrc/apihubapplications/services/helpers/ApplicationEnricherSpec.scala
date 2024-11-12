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

package uk.gov.hmrc.apihubapplications.services.helpers

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{times, verify, verifyNoMoreInteractions, when}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.CallError
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, ClientScope}
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future

class ApplicationEnricherSpec extends AsyncFreeSpec
  with Matchers
  with MockitoSugar
  with EitherValues {

  import ApplicationEnricherSpec._

  private implicit val hc: HeaderCarrier = HeaderCarrier()
  private val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

  "process" - {
    "must correctly combine successful enrichers" in {
      val scope1 = Scope("test-scope-1")
      val scope2 = Scope("test-scope-2")

      ApplicationEnrichers.process(
        testApplication,
        Seq(
          successfulEnricher(_.addPrimaryScope(scope1)),
          successfulEnricher(_.addPrimaryScope(scope2))
        )
      ).map {
        actual =>
          actual mustBe Right(testApplication.setPrimaryScopes(Seq(scope1, scope2)))
      }
    }

    "must return an exception if any enricher returns an exception" in {
      ApplicationEnrichers.process(
        testApplication,
        Seq(
          successfulEnricher(identity),
          unsuccessfulEnricher()
        )
      ).map {
        actual =>
          actual mustBe Left(testException)
      }
    }

    "must handle an empty sequence" in {
      ApplicationEnrichers.process(
        testApplication,
        Seq.empty
      ).map {
        actual =>
          actual mustBe Right(testApplication)
      }
    }
  }

  "processAll" - {
    "must successfully process multiple applications" in {
      ApplicationEnrichers.processAll(
        Seq(testApplication, testApplication2),
        (application, _) =>
          successfulEnricher(
            _ => application.addTeamMember(s"team-member-${application.id}")
          )
        ,
        mock[IdmsConnector]
      ).map(
        actual =>
          actual mustBe Right(
            Seq(
              testApplication.addTeamMember(s"team-member-${testApplication.id}"),
              testApplication2.addTeamMember(s"team-member-${testApplication2.id}")
            )
          )
      )
    }

    "must return an exception if the enricher returns an exception for any application" in {
      val idmsException = IdmsException.clientNotFound("test-client-id")

      ApplicationEnrichers.processAll(
        Seq(testApplication, testApplication2),
        (application, _) =>
          if (application == testApplication) {
            Future.successful(Left(idmsException))
          }
          else {
            successfulEnricher(identity)
          }
        ,
        mock[IdmsConnector]
      ).map(
        actual =>
          actual mustBe Left(idmsException)
      )
    }

    "must handle an empty sequence of applications" in {
      ApplicationEnrichers.processAll(
        Seq.empty,
        (_, _) => successfulEnricher(identity),
        mock[IdmsConnector]
      ).map(
        actual =>
          actual mustBe Right(Seq.empty)
      )
    }
  }

  "secondaryCredentialApplicationEnricher" - {
    "must enrich an application with secondary credentials" in {
      val application = testApplication
        .setSecondaryCredentials(
          Seq(
            testClientResponse1.asNewCredential(clock),
            testClientResponse2.asNewCredential(clock)
          )
        )

      val expected = application
        .updateSecondaryCredential(testClientResponse1.clientId, testClientResponse1.secret)
        .updateSecondaryCredential(testClientResponse2.clientId, testClientResponse2.secret)

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.fetchClient(eqTo(Secondary), eqTo(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Right(testClientResponse1)))
      when(idmsConnector.fetchClient(eqTo(Secondary), eqTo(testClientResponse2.clientId))(any()))
        .thenReturn(Future.successful(Right(testClientResponse2)))

      ApplicationEnrichers.secondaryCredentialApplicationEnricher(application, idmsConnector).map {
        case Right(enricher) => enricher.enrich(application) mustBe expected
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must not modify an application if there are zero secondary credentials" in {
      ApplicationEnrichers.secondaryCredentialApplicationEnricher(testApplication, mock[IdmsConnector]).map {
        case Right(enricher) => enricher.enrich(testApplication) mustBe testApplication
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must not return IdmsException if any call to IDMS fails" in {
      val application = testApplication
        .setSecondaryCredentials(
          Seq(
            testClientResponse1.asNewCredential(clock),
            testClientResponse2.asNewCredential(clock)
          )
        )

      val applicationWithIssues = application.copy(issues = Seq("Secondary credential not found. test-message"))

      val exception = IdmsException("test-message", CallError)

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.fetchClient(eqTo(Secondary), eqTo(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Right(testClientResponse1)))
      when(idmsConnector.fetchClient(eqTo(Secondary), eqTo(testClientResponse2.clientId))(any()))
        .thenReturn(Future.successful(Left(exception)))

      ApplicationEnrichers.secondaryCredentialApplicationEnricher(application, idmsConnector) map {
        case Right(enricher) =>
          enricher.enrich(application) mustBe applicationWithIssues
          verify(idmsConnector, times(2)).fetchClient(any(), any())(any())
          succeed
        case Left(e) => fail("Unexpected Left response", e)
      }

    }

    "must add an application issue if a credential cannot be found" in {
      val application = testApplication
        .setSecondaryCredentials(
          Seq(
            testClientResponse1.asNewCredential(clock),
            testClientResponse2.asNewCredential(clock)
          )
        )

      val expected = application
        .updateSecondaryCredential(testClientResponse1.clientId, testClientResponse1.secret)
        .addIssue(Issues.secondaryCredentialNotFound(IdmsException.clientNotFound(testClientId2)))

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.fetchClient(eqTo(Secondary), eqTo(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Right(testClientResponse1)))
      when(idmsConnector.fetchClient(eqTo(Secondary), eqTo(testClientResponse2.clientId))(any()))
        .thenReturn(Future.successful(Left(IdmsException.clientNotFound(testClientId2))))

      ApplicationEnrichers.secondaryCredentialApplicationEnricher(application, idmsConnector).map {
        case Right(enricher) => enricher.enrich(application) mustBe expected
        case Left(e) => fail("Unexpected Left response", e)
      }
    }
  }

  "secondaryScopeApplicationEnricher" - {
    "must enrich an application with secondary scopes using the secondary master credential" in {
      val masterCredential = testClientResponse1.asNewCredential(clock)
      val oldCredential = Credential("test-older-client-id", LocalDateTime.now(clock).minusDays(1), None, None)

      val application = testApplication
        .setSecondaryCredentials(Seq(oldCredential, masterCredential))

      val expected = application
        .setSecondaryScopes(
          Seq(
            Scope(testClientScope1.clientScopeId),
            Scope(testClientScope2.clientScopeId)
          )
        )

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.fetchClientScopes(eqTo(Secondary), eqTo(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Right(Seq(testClientScope1, testClientScope2))))

      ApplicationEnrichers.secondaryScopeApplicationEnricher(application, idmsConnector).map {
        case Right(enricher) => enricher.enrich(application) mustBe expected
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must not modify an application if there are zero secondary credentials" in {
      ApplicationEnrichers.secondaryScopeApplicationEnricher(testApplication, mock[IdmsConnector]).map {
        case Right(enricher) => enricher.enrich(testApplication) mustBe testApplication
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must not return IdmsException if any call to IDMS fails" in {
      val application = testApplication
        .setSecondaryCredentials(Seq(testClientResponse1.asNewCredential(clock)))

      val exception = IdmsException("test-message", CallError)

      val applicationWithIssues = application.copy(issues = Seq("Secondary scopes not found. test-message"))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.fetchClientScopes(eqTo(Secondary), eqTo(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Left(exception)))

      ApplicationEnrichers.secondaryScopeApplicationEnricher(application, idmsConnector) map {
        case Right(enricher) =>
          enricher.enrich(application) mustBe applicationWithIssues
          verify(idmsConnector).fetchClientScopes(any(), any())(any())
          succeed
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must add an application issue if the secondary credential cannot be found" in {
      val application = testApplication
        .setSecondaryCredentials(Seq(testClientResponse1.asNewCredential(clock)))

      val expected = application
        .addIssue(Issues.secondaryScopesNotFound(IdmsException.clientNotFound(testClientId1)))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.fetchClientScopes(eqTo(Secondary), eqTo(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Left(IdmsException.clientNotFound(testClientId1))))

      ApplicationEnrichers.secondaryScopeApplicationEnricher(application, idmsConnector).map {
        case Right(enricher) => enricher.enrich(application) mustBe expected
        case Left(e) => fail("Unexpected Left response", e)
      }
    }
  }

  "primaryScopeApplicationEnricher" - {
    "must not modify an application if there are zero primary credentials" in {
      ApplicationEnrichers.primaryScopeApplicationEnricher(testApplication, mock[IdmsConnector]).map {
        case Right(enricher) => enricher.enrich(testApplication) mustBe testApplication
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must return IdmsException if any call to IDMS fails" in {
      val application = testApplication
        .setPrimaryCredentials(Seq(testClientResponse1.asNewCredential(clock)))

      val exception = IdmsException("test-message", CallError)
      val applicationWithIssues = application.copy(issues = Seq("Primary scopes not found. test-message"))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.fetchClientScopes(eqTo(Primary), eqTo(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Left(exception)))

      ApplicationEnrichers.primaryScopeApplicationEnricher(application, idmsConnector) map {
        case Right(enricher) =>
          enricher.enrich(application) mustBe applicationWithIssues
          verify(idmsConnector).fetchClientScopes(any(), any())(any())
          succeed
        case Left(e) => fail("Unexpected Left response", e)
      }
    }
  }

  "must add an application issue if the secondary credential cannot be found" in {
    val application = testApplication
      .setPrimaryCredentials(Seq(testClientResponse1.asNewCredential(clock)))

    val expected = application
      .addIssue(Issues.primaryScopesNotFound(IdmsException.clientNotFound(testClientId1)))

    val idmsConnector = mock[IdmsConnector]
    when(idmsConnector.fetchClientScopes(eqTo(Primary), eqTo(testClientResponse1.clientId))(any()))
      .thenReturn(Future.successful(Left(IdmsException.clientNotFound(testClientId1))))

    ApplicationEnrichers.primaryScopeApplicationEnricher(application, idmsConnector).map {
      case Right(enricher) => enricher.enrich(application) mustBe expected
      case Left(e) => fail("Unexpected Left response", e)
    }
  }


  "credentialCreatingApplicationEnricher" - {
    "must create a credential in the primary environment and enrich the application with it" in {
      val expected = testApplication.setPrimaryCredentials(Seq(Credential(testClientResponse1.clientId, LocalDateTime.now(clock), None, None)))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.createClient(eqTo(Primary), eqTo(Client(testApplication)))(any()))
        .thenReturn(Future.successful(Right(testClientResponse1)))

      ApplicationEnrichers.credentialCreatingApplicationEnricher(Primary, testApplication, idmsConnector, clock).map {
        case Right(enricher) => enricher.enrich(testApplication) mustBe expected
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must create a credential in the secondary environment and enrich the application with it" in {
      val expected = testApplication.setSecondaryCredentials(Seq(testClientResponse1.asNewCredential(clock)))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.createClient(eqTo(Secondary), eqTo(Client(testApplication)))(any()))
        .thenReturn(Future.successful(Right(testClientResponse1)))

      ApplicationEnrichers.credentialCreatingApplicationEnricher(Secondary, testApplication, idmsConnector, clock).map {
        case Right(enricher) => enricher.enrich(testApplication) mustBe expected
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must return IdmsException if any call to IDMS fails" in {
      val expected = IdmsException("test-message", CallError)

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.createClient(eqTo(Primary), eqTo(Client(testApplication)))(any()))
        .thenReturn(Future.successful(Left(expected)))

      ApplicationEnrichers.credentialCreatingApplicationEnricher(Primary, testApplication, idmsConnector, clock).map {
        actual =>
          actual mustBe Left(expected)
      }
    }
  }

  "credentialDeletingApplicationEnricher" - {
    "must delete a credential from the primary environment and remove it from the application" in {
      val application = testApplication.addPrimaryCredential(Credential(testClientId1, LocalDateTime.now(clock), None, None))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.deleteClient(eqTo(Primary), eqTo(testClientId1))(any()))
        .thenReturn(Future.successful(Right(())))

      ApplicationEnrichers.credentialDeletingApplicationEnricher(Primary, testClientId1, idmsConnector).map {
        case Right(enricher) =>
          enricher.enrich(application) mustBe testApplication
          verify(idmsConnector).deleteClient(any(), any())(any())
          succeed
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must delete a credential from the secondary environment and remove it from the application" in {
      val application = testApplication.addSecondaryCredential(Credential(testClientId1, LocalDateTime.now(clock), None, None))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.deleteClient(eqTo(Secondary), eqTo(testClientId1))(any()))
        .thenReturn(Future.successful(Right(())))

      ApplicationEnrichers.credentialDeletingApplicationEnricher(Secondary, testClientId1, idmsConnector).map {
        case Right(enricher) =>
          enricher.enrich(application) mustBe testApplication
          verify(idmsConnector).deleteClient(any(), any())(any())
          succeed
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must succeed if the client is not found in IDMS" in {
      val application = testApplication.addPrimaryCredential(Credential(testClientId1, LocalDateTime.now(clock), None, None))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.deleteClient(eqTo(Primary), eqTo(testClientId1))(any()))
        .thenReturn(Future.successful(Left(IdmsException.clientNotFound(testClientId1))))

      ApplicationEnrichers.credentialDeletingApplicationEnricher(Primary, testClientId1, idmsConnector).map {
        case Right(enricher) =>
          enricher.enrich(application) mustBe testApplication
          verify(idmsConnector).deleteClient(any(), any())(any())
          succeed
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must return IdmsException if any call to IDMS fails (other than client not found)" in {
      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.deleteClient(any(), any())(any()))
        .thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))

      ApplicationEnrichers.credentialDeletingApplicationEnricher(Primary, testClientId1, idmsConnector).map {
        actual =>
          actual mustBe Left(IdmsException.unexpectedResponse(500))
      }
    }
  }

  "scopeAddingApplicationEnricher" - {
    "must add a scope in the primary environment and enrich the application with it" in {
      val clientId1 = "test-client-id-1"
      val clientId2 = "test-client-id-2"

      val application = testApplication.setPrimaryCredentials(
        Seq(
          Credential(clientId1, LocalDateTime.now(clock), None, None),
          Credential(clientId2, LocalDateTime.now(clock), None, None)
        )
      )

      val scope = "test-scope"
      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.addClientScope(eqTo(Primary), any(), eqTo(scope))(any()))
        .thenReturn(Future.successful(Right(())))

      ApplicationEnrichers.scopeAddingApplicationEnricher(Primary, application, idmsConnector, scope).map {
        case Right(enricher) =>
          enricher.enrich(application) mustBe application.addPrimaryScope(Scope(scope))
          verify(idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scope))(any())
          verify(idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId2), eqTo(scope))(any())
          succeed
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must add a scope in the secondary environment and enrich the application with it" in {
      val clientId = "test-client-id"
      val application = testApplication.setSecondaryCredentials(Seq(Credential(clientId, LocalDateTime.now(clock), None, None)))
      val scope = "test-scope"
      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.addClientScope(eqTo(Secondary), eqTo(clientId), eqTo(scope))(any()))
        .thenReturn(Future.successful(Right(())))

      ApplicationEnrichers.scopeAddingApplicationEnricher(Secondary, application, idmsConnector, scope).map {
        case Right(enricher) =>
          enricher.enrich(application) mustBe application.addSecondaryScope(Scope(scope))
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must return IdmsException if any call to IDMS fails" in {
      val application = testApplication.setSecondaryCredentials(Seq(Credential("test-client-id", LocalDateTime.now(clock), None, None)))
      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.addClientScope(any(), any(), any())(any()))
        .thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))

      ApplicationEnrichers.scopeAddingApplicationEnricher(Secondary, application, idmsConnector, "test-scope").map {
        actual =>
          actual mustBe Left(IdmsException.unexpectedResponse(500))
      }
    }
  }

  "scopeRemovingApplicationEnricher" - {
    "must remove a scope from the primary environment and also the application" in {
      val expected = testApplication
        .addPrimaryCredential(Credential(testClientId1, LocalDateTime.now(), None, None))
        .addPrimaryCredential(Credential(testClientId2, LocalDateTime.now(), None, None))
        .addPrimaryScope(Scope(testScopeName2))
        .addSecondaryScope(Scope(testScopeName3))

      val application = expected
        .addPrimaryScope(Scope(testScopeName1))

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.deleteClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      ApplicationEnrichers.scopeRemovingApplicationEnricher(Primary, application, idmsConnector, testScopeName1).map {
        result =>
          verify(idmsConnector).deleteClientScope(eqTo(Primary), eqTo(testClientId1), eqTo(testScopeName1))(any())
          verify(idmsConnector).deleteClientScope(eqTo(Primary), eqTo(testClientId2), eqTo(testScopeName1))(any())
          result.value.enrich(application) mustBe expected
      }
    }

    "must remove a scope from the secondary environment and also the application" in {
      val expected = testApplication
        .addSecondaryCredential(Credential(testClientId1, LocalDateTime.now(), None, None))
        .addSecondaryCredential(Credential(testClientId2, LocalDateTime.now(), None, None))
        .addPrimaryScope(Scope(testScopeName2))
        .addSecondaryScope(Scope(testScopeName3))

      val application = expected
        .addSecondaryScope(Scope(testScopeName1))

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.deleteClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      ApplicationEnrichers.scopeRemovingApplicationEnricher(Secondary, application, idmsConnector, testScopeName1).map {
        result =>
          verify(idmsConnector).deleteClientScope(eqTo(Secondary), eqTo(testClientId1), eqTo(testScopeName1))(any())
          verify(idmsConnector).deleteClientScope(eqTo(Secondary), eqTo(testClientId2), eqTo(testScopeName1))(any())
          result.value.enrich(application) mustBe expected
      }
    }

    "must ignore a Not Found response from IDMS (as this is the desired situation)" in {
      val expected = testApplication
        .addPrimaryCredential(Credential(testClientId1, LocalDateTime.now(), None, None))

      val application = expected
        .addPrimaryScope(Scope(testScopeName1))

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.deleteClientScope(any(), any(), any())(any()))
        .thenReturn(Future.successful(Left(IdmsException.clientNotFound(testClientId1))))

      ApplicationEnrichers.scopeRemovingApplicationEnricher(Primary, application, idmsConnector, testScopeName1).map {
        result =>
          result.value.enrich(application) mustBe expected
      }
    }

    "must return IdmsException if any call to IDMS fails" in {
      val application = testApplication
        .addPrimaryCredential(Credential(testClientId1, LocalDateTime.now(), None, None))

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.deleteClientScope(any(), any(), any())(any()))
        .thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))

      ApplicationEnrichers.scopeRemovingApplicationEnricher(Primary, application, idmsConnector, testScopeName1).map {
        actual =>
          actual mustBe Left(IdmsException.unexpectedResponse(500))
      }
    }

    "must only process a single credential when asked to (master credential)" in {
      val expected = testApplication
        .addPrimaryCredential(Credential(testClientId1, LocalDateTime.now(clock), None, None))
        .addPrimaryCredential(Credential(testClientId2, LocalDateTime.now(clock).plusMinutes(1), None, None))
        .addPrimaryScope(Scope(testScopeName2))
        .addSecondaryScope(Scope(testScopeName3))

      val application = expected
        .addPrimaryScope(Scope(testScopeName1))

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.deleteClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      ApplicationEnrichers.scopeRemovingApplicationEnricher(Primary, application, idmsConnector, testScopeName1, Some(testClientId2)).map {
        result =>
          verify(idmsConnector).deleteClientScope(eqTo(Primary), eqTo(testClientId2), eqTo(testScopeName1))(any())
          verifyNoMoreInteractions(idmsConnector)
          result.value.enrich(application) mustBe expected
      }
    }

    "must only process a single credential when asked to (not master credential)" in {
      val application = testApplication
        .addPrimaryCredential(Credential(testClientId1, LocalDateTime.now(clock), None, None))
        .addPrimaryCredential(Credential(testClientId2, LocalDateTime.now(clock).plusMinutes(1), None, None))
        .addPrimaryScope(Scope(testScopeName1))
        .addPrimaryScope(Scope(testScopeName2))
        .addSecondaryScope(Scope(testScopeName3))

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.deleteClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      ApplicationEnrichers.scopeRemovingApplicationEnricher(Primary, application, idmsConnector, testScopeName1, Some(testClientId1)).map {
        result =>
          verify(idmsConnector).deleteClientScope(eqTo(Primary), eqTo(testClientId1), eqTo(testScopeName1))(any())
          verifyNoMoreInteractions(idmsConnector)
          result.value.enrich(application) mustBe application
      }
    }
  }

}

object ApplicationEnricherSpec {

  val testApplication: Application = Application(Some("test-id-1"), "test-description", Creator("test-email"), Seq.empty)
  val testApplication2: Application = Application(Some("test-id-2"), "test-description-2", Creator("test-email-2"), Seq.empty)
  val testClientId1: String = "test-client-id-1"
  val testClientId2: String = "test-client-id-2"
  val testClientResponse1: ClientResponse = ClientResponse(testClientId1, "test-secret-1")
  val testClientResponse2: ClientResponse = ClientResponse(testClientId2, "test-secret-2")
  val testScopeName1: String = "test-scope-name-1"
  val testScopeName2: String = "test-scope-name-2"
  val testScopeName3: String = "test-scope-name-3"
  val testClientScope1: ClientScope = ClientScope(testScopeName1)
  val testClientScope2: ClientScope = ClientScope(testScopeName2)
  val testException: IdmsException = IdmsException("test-exception", CallError)

  def successfulEnricher(mutator: Application => Application): Future[Either[IdmsException, ApplicationEnricher]] = {
    Future.successful(
      Right(
        (application: Application) => mutator.apply(application)
      )
    )
  }

  def unsuccessfulEnricher(): Future[Either[IdmsException, ApplicationEnricher]] = {
    Future.successful(Left(testException))
  }

}
