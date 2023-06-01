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

import org.mockito.ArgumentMatchers.any
import org.mockito.{ArgumentMatchers, MockitoSugar}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.{Application, Approved, Creator, Credential, Issues, Pending, Primary, Scope, Secondary}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.exception.{CallError, IdmsException}
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, ClientScope}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future

class ApplicationEnricherSpec   extends AsyncFreeSpec
  with Matchers
  with MockitoSugar {

  import ApplicationEnricherSpec._

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  "process" - {
    "must correctly combine successful enrichers" in {
      val scope1 = Scope("test-scope-1", Approved)
      val scope2 = Scope("test-scope-2", Approved)

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

  "secondaryCredentialApplicationEnricher" - {
    "must enrich an application with secondary credentials" in {
      val application = testApplication
        .setSecondaryCredentials(
          Seq(
            testClientResponse1.asCredential(),
            testClientResponse2.asCredential()
          )
        )

      val expected = application
        .setSecondaryCredentials(
          Seq(
            testClientResponse1.asCredentialWithSecret(),
            testClientResponse2.asCredentialWithSecret()
          )
        )

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Right(testClientResponse1)))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientResponse2.clientId))(any()))
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

    "must return IdmsException if any call to IDMS fails" in {
      val application = testApplication
        .setSecondaryCredentials(
          Seq(
            testClientResponse1.asCredential(),
            testClientResponse2.asCredential()
          )
        )

      val expected = IdmsException("test-message", CallError)

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Right(testClientResponse1)))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientResponse2.clientId))(any()))
        .thenReturn(Future.successful(Left(expected)))

      ApplicationEnrichers.secondaryCredentialApplicationEnricher(application, idmsConnector).map {
        actual =>
          actual mustBe Left(expected)
      }
    }

    "must add an application issue if a credential cannot be found" in {
      val application = testApplication
        .setSecondaryCredentials(
          Seq(
            testClientResponse1.asCredential(),
            testClientResponse2.asCredential()
          )
        )

      val expected = application
        .setSecondaryCredentials(
          Seq(
            testClientResponse1.asCredentialWithSecret(),
            testClientResponse2.asCredential()
          )
        )
        .addIssue(Issues.secondaryCredentialNotFound(IdmsException.clientNotFound(testClientId2)))

      val idmsConnector = mock[IdmsConnector]

      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Right(testClientResponse1)))
      when(idmsConnector.fetchClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientResponse2.clientId))(any()))
        .thenReturn(Future.successful(Left(IdmsException.clientNotFound(testClientId2))))

      ApplicationEnrichers.secondaryCredentialApplicationEnricher(application, idmsConnector).map {
        case Right(enricher) => enricher.enrich(application) mustBe expected
        case Left(e) => fail("Unexpected Left response", e)
      }
    }
  }

  "secondaryScopeApplicationEnricher" - {
    "must enrich an application with secondary scopes" in {
      val application = testApplication
        .setSecondaryCredentials(Seq(testClientResponse1.asCredential()))

      val expected = application
        .setSecondaryScopes(
          Seq(
            Scope(testClientScope1.clientScopeId, Approved),
            Scope(testClientScope2.clientScopeId, Approved)
          )
        )

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientResponse1.clientId))(any()))
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

    "must return IdmsException if any call to IDMS fails" in {
      val application = testApplication
        .setSecondaryCredentials(Seq(testClientResponse1.asCredential()))

      val expected = IdmsException("test-message", CallError)

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Left(expected)))

      ApplicationEnrichers.secondaryScopeApplicationEnricher(application, idmsConnector).map {
        actual =>
          actual mustBe Left(expected)
      }
    }
  }

  "primaryScopeApplicationEnricher" - {
    "must enrich an application with secondary scopes and still include pending scopes" in {
      val pendingScope1 = Scope("test-pending-scope-1", Pending)
      val pendingScope2 = Scope("test-pending-scope-2", Pending)
      val approvedScope = Scope("test-approved-scope", Approved)

      val application = testApplication
        .setPrimaryCredentials(Seq(testClientResponse1.asCredential()))
        .setPrimaryScopes(Seq(pendingScope1, pendingScope2, approvedScope))

      val expected = application
        .setPrimaryScopes(
          Seq(
            Scope(testClientScope1.clientScopeId, Approved),
            Scope(testClientScope2.clientScopeId, Approved),
            pendingScope1,
            pendingScope2
          )
        )

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Right(Seq(testClientScope1, testClientScope2))))

      ApplicationEnrichers.primaryScopeApplicationEnricher(application, idmsConnector).map {
        case Right(enricher) => enricher.enrich(application) mustBe expected
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must not modify an application if there are zero primary credentials" in {
      ApplicationEnrichers.primaryScopeApplicationEnricher(testApplication, mock[IdmsConnector]).map {
        case Right(enricher) => enricher.enrich(testApplication) mustBe testApplication
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must return IdmsException if any call to IDMS fails" in {
      val application = testApplication
        .setPrimaryCredentials(Seq(testClientResponse1.asCredential()))

      val expected = IdmsException("test-message", CallError)

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Left(expected)))

      ApplicationEnrichers.primaryScopeApplicationEnricher(application, idmsConnector).map {
        actual =>
          actual mustBe Left(expected)
      }
    }
  }

  "credentialCreatingApplicationEnricher" - {
    "must create a credential in the primary environment and enrich the application with it" in {
      val expected = testApplication.setPrimaryCredentials(Seq(Credential(testClientResponse1.clientId, None, None)))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.createClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(Client(testApplication)))(any()))
        .thenReturn(Future.successful(Right(testClientResponse1)))

      ApplicationEnrichers.credentialCreatingApplicationEnricher(Primary, testApplication, idmsConnector).map {
        case Right(enricher) => enricher.enrich(testApplication) mustBe expected
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must create a credential in the secondary environment and enrich the application with it" in {
      val expected = testApplication.setSecondaryCredentials(Seq(testClientResponse1.asCredential()))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.createClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(Client(testApplication)))(any()))
        .thenReturn(Future.successful(Right(testClientResponse1)))

      ApplicationEnrichers.credentialCreatingApplicationEnricher(Secondary, testApplication, idmsConnector).map {
        case Right(enricher) => enricher.enrich(testApplication) mustBe expected
        case Left(e) => fail("Unexpected Left response", e)
      }
    }

    "must return IdmsException if any call to IDMS fails" in {
      val expected = IdmsException("test-message", CallError)

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.createClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(Client(testApplication)))(any()))
        .thenReturn(Future.successful(Left(expected)))

      ApplicationEnrichers.credentialCreatingApplicationEnricher(Primary, testApplication, idmsConnector).map {
        actual =>
          actual mustBe Left(expected)
      }
    }
  }

  "credentialDeletingApplicationEnricher" - {
    "must delete a credential from the primary environment and remove it from the application" in {
      val application = testApplication.addPrimaryCredential(Credential(testClientId1, None, None))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.deleteClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientId1))(any()))
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
      val application = testApplication.addSecondaryCredential(Credential(testClientId1, None, None))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.deleteClient(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientId1))(any()))
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
      val application = testApplication.addPrimaryCredential(Credential(testClientId1, None, None))

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.deleteClient(ArgumentMatchers.eq(Primary), ArgumentMatchers.eq(testClientId1))(any()))
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

}

object ApplicationEnricherSpec {

  val testApplication: Application = Application(Some("test-id-1"), "test-description", Creator("test-email"), Seq.empty)
  val testClientId1: String = "test-client-id-1"
  val testClientId2: String = "test-client-id-2"
  val testClientResponse1: ClientResponse = ClientResponse(testClientId1, "test-secret-1")
  val testClientResponse2: ClientResponse = ClientResponse(testClientId2, "test-secret-2")
  val testClientScope1: ClientScope = ClientScope("test-client-scope-1")
  val testClientScope2: ClientScope = ClientScope("test-client-scope-2")
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
