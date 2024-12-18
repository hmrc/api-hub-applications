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
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments
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
          successfulEnricher(_.addScope(FakeHipEnvironments.primaryEnvironment, scope1.name)),
          successfulEnricher(_.addScope(FakeHipEnvironments.primaryEnvironment, scope2.name))
        )
      ).map {
        actual =>
          actual mustBe Right(testApplication.setScopes(FakeHipEnvironments.primaryEnvironment, Seq(scope1, scope2)))
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
        .setCredentials(
          FakeHipEnvironments.secondaryEnvironment,
          Seq(
            testClientResponse1.asNewCredential(clock, FakeHipEnvironments.secondaryEnvironment),
            testClientResponse2.asNewCredential(clock, FakeHipEnvironments.secondaryEnvironment)
          )
        )

      val expected = application
        .updateCredential(FakeHipEnvironments.secondaryEnvironment, testClientResponse1.clientId, testClientResponse1.secret)
        .updateCredential(FakeHipEnvironments.secondaryEnvironment, testClientResponse2.clientId, testClientResponse2.secret)

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.fetchClient(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Right(testClientResponse1)))
      when(idmsConnector.fetchClient(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(testClientResponse2.clientId))(any()))
        .thenReturn(Future.successful(Right(testClientResponse2)))

      ApplicationEnrichers.secondaryCredentialApplicationEnricher(application, idmsConnector, FakeHipEnvironments).map {
        case Right(enricher) => enricher.enrich(application) mustBe expected
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must not modify an application if there are zero secondary credentials" in {
      ApplicationEnrichers.secondaryCredentialApplicationEnricher(testApplication, mock[IdmsConnector], FakeHipEnvironments).map {
        case Right(enricher) => enricher.enrich(testApplication) mustBe testApplication
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must not return IdmsException if any call to IDMS fails" in {
      val application = testApplication
        .setCredentials(
          FakeHipEnvironments.secondaryEnvironment,
          Seq(
            testClientResponse1.asNewCredential(clock, FakeHipEnvironments.secondaryEnvironment),
            testClientResponse2.asNewCredential(clock, FakeHipEnvironments.secondaryEnvironment)
          )
        )

      val applicationWithIssues = application.copy(issues = Seq("Secondary credential not found. test-message"))

      val exception = IdmsException("test-message", CallError)

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.fetchClient(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Right(testClientResponse1)))
      when(idmsConnector.fetchClient(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(testClientResponse2.clientId))(any()))
        .thenReturn(Future.successful(Left(exception)))

      ApplicationEnrichers.secondaryCredentialApplicationEnricher(application, idmsConnector, FakeHipEnvironments) map {
        case Right(enricher) =>
          enricher.enrich(application) mustBe applicationWithIssues
          verify(idmsConnector, times(2)).fetchClient(any(), any())(any())
          succeed
        case Left(e) => fail("Unexpected Left response", e)
      }

    }

    "must add an application issue if a credential cannot be found" in {
      val application = testApplication
        .setCredentials(
          FakeHipEnvironments.secondaryEnvironment,
          Seq(
            testClientResponse1.asNewCredential(clock, FakeHipEnvironments.secondaryEnvironment),
            testClientResponse2.asNewCredential(clock, FakeHipEnvironments.secondaryEnvironment)
          )
        )

      val expected = application
        .updateCredential(FakeHipEnvironments.secondaryEnvironment, testClientResponse1.clientId, testClientResponse1.secret)
        .addIssue(Issues.secondaryCredentialNotFound(IdmsException.clientNotFound(testClientId2)))

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.fetchClient(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Right(testClientResponse1)))
      when(idmsConnector.fetchClient(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(testClientResponse2.clientId))(any()))
        .thenReturn(Future.successful(Left(IdmsException.clientNotFound(testClientId2))))

      ApplicationEnrichers.secondaryCredentialApplicationEnricher(application, idmsConnector, FakeHipEnvironments).map {
        case Right(enricher) => enricher.enrich(application) mustBe expected
        case Left(e) => fail("Unexpected Left response", e)
      }
    }
  }

  "secondaryScopeApplicationEnricher" - {
    "must enrich an application with secondary scopes using the secondary master credential" in {
      val masterCredential = testClientResponse1.asNewCredential(clock, FakeHipEnvironments.secondaryEnvironment)
      val oldCredential = Credential("test-older-client-id", LocalDateTime.now(clock).minusDays(1), None, None, FakeHipEnvironments.secondaryEnvironment.id)

      val application = testApplication
        .setCredentials(FakeHipEnvironments.secondaryEnvironment, Seq(oldCredential, masterCredential))

      val expected = application
        .setScopes(
          FakeHipEnvironments.secondaryEnvironment,
          Seq(
            Scope(testClientScope1.clientScopeId),
            Scope(testClientScope2.clientScopeId)
          )
        )

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Right(Seq(testClientScope1, testClientScope2))))

      ApplicationEnrichers.secondaryScopeApplicationEnricher(application, idmsConnector, FakeHipEnvironments).map {
        case Right(enricher) => enricher.enrich(application) mustBe expected
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must not modify an application if there are zero secondary credentials" in {
      ApplicationEnrichers.secondaryScopeApplicationEnricher(testApplication, mock[IdmsConnector], FakeHipEnvironments).map {
        case Right(enricher) => enricher.enrich(testApplication) mustBe testApplication
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must not return IdmsException if any call to IDMS fails" in {
      val application = testApplication
        .setCredentials(FakeHipEnvironments.secondaryEnvironment, Seq(testClientResponse1.asNewCredential(clock, FakeHipEnvironments.secondaryEnvironment)))

      val exception = IdmsException("test-message", CallError)

      val applicationWithIssues = application.copy(issues = Seq("Secondary scopes not found. test-message"))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Left(exception)))

      ApplicationEnrichers.secondaryScopeApplicationEnricher(application, idmsConnector, FakeHipEnvironments) map {
        case Right(enricher) =>
          enricher.enrich(application) mustBe applicationWithIssues
          verify(idmsConnector).fetchClientScopes(any(), any())(any())
          succeed
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must add an application issue if the secondary credential cannot be found" in {
      val application = testApplication
        .setCredentials(FakeHipEnvironments.secondaryEnvironment, Seq(testClientResponse1.asNewCredential(clock, FakeHipEnvironments.secondaryEnvironment)))

      val expected = application
        .addIssue(Issues.secondaryScopesNotFound(IdmsException.clientNotFound(testClientId1)))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Left(IdmsException.clientNotFound(testClientId1))))

      ApplicationEnrichers.secondaryScopeApplicationEnricher(application, idmsConnector, FakeHipEnvironments).map {
        case Right(enricher) => enricher.enrich(application) mustBe expected
        case Left(e) => fail("Unexpected Left response", e)
      }
    }
  }

  "primaryScopeApplicationEnricher" - {
    "must not modify an application if there are zero primary credentials" in {
      ApplicationEnrichers.primaryScopeApplicationEnricher(testApplication, mock[IdmsConnector], FakeHipEnvironments).map {
        case Right(enricher) => enricher.enrich(testApplication) mustBe testApplication
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must return IdmsException if any call to IDMS fails" in {
      val application = testApplication
        .setCredentials(FakeHipEnvironments.primaryEnvironment, Seq(testClientResponse1.asNewCredential(clock, FakeHipEnvironments.primaryEnvironment)))

      val exception = IdmsException("test-message", CallError)
      val applicationWithIssues = application.copy(issues = Seq("Primary scopes not found. test-message"))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Left(exception)))

      ApplicationEnrichers.primaryScopeApplicationEnricher(application, idmsConnector, FakeHipEnvironments) map {
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
      .setCredentials(FakeHipEnvironments.primaryEnvironment, Seq(testClientResponse1.asNewCredential(clock, FakeHipEnvironments.primaryEnvironment)))

    val expected = application
      .addIssue(Issues.primaryScopesNotFound(IdmsException.clientNotFound(testClientId1)))

    val idmsConnector = mock[IdmsConnector]
    when(idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(testClientResponse1.clientId))(any()))
      .thenReturn(Future.successful(Left(IdmsException.clientNotFound(testClientId1))))

    ApplicationEnrichers.primaryScopeApplicationEnricher(application, idmsConnector, FakeHipEnvironments).map {
      case Right(enricher) => enricher.enrich(application) mustBe expected
      case Left(e) => fail("Unexpected Left response", e)
    }
  }


  "credentialCreatingApplicationEnricher" - {
    "must create a credential in the hip environments and enrich the application with it" in {
      val expected = Seq(
        testApplication.setCredentials(FakeHipEnvironments.primaryEnvironment, Seq(testClientResponse1.asNewCredential(clock, FakeHipEnvironments.primaryEnvironment))),
        testApplication.setCredentials(FakeHipEnvironments.secondaryEnvironment, Seq(testClientResponse2.asNewCredential(clock, FakeHipEnvironments.secondaryEnvironment))),
      )

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.createClient(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(Client(testApplication)))(any()))
        .thenReturn(Future.successful(Right(testClientResponse1)))
      when(idmsConnector.createClient(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(Client(testApplication)))(any()))
        .thenReturn(Future.successful(Right(testClientResponse2)))

      val results = Future.sequence(
        FakeHipEnvironments.environments.map(
          ApplicationEnrichers.credentialCreatingApplicationEnricher(_, testApplication, idmsConnector, clock)
        )).map(_.map {
            case Right(enricher) => enricher.enrich(testApplication)
            case Left(e) => fail("Unexpected Left response", e)
          })

      results.map(
        _ mustBe expected
      )
    }

    "must return IdmsException if any call to IDMS fails" in {
      val expected = IdmsException("test-message", CallError)

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.createClient(any, eqTo(Client(testApplication)))(any()))
        .thenReturn(Future.successful(Left(expected)))

      ApplicationEnrichers.credentialCreatingApplicationEnricher(FakeHipEnvironments.environments.head, testApplication, idmsConnector, clock).map {
        case Right(_) => fail("Unexpected Right response")
        case Left(e) => e mustBe expected
      }
    }
  }
  
  "scopeAddingApplicationEnricher" - {
    "must add a scope in the primary environment and enrich the application with it" in {
      val clientId1 = "test-client-id-1"
      val clientId2 = "test-client-id-2"

      val application = testApplication.setCredentials(
        FakeHipEnvironments.primaryEnvironment,
        Seq(
          Credential(clientId1, LocalDateTime.now(clock), None, None, FakeHipEnvironments.primaryEnvironment.id),
          Credential(clientId2, LocalDateTime.now(clock), None, None, FakeHipEnvironments.primaryEnvironment.id)
        )
      )

      val scope = "test-scope"
      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.addClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), any(), eqTo(scope))(any()))
        .thenReturn(Future.successful(Right(())))

      ApplicationEnrichers.scopeAddingApplicationEnricher(FakeHipEnvironments.primaryEnvironment, application, idmsConnector, scope).map {
        case Right(enricher) =>
          enricher.enrich(application) mustBe application.addScope(FakeHipEnvironments.primaryEnvironment, scope)
          verify(idmsConnector).addClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId1), eqTo(scope))(any())
          verify(idmsConnector).addClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId2), eqTo(scope))(any())
          succeed
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must add a scope in the secondary environment and enrich the application with it" in {
      val clientId = "test-client-id"
      val application = testApplication.setCredentials(FakeHipEnvironments.secondaryEnvironment, Seq(Credential(clientId, LocalDateTime.now(clock), None, None, FakeHipEnvironments.secondaryEnvironment.id)))
      val scope = "test-scope"
      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.addClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId), eqTo(scope))(any()))
        .thenReturn(Future.successful(Right(())))

      ApplicationEnrichers.scopeAddingApplicationEnricher(FakeHipEnvironments.secondaryEnvironment, application, idmsConnector, scope).map {
        case Right(enricher) =>
          enricher.enrich(application) mustBe application.addScope(FakeHipEnvironments.secondaryEnvironment, scope)
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must return IdmsException if any call to IDMS fails" in {
      val application = testApplication.setCredentials(FakeHipEnvironments.secondaryEnvironment, Seq(Credential("test-client-id", LocalDateTime.now(clock), None, None, FakeHipEnvironments.secondaryEnvironment.id)))
      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.addClientScope(any(), any(), any())(any()))
        .thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))

      ApplicationEnrichers.scopeAddingApplicationEnricher(FakeHipEnvironments.secondaryEnvironment, application, idmsConnector, "test-scope").map {
        actual =>
          actual mustBe Left(IdmsException.unexpectedResponse(500))
      }
    }
  }

  "scopeRemovingApplicationEnricher" - {
    "must remove a scope from the primary environment and also the application" in {
      val expected = testApplication
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential(testClientId1, LocalDateTime.now(), None, None, FakeHipEnvironments.primaryEnvironment.id))
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential(testClientId2, LocalDateTime.now(), None, None, FakeHipEnvironments.primaryEnvironment.id))
        .addScope(FakeHipEnvironments.primaryEnvironment, testScopeName2)
        .addScope(FakeHipEnvironments.secondaryEnvironment, testScopeName3)

      val application = expected
        .addScope(FakeHipEnvironments.primaryEnvironment, testScopeName1)

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.deleteClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      ApplicationEnrichers.scopeRemovingApplicationEnricher(FakeHipEnvironments.primaryEnvironment, application, idmsConnector, testScopeName1).map {
        result =>
          verify(idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(testClientId1), eqTo(testScopeName1))(any())
          verify(idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(testClientId2), eqTo(testScopeName1))(any())
          result.value.enrich(application) mustBe expected
      }
    }

    "must remove a scope from the secondary environment and also the application" in {
      val expected = testApplication
        .addCredential(FakeHipEnvironments.secondaryEnvironment, Credential(testClientId1, LocalDateTime.now(), None, None, FakeHipEnvironments.secondaryEnvironment.id))
        .addCredential(FakeHipEnvironments.secondaryEnvironment, Credential(testClientId2, LocalDateTime.now(), None, None, FakeHipEnvironments.secondaryEnvironment.id))
        .addScope(FakeHipEnvironments.primaryEnvironment, testScopeName2)
        .addScope(FakeHipEnvironments.secondaryEnvironment, testScopeName3)

      val application = expected
        .addScope(FakeHipEnvironments.secondaryEnvironment, testScopeName1)

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.deleteClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      ApplicationEnrichers.scopeRemovingApplicationEnricher(FakeHipEnvironments.secondaryEnvironment, application, idmsConnector, testScopeName1).map {
        result =>
          verify(idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(testClientId1), eqTo(testScopeName1))(any())
          verify(idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(testClientId2), eqTo(testScopeName1))(any())
          result.value.enrich(application) mustBe expected
      }
    }

    "must ignore a Not Found response from IDMS (as this is the desired situation)" in {
      val expected = testApplication
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential(testClientId1, LocalDateTime.now(), None, None, FakeHipEnvironments.primaryEnvironment.id))

      val application = expected
        .addScope(FakeHipEnvironments.primaryEnvironment, testScopeName1)

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.deleteClientScope(any(), any(), any())(any()))
        .thenReturn(Future.successful(Left(IdmsException.clientNotFound(testClientId1))))

      ApplicationEnrichers.scopeRemovingApplicationEnricher(FakeHipEnvironments.primaryEnvironment, application, idmsConnector, testScopeName1).map {
        result =>
          result.value.enrich(application) mustBe expected
      }
    }

    "must return IdmsException if any call to IDMS fails" in {
      val application = testApplication
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential(testClientId1, LocalDateTime.now(), None, None, FakeHipEnvironments.primaryEnvironment.id))

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.deleteClientScope(any(), any(), any())(any()))
        .thenReturn(Future.successful(Left(IdmsException.unexpectedResponse(500))))

      ApplicationEnrichers.scopeRemovingApplicationEnricher(FakeHipEnvironments.primaryEnvironment, application, idmsConnector, testScopeName1).map {
        actual =>
          actual mustBe Left(IdmsException.unexpectedResponse(500))
      }
    }

    "must only process a single credential when asked to (master credential)" in {
      val expected = testApplication
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential(testClientId1, LocalDateTime.now(clock), None, None, FakeHipEnvironments.primaryEnvironment.id))
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential(testClientId2, LocalDateTime.now(clock).plusMinutes(1), None, None, FakeHipEnvironments.primaryEnvironment.id))
        .addScope(FakeHipEnvironments.primaryEnvironment, testScopeName2)
        .addScope(FakeHipEnvironments.secondaryEnvironment, testScopeName3)

      val application = expected
        .addScope(FakeHipEnvironments.primaryEnvironment, testScopeName1)

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.deleteClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      ApplicationEnrichers.scopeRemovingApplicationEnricher(FakeHipEnvironments.primaryEnvironment, application, idmsConnector, testScopeName1, Some(testClientId2)).map {
        result =>
          verify(idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(testClientId2), eqTo(testScopeName1))(any())
          verifyNoMoreInteractions(idmsConnector)
          result.value.enrich(application) mustBe expected
      }
    }

    "must only process a single credential when asked to (not master credential)" in {
      val application = testApplication
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential(testClientId1, LocalDateTime.now(clock), None, None, FakeHipEnvironments.primaryEnvironment.id))
        .addCredential(FakeHipEnvironments.primaryEnvironment, Credential(testClientId2, LocalDateTime.now(clock).plusMinutes(1), None, None, FakeHipEnvironments.primaryEnvironment.id))
        .addScope(FakeHipEnvironments.primaryEnvironment, testScopeName1)
        .addScope(FakeHipEnvironments.primaryEnvironment, testScopeName2)
        .addScope(FakeHipEnvironments.secondaryEnvironment, testScopeName3)

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.deleteClientScope(any(), any(), any())(any())).thenReturn(Future.successful(Right(())))

      ApplicationEnrichers.scopeRemovingApplicationEnricher(FakeHipEnvironments.primaryEnvironment, application, idmsConnector, testScopeName1, Some(testClientId1)).map {
        result =>
          verify(idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(testClientId1), eqTo(testScopeName1))(any())
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
