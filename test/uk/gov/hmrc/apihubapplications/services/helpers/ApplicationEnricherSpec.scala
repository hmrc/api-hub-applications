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
import uk.gov.hmrc.apihubapplications.models.application.{Application, Approved, Creator, Scope, Secondary}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.idms.{ClientResponse, ClientScope, IdmsException}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future

class ApplicationEnricherSpec   extends AsyncFreeSpec
  with Matchers
  with MockitoSugar {

  import ApplicationEnricherSpec._

  private implicit val hc: HeaderCarrier = HeaderCarrier()

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
        case _ => fail("Unexpected Left response")
      }
    }

    "must not modify an application if there are zero secondary credentials" in {
      ApplicationEnrichers.secondaryCredentialApplicationEnricher(testApplication, mock[IdmsConnector]).map {
        case Right(enricher) => enricher.enrich(testApplication) mustBe testApplication
        case _ => fail("Unexpected Left response")
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

      val expected = IdmsException("test-message")

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
        case _ => fail("Unexpected Left response")
      }
    }

    "must not modify an application if there are zero secondary credentials" in {
      ApplicationEnrichers.secondaryScopeApplicationEnricher(testApplication, mock[IdmsConnector]).map {
        case Right(enricher) => enricher.enrich(testApplication) mustBe testApplication
        case _ => fail("Unexpected Left response")
      }
    }

    "must return IdmsException if any call to IDMS fails" in {
      val application = testApplication
        .setSecondaryCredentials(Seq(testClientResponse1.asCredential()))

      val expected = IdmsException("test-message")

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.fetchClientScopes(ArgumentMatchers.eq(Secondary), ArgumentMatchers.eq(testClientResponse1.clientId))(any()))
        .thenReturn(Future.successful(Left(expected)))

      ApplicationEnrichers.secondaryScopeApplicationEnricher(application, idmsConnector).map {
        actual =>
          actual mustBe Left(expected)
      }
    }
  }

}

object ApplicationEnricherSpec {

  val testApplication: Application = Application(Some("test-id-1"), "test-description", Creator("test-email"), Seq.empty)
  val testClientResponse1: ClientResponse = ClientResponse("test-client-id-1", "test-secret-1")
  val testClientResponse2: ClientResponse = ClientResponse("test-client-id-2", "test-secret-2")
  val testClientScope1: ClientScope = ClientScope("test-client-scope-1")
  val testClientScope2: ClientScope = ClientScope("test-client-scope-2")

}
