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

import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.{verify, verifyNoInteractions, verifyNoMoreInteractions, when}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.connectors.{IdmsConnector, IntegrationCatalogueConnector}
import uk.gov.hmrc.apihubapplications.models.api.ApiDetailLenses._
import uk.gov.hmrc.apihubapplications.models.api.{ApiDetail, Endpoint, EndpointMethod, Live}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses._
import uk.gov.hmrc.apihubapplications.models.application.{Api, Application, Creator, Credential, Primary, Scope, Secondary, Endpoint => ApplicationEndpoint}
import uk.gov.hmrc.http.HeaderCarrier

import java.time.LocalDateTime
import scala.concurrent.Future

class ScopeFixerSpec extends AsyncFreeSpec with Matchers with MockitoSugar with EitherValues {

  import ScopeFixerSpec._

  "fix" - {
    "must process an application with no APIs or Scopes efficiently" in {
      val fixture = buildFixture()

      fixture.scopeFixer.fix(baseApplication)(HeaderCarrier()).map {
        result =>
          verifyNoInteractions(fixture.integrationCatalogueConnector)
          verifyNoInteractions(fixture.idmsConnector)
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

      fixture.scopeFixer.fix(application)(HeaderCarrier()).map {
        result =>
          verifyNoInteractions(fixture.integrationCatalogueConnector)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName3))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)
          result.value mustBe applicationWithCredentials
      }
    }

    "must remove scopes associated with a removed endpoint and retain scopes still used" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope1)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addPrimaryScope(scope1)
        .addPrimaryScope(scope2)
        .addSecondaryScope(scope1)
        .addSecondaryScope(scope2)
        .addApi(buildApi(api.removeEndpoint(endpointForScope2.path)))

      val expected = application
        .removePrimaryScope(scopeName2)
        .removeSecondaryScope(scopeName2)

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))
      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName1))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)
          result.value mustBe expected
      }
    }

    "must always add all secondary scopes (self-healing)" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope1)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addPrimaryScope(scope1)
        .addSecondaryScope(scope1)
        .addSecondaryScope(scope2)
        .addApi(buildApi(api))

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName2))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)
          result.value mustBe application
      }
    }

    "must not remove scopes that are still required" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addPrimaryScope(scope1)
        .addPrimaryScope(scope2)
        .addSecondaryScope(scope2)
        .addSecondaryScope(scope3)
        .addApi(buildApi(api))

      val expected = application
        .removePrimaryScope(scopeName1)
        .removeSecondaryScope(scopeName3)

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))
      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verify(fixture.idmsConnector).deleteClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName3))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName2))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)
          result.value mustBe expected
      }
    }

    "must add secondary scopes when adding an endpoint" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope1)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addPrimaryScope(scope1)
        .addSecondaryScope(scope1)
        .addApi(buildApi(api))

      val expected = application
        .addSecondaryScope(scope2)

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName2))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)
          result.value mustBe expected
      }
    }

    "must add secondary scopes when adding an API" in {
      val api1 = baseApi(apiId1)
        .addEndpoint(endpointForScope1)

      val api2 = baseApi(apiId2)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addPrimaryScope(scope1)
        .addSecondaryScope(scope1)
        .addApi(buildApi(api1))
        .addApi(buildApi(api2))

      val expected = application
        .addSecondaryScope(scope2)

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(eqTo(api1.id))(any)).thenReturn(Future.successful(Right(api1)))
      when(fixture.integrationCatalogueConnector.findById(eqTo(api2.id))(any)).thenReturn(Future.successful(Right(api2)))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api1.id))(any)
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api2.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName2))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)
          result.value mustBe expected
      }
    }
  }

  private case class Fixture(
    integrationCatalogueConnector: IntegrationCatalogueConnector,
    idmsConnector: IdmsConnector,
    scopeFixer: ScopeFixer
  )

  private def buildFixture(): Fixture = {
    val integrationCatalogueConnector = mock[IntegrationCatalogueConnector]
    val idmsConnector = mock[IdmsConnector]
    val scopeFixer = new ScopeFixer(integrationCatalogueConnector, idmsConnector)
    Fixture(integrationCatalogueConnector, idmsConnector, scopeFixer)
  }

}

object ScopeFixerSpec {

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

  private def baseApi(id: String): ApiDetail = ApiDetail(id, "test-publisher-ref", "test-title", "test-description", "test-version", Seq.empty, None, "test-oas", Live, None, None, None, Seq.empty)

  private val apiId1: String = "test-api-id-1"
  private val apiId2: String = "test-api-id-2"

  private val endpointForScope1: Endpoint = Endpoint("test-path-1", Seq(EndpointMethod("GET", None, None, Seq(scopeName1))))
  private val endpointForScope2: Endpoint = Endpoint("test-path-2", Seq(EndpointMethod("GET", None, None, Seq(scopeName2))))

  private def buildApi(apiDetail: ApiDetail): Api = {
    Api(
      id = apiDetail.id,
      endpoints = apiDetail.endpoints.flatMap(
        endpoint =>
          endpoint.methods.map(
            method =>
              ApplicationEndpoint(
                httpMethod = method.httpMethod,
                path = endpoint.path
              )
          )
      )
    )
  }

}
