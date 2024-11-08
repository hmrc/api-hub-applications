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

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{verify, verifyNoInteractions, verifyNoMoreInteractions, when}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.connectors.{IdmsConnector, IntegrationCatalogueConnector}
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, AccessRequestEndpoint, Approved}
import uk.gov.hmrc.apihubapplications.models.api.ApiDetailLenses.*
import uk.gov.hmrc.apihubapplications.models.api.{ApiDetail, Endpoint, EndpointMethod, Live}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.application.{Api, Application, Creator, Credential, EnvironmentName, Primary, Scope, Secondary, Endpoint as ApplicationEndpoint}
import uk.gov.hmrc.apihubapplications.models.exception.ApiNotFoundException
import uk.gov.hmrc.apihubapplications.models.idms.ClientScope
import uk.gov.hmrc.apihubapplications.services.AccessRequestsService
import uk.gov.hmrc.http.HeaderCarrier

import java.time.LocalDateTime
import scala.concurrent.Future

class ScopeFixerSpec extends AsyncFreeSpec with Matchers with MockitoSugar with EitherValues {

  import ScopeFixerSpec._

  "fix" - {
    "must process an application with no APIs or Scopes efficiently" in {
      val fixture = buildFixture()
      val accessRequests = Seq.empty

      fixture.scopeFixer.fix(baseApplication, accessRequests)(HeaderCarrier()).map {
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
      val accessRequests = Seq.empty

      stubIdmsFetchClientScopes(application, fixture)

      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verifyNoInteractions(fixture.integrationCatalogueConnector)
          verifyIdmsFetchClientScopes(application, fixture)

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

      val accessRequests = Seq(buildApprovedAccessRequest(application, api))

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))

      stubIdmsFetchClientScopes(application, fixture)

      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName1))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)

          result.value mustBe expected
      }
    }

    "must handle disparity when removing scopes (use each credential's scopes and not a master list)" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope1)

      val application = applicationWithCredentials
        .addPrimaryCredential(credential3)
        .addPrimaryScope(scope1)
        .addSecondaryScope(scope1)
        .addApi(buildApi(api))

      val accessRequests = Seq(buildApprovedAccessRequest(application, api))

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))

      stubIdmsFetchClientScopes(application, fixture)
      when(fixture.idmsConnector.fetchClientScopes(eqTo(Primary), eqTo(clientId3))(any))
        .thenReturn(Future.successful(Right(Seq(ClientScope(scopeName1), ClientScope(scopeName2)))))

      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Primary), eqTo(clientId3), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId3), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName1))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)

          result.value mustBe application
      }
    }

    "must handle disparity when adding scopes (use each credential's scopes and not a master list)" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope1)

      val application = applicationWithCredentials
        .addPrimaryCredential(credential3)
        .addSecondaryCredential(credential4)
        .addApi(buildApi(api))

      val expected = application
        .addPrimaryScope(scope1)
        .addSecondaryScope(scope1)

      val accessRequests = Seq(buildApprovedAccessRequest(application, api))

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))

      stubIdmsFetchClientScopes(application, fixture, noScopesFor = Set(clientId1, clientId2, clientId3, clientId4))

      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
          verify(fixture.idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId3), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId4), eqTo(scopeName1))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)

          result.value mustBe expected
      }
    }

    "must always grant all allowed scopes (self-healing)" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope1)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addPrimaryScope(scope1)
        .addPrimaryScope(scope2)
        .addSecondaryScope(scope1)
        .addSecondaryScope(scope2)
        .addApi(buildApi(api))

      val accessRequests = Seq(buildApprovedAccessRequest(application, api))

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))

      stubIdmsFetchClientScopes(application, fixture, noScopesFor = Set(clientId1, clientId2))

      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
          verify(fixture.idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scopeName2))(any)
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

      val accessRequests = Seq(buildApprovedAccessRequest(application, api))

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))

      stubIdmsFetchClientScopes(application, fixture)

      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName3))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scopeName2))(any)
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

      val accessRequests = Seq.empty

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))

      stubIdmsFetchClientScopes(application, fixture, noScopesFor = Set(clientId1))

      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
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

      val accessRequests = Seq.empty

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(eqTo(api1.id))(any)).thenReturn(Future.successful(Right(api1)))
      when(fixture.integrationCatalogueConnector.findById(eqTo(api2.id))(any)).thenReturn(Future.successful(Right(api2)))

      stubIdmsFetchClientScopes(application, fixture, noScopesFor = Set(clientId1))

      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api1.id))(any)
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api2.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName2))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)

          result.value mustBe expected
      }
    }

    "must handle a missing API, treating it like an API with no scopes" in {
      val apiDetail1 = baseApi(apiId1)
        .addEndpoint(endpointForScope1)

      val apiDetail2 = baseApi(apiId2)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addPrimaryScope(scope1)
        .addPrimaryScope(scope2)
        .addSecondaryScope(scope1)
        .addSecondaryScope(scope2)
        .addApi(buildApi(apiDetail1))
        .addApi(buildApi(apiDetail2))

      val expected = application
        .removePrimaryScope(scopeName2)
        .removeSecondaryScope(scopeName2)

      val accessRequests = Seq(buildApprovedAccessRequest(application, apiDetail1))

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(eqTo(apiId1))(any)).thenReturn(Future.successful(Right(apiDetail1)))
      when(fixture.integrationCatalogueConnector.findById(eqTo(apiId2))(any)).thenReturn(Future.successful(Left(ApiNotFoundException.forId(apiId2))))

      stubIdmsFetchClientScopes(application, fixture)

      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verifyIdmsFetchClientScopes(application, fixture)
          result.value mustBe expected
      }
    }

    "must not grant scopes for production credential with missing but unapproved scope" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope1)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addPrimaryScope(scope1)
        .addPrimaryScope(scope2)
        .addSecondaryScope(scope1)
        .addSecondaryScope(scope2)
        .addApi(buildApi(api))

      val accessRequests = Seq(buildApprovedAccessRequest(application, api.removeEndpoint(endpointForScope2.path)))

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))

      stubIdmsFetchClientScopes(application, fixture, noScopesFor = Set(clientId1, clientId2))

      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
          verify(fixture.idmsConnector).addClientScope(eqTo(Primary), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(Secondary), eqTo(clientId2), eqTo(scopeName2))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)

          result.value mustBe application
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

  private def stubIdmsFetchClientScopes(application: Application, fixture: Fixture, noScopesFor: Set[String] = Set.empty): Unit = {
    EnvironmentName.values.foreach(
      environmentName =>
        application
          .getCredentialsFor(environmentName)
          .filterNot(credential => noScopesFor.contains(credential.clientId))
          .foreach(
            credential =>
              when(fixture.idmsConnector.fetchClientScopes(eqTo(environmentName), eqTo(credential.clientId))(any))
                .thenReturn(Future.successful(Right(
                  application
                    .getScopesFor(environmentName)
                    .map(scope => ClientScope(scope.name))
                )))
          )
    )

    EnvironmentName.values.foreach(
      environmentName =>
        noScopesFor.foreach(
          clientId =>
            when(fixture.idmsConnector.fetchClientScopes(eqTo(environmentName), eqTo(clientId))(any))
              .thenReturn(Future.successful(Right(Seq.empty)))
        )
    )
  }

  private def verifyIdmsFetchClientScopes(application: Application, fixture: Fixture, ignoreClientIds: Set[String] = Set.empty) = {
    EnvironmentName.values.map(
      environmentName =>
        application
          .getCredentialsFor(environmentName)
          .filterNot(credential => ignoreClientIds.contains(credential.clientId))
          .foreach(
            credential =>
              verify(fixture.idmsConnector).fetchClientScopes(eqTo(environmentName), eqTo(credential.clientId))(any)
          )
    )
  }

}

