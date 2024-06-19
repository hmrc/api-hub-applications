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

package uk.gov.hmrc.apihubapplications.services.helpers

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.connectors.{IdmsConnector, IntegrationCatalogueConnector}
import uk.gov.hmrc.apihubapplications.models.application.{Application, Creator, Credential, Primary, Scope, Secondary}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses._
import uk.gov.hmrc.http.HeaderCarrier

import java.time.LocalDateTime
import scala.concurrent.Future

class ScopeChangerSpec extends AsyncFreeSpec with Matchers with MockitoSugar with ArgumentMatchersSugar with EitherValues {

  import ScopeChangerSpec._

  "minimiseScopes" - {
    "must process an application with no APIs or Scopes efficiently" in {
      val fixture = buildFixture()

      fixture.scopeChanger.minimiseScopes(baseApplication)(HeaderCarrier()).map {
        result =>
          verifyZeroInteractions(fixture.integrationCatalogueConnector)
          verifyZeroInteractions(fixture.idmsConnector)
          result.value mustBe baseApplication
      }
    }

    "must remove all scopes when the application has no remaining APIs" in {
      val application = applicationWithCredentials
        .addPrimaryScope(scope1)
        .addPrimaryScope(scope2)
        .addSecondaryScope(scope2)
        .addSecondaryScope(scope3)

      val fixture = buildFixture()

      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeChanger.minimiseScopes(application)(HeaderCarrier()).map {
        result =>
          verifyZeroInteractions(fixture.integrationCatalogueConnector)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName3))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)
          result.value mustBe applicationWithCredentials
      }
    }
  }

  private case class Fixture(
    integrationCatalogueConnector: IntegrationCatalogueConnector,
    idmsConnector: IdmsConnector,
    scopeChanger: ScopeChanger
  )

  private def buildFixture(): Fixture = {
    val integrationCatalogueConnector = mock[IntegrationCatalogueConnector]
    val idmsConnector = mock[IdmsConnector]
    val scopeChanger = new ScopeChanger(integrationCatalogueConnector, idmsConnector)
    Fixture(integrationCatalogueConnector, idmsConnector, scopeChanger)
  }

}

object ScopeChangerSpec {

  private val clientId1: String = "test-client-id-1"
  private val clientId2: String = "test-client-id-2"
  private val credential1: Credential = Credential(clientId1, LocalDateTime.now(), None, None)
  private val credential2: Credential = Credential(clientId2, LocalDateTime.now(), None, None)
  private val scopeName1: String = "test-scope-name-1"
  private val scopeName2: String = "test-scope-name-2"
  private val scopeName3: String = "test-scope-name-3"
  private val scope1: Scope = Scope(scopeName1)
  private val scope2: Scope = Scope(scopeName2)
  private val scope3: Scope = Scope(scopeName3)

  private val baseApplication: Application = Application(Some("test-id"), "test-name", Creator("test-email"), Seq.empty)
  private val applicationWithCredentials: Application = baseApplication
    .addPrimaryCredential(credential1)
    .addSecondaryCredential(credential2)

}