object ScopeFixerSpec {

  private val clientId1: String = "test-client-id-1"
  private val clientId2: String = "test-client-id-2"
  private val clientId3: String = "test-client-id-3"
  private val clientId4: String = "test-client-id-4"
  private val credential1: Credential = Credential(clientId1, LocalDateTime.now(), None, None)
  private val credential2: Credential = Credential(clientId2, LocalDateTime.now(), None, None)
  private val credential3: Credential = Credential(clientId3, LocalDateTime.now(), None, None)
  private val credential4: Credential = Credential(clientId4, LocalDateTime.now(), None, None)
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

  private def baseApi(id: String): ApiDetail = ApiDetail(id, "test-publisher-ref", "test-title", "test-description", "test-platform", "test-version", Seq.empty, None, "test-oas", Live, None, None, None, Seq.empty)

  private val apiId1: String = "test-api-id-1"
  private val apiId2: String = "test-api-id-2"

  private val endpointForScope1: Endpoint = Endpoint("test-path-1", Seq(EndpointMethod("GET", None, None, Seq(scopeName1))))
  private val endpointForScope2: Endpoint = Endpoint("test-path-2", Seq(EndpointMethod("GET", None, None, Seq(scopeName2))))

  private def buildApi(apiDetail: ApiDetail): Api = {
    Api(
      id = apiDetail.id,
      title = apiDetail.title,
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

  private def buildApprovedAccessRequest(application: Application, apiDetail: ApiDetail): AccessRequest = {
    AccessRequest(
      id = Some("test-access-request-id"),
      applicationId = application.safeId,
      apiId = apiDetail.id,
      apiName = apiDetail.title,
      status = Approved,
      endpoints = application.apis
        .find(_.id == apiDetail.id)
        .map(
          api =>
            api.endpoints.map(
              endpoint =>
                AccessRequestEndpoint(
                  httpMethod = endpoint.httpMethod,
                  path = endpoint.path,
                  scopes = apiDetail
                    .endpoints
                    .filter(_.path == endpoint.path)
                    .flatMap(_.methods.filter(_.httpMethod == endpoint.httpMethod).flatMap(_.scopes))
                )
            )
        )
        .getOrElse(Seq.empty),
      supportingInformation = "test-supporting-information",
      requested = LocalDateTime.now(),
      requestedBy = "test-requested-by",
      decision = None,
      cancelled = None
    )
  }

}
