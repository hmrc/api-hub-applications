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
import uk.gov.hmrc.apihubapplications.config.HipEnvironment
import uk.gov.hmrc.apihubapplications.connectors.{IdmsConnector, IntegrationCatalogueConnector}
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, AccessRequestEndpoint, Approved}
import uk.gov.hmrc.apihubapplications.models.api.ApiDetailLenses.*
import uk.gov.hmrc.apihubapplications.models.api.{ApiDetail, Endpoint, EndpointMethod, Live}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.application.{Api, Application, Creator, Credential, Endpoint as ApplicationEndpoint}
import uk.gov.hmrc.apihubapplications.models.exception.ApiNotFoundException
import uk.gov.hmrc.apihubapplications.models.idms.ClientScope
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments
import uk.gov.hmrc.http.HeaderCarrier

import java.time.LocalDateTime
import scala.concurrent.Future

class ScopeFixerSpec extends AsyncFreeSpec with Matchers with MockitoSugar with EitherValues {

  import ScopeFixerSpec.*

  "fix" - {
    "must process an application with no APIs or Scopes efficiently" in {
      val fixture = buildFixture()
      val accessRequests = Seq.empty

      fixture.scopeFixer.fix(baseApplication, accessRequests)(HeaderCarrier()).map {
        result =>
          verifyNoInteractions(fixture.integrationCatalogueConnector)
          verifyNoInteractions(fixture.idmsConnector)

          result.value mustBe ()
      }
    }

    "must remove all scopes when the application has no remaining APIs" in {
      val application = applicationWithCredentials
      val scopeMap = Map(
        FakeHipEnvironments.primaryEnvironment -> Seq(scopeName1, scopeName2),
        FakeHipEnvironments.secondaryEnvironment -> Seq(scopeName2, scopeName3)
      )

      val fixture = buildFixture()
      val accessRequests = Seq.empty

      stubIdmsFetchClientScopes(application, fixture, scopeMap)

      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verifyNoInteractions(fixture.integrationCatalogueConnector)
          verifyIdmsFetchClientScopes(application, fixture)

          verify(fixture.idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId1), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName3))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)

          result.value mustBe ()
      }
    }

    "must remove scopes associated with a removed endpoint and retain scopes still used" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope1)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addApi(buildApi(api.removeEndpoint(endpointForScope2.path)))

      val scopeMap = Map(
        FakeHipEnvironments.primaryEnvironment -> Seq(scopeName1, scopeName2),
        FakeHipEnvironments.secondaryEnvironment -> Seq(scopeName1, scopeName2)
      )

      val accessRequests = Seq(buildApprovedAccessRequest(application, api))

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))

      stubIdmsFetchClientScopes(application, fixture, scopeMap)

      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId1), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName1))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)

          result.value mustBe ()
      }
    }

    "must remove scopes associated with a removed endpoint and retain scopes still used for a specific credential and environment" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope1)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addApi(buildApi(api))

      val newCredential = Credential(
        clientId = clientId4,
        created = LocalDateTime.now(),
        clientSecret = None,
        secretFragment = None,
        environmentId = FakeHipEnvironments.primaryEnvironment.id
      )

      val clientScopes = Seq(ClientScope(scopeName1), ClientScope(scopeName2), ClientScope(scopeName3))

      val accessRequests = Seq(buildApprovedAccessRequest(application, api))

      val fixture = buildFixture()

      when(fixture.idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(newCredential.clientId))(any))
        .thenReturn(Future.successful(Right(clientScopes)))
      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))

      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests, newCredential)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verify(fixture.idmsConnector).fetchClientScopes(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId4))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId4), eqTo(scopeName3))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId4), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId4), eqTo(scopeName2))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)

          result.value mustBe ()
      }
    }

    "must handle disparity when removing scopes (use each credential's scopes and not a master list)" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope1)

      val application = applicationWithCredentials
        .addCredential(FakeHipEnvironments.primaryEnvironment, credential3)
        .addApi(buildApi(api))

      val scopeMap = Map(
        FakeHipEnvironments.primaryEnvironment -> Seq(scopeName1),
        FakeHipEnvironments.secondaryEnvironment -> Seq(scopeName1)
      )

      val accessRequests = Seq(buildApprovedAccessRequest(application, api))

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))

      stubIdmsFetchClientScopes(application, fixture, scopeMap)

      when(fixture.idmsConnector.fetchClientScopes(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId3))(any))
        .thenReturn(Future.successful(Right(Seq(ClientScope(scopeName1), ClientScope(scopeName2)))))

      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId3), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId3), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName1))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)

          result.value mustBe ()
      }
    }

    "must handle disparity when adding scopes (use each credential's scopes and not a master list)" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope1)

      val application = applicationWithCredentials
        .addCredential(FakeHipEnvironments.primaryEnvironment, credential3)
        .addCredential(FakeHipEnvironments.secondaryEnvironment, credential4)
        .addApi(buildApi(api))

      val accessRequests = Seq(buildApprovedAccessRequest(application, api))

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))

      stubIdmsFetchClientScopes(application, fixture, Map.empty, noScopesFor = Set(clientId1, clientId2, clientId3, clientId4))

      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId3), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId4), eqTo(scopeName1))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)

          result.value mustBe ()
      }
    }

    "must always grant all allowed scopes (self-healing)" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope1)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addApi(buildApi(api))

      val scopeMap = Map(
        FakeHipEnvironments.primaryEnvironment -> Seq(scopeName1, scopeName2),
        FakeHipEnvironments.secondaryEnvironment -> Seq(scopeName1, scopeName2)
      )

      val accessRequests = Seq(buildApprovedAccessRequest(application, api))

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))

      stubIdmsFetchClientScopes(application, fixture, scopeMap, noScopesFor = Set(clientId1, clientId2))

      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId1), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName2))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)

          result.value mustBe ()
      }
    }

    "must not remove scopes that are still required" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addApi(buildApi(api))

      val scopeMap = Map(
        FakeHipEnvironments.primaryEnvironment -> Seq(scopeName1, scopeName2),
        FakeHipEnvironments.secondaryEnvironment -> Seq(scopeName2, scopeName3)
      )

      val accessRequests = Seq(buildApprovedAccessRequest(application, api))

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))

      stubIdmsFetchClientScopes(application, fixture, scopeMap)

      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).deleteClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName3))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId1), eqTo(scopeName2))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName2))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)

          result.value mustBe ()
      }
    }

    "must add secondary scopes when adding an endpoint" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope1)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addApi(buildApi(api))

      val scopeMap = Map(
        FakeHipEnvironments.primaryEnvironment -> Seq(scopeName1),
        FakeHipEnvironments.secondaryEnvironment -> Seq(scopeName1)
      )

      val accessRequests = Seq.empty

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))

      stubIdmsFetchClientScopes(application, fixture, scopeMap, noScopesFor = Set(clientId1))

      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName2))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)

          result.value mustBe ()
      }
    }

    "must add secondary scopes when adding an API" in {
      val api1 = baseApi(apiId1)
        .addEndpoint(endpointForScope1)

      val api2 = baseApi(apiId2)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addApi(buildApi(api1))
        .addApi(buildApi(api2))

      val scopeMap = Map(
        FakeHipEnvironments.primaryEnvironment -> Seq(scopeName1),
        FakeHipEnvironments.secondaryEnvironment -> Seq(scopeName1)
      )

      val accessRequests = Seq.empty

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(eqTo(api1.id))(any)).thenReturn(Future.successful(Right(api1)))
      when(fixture.integrationCatalogueConnector.findById(eqTo(api2.id))(any)).thenReturn(Future.successful(Right(api2)))

      stubIdmsFetchClientScopes(application, fixture, scopeMap, noScopesFor = Set(clientId1))

      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api1.id))(any)
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api2.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName2))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)

          result.value mustBe ()
      }
    }

    "must handle a missing API, treating it like an API with no scopes" in {
      val apiDetail1 = baseApi(apiId1)
        .addEndpoint(endpointForScope1)

      val apiDetail2 = baseApi(apiId2)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addApi(buildApi(apiDetail1))
        .addApi(buildApi(apiDetail2))

      val scopeMap = Map(
        FakeHipEnvironments.primaryEnvironment -> Seq(scopeName1, scopeName2),
        FakeHipEnvironments.secondaryEnvironment -> Seq(scopeName1, scopeName2)
      )

      val accessRequests = Seq(buildApprovedAccessRequest(application, apiDetail1))

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(eqTo(apiId1))(any)).thenReturn(Future.successful(Right(apiDetail1)))
      when(fixture.integrationCatalogueConnector.findById(eqTo(apiId2))(any)).thenReturn(Future.successful(Left(ApiNotFoundException.forId(apiId2))))

      stubIdmsFetchClientScopes(application, fixture, scopeMap)

      when(fixture.idmsConnector.deleteClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))
      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verifyIdmsFetchClientScopes(application, fixture)
          result.value mustBe ()
      }
    }

    "must not grant scopes for production credential with missing but unapproved scope" in {
      val api = baseApi(apiId1)
        .addEndpoint(endpointForScope1)
        .addEndpoint(endpointForScope2)

      val application = applicationWithCredentials
        .addApi(buildApi(api))

      val scopeMap = Map(
        FakeHipEnvironments.primaryEnvironment -> Seq(scopeName1, scopeName2),
        FakeHipEnvironments.secondaryEnvironment -> Seq(scopeName1, scopeName2)
      )

      val accessRequests = Seq(buildApprovedAccessRequest(application, api.removeEndpoint(endpointForScope2.path)))

      val fixture = buildFixture()

      when(fixture.integrationCatalogueConnector.findById(any)(any)).thenReturn(Future.successful(Right(api)))

      stubIdmsFetchClientScopes(application, fixture, scopeMap, noScopesFor = Set(clientId1, clientId2))

      when(fixture.idmsConnector.addClientScope(any, any, any)(any)).thenReturn(Future.successful(Right(())))

      fixture.scopeFixer.fix(application, accessRequests)(HeaderCarrier()).map {
        result =>
          verify(fixture.integrationCatalogueConnector).findById(eqTo(api.id))(any)
          verifyNoMoreInteractions(fixture.integrationCatalogueConnector)

          verifyIdmsFetchClientScopes(application, fixture)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(clientId1), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName1))(any)
          verify(fixture.idmsConnector).addClientScope(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(clientId2), eqTo(scopeName2))(any)
          verifyNoMoreInteractions(fixture.idmsConnector)

          result.value mustBe ()
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
    val scopeFixer = new ScopeFixer(integrationCatalogueConnector, idmsConnector, FakeHipEnvironments)
    Fixture(integrationCatalogueConnector, idmsConnector, scopeFixer)
  }

  private def stubIdmsFetchClientScopes(application: Application, fixture: Fixture, scopeMap: Map[HipEnvironment, Seq[String]], noScopesFor: Set[String] = Set.empty): Unit = {
    FakeHipEnvironments.environments.foreach(
      hipEnvironment =>
        application
          .getCredentials(hipEnvironment)
          .filterNot(credential => noScopesFor.contains(credential.clientId))
          .foreach(
            credential =>
              when(fixture.idmsConnector.fetchClientScopes(eqTo(hipEnvironment), eqTo(credential.clientId))(any))
                .thenReturn(Future.successful(Right(
                  scopeMap
                    .getOrElse(hipEnvironment, Seq.empty)
                    .map(scope => ClientScope(scope))
                )))
          )
    )

    FakeHipEnvironments.environments.foreach(
      hipEnvironment =>
        noScopesFor.foreach(
          clientId =>
            when(fixture.idmsConnector.fetchClientScopes(eqTo(hipEnvironment), eqTo(clientId))(any))
              .thenReturn(Future.successful(Right(Seq.empty)))
        )
    )
  }

  private def verifyIdmsFetchClientScopes(application: Application, fixture: Fixture, ignoreClientIds: Set[String] = Set.empty) = {
    FakeHipEnvironments.environments.map(
      hipEnvironment =>
        application
          .getCredentials(hipEnvironment)
          .filterNot(credential => ignoreClientIds.contains(credential.clientId))
          .foreach(
            credential =>
              verify(fixture.idmsConnector).fetchClientScopes(eqTo(hipEnvironment), eqTo(credential.clientId))(any)
          )
    )
  }

}

object ScopeFixerSpec {

  private val clientId1: String = "test-client-id-1"
  private val clientId2: String = "test-client-id-2"
  private val clientId3: String = "test-client-id-3"
  private val clientId4: String = "test-client-id-4"
  private val credential1: Credential = Credential(clientId1, LocalDateTime.now(), None, None, FakeHipEnvironments.primaryEnvironment.id)
  private val credential2: Credential = Credential(clientId2, LocalDateTime.now(), None, None, FakeHipEnvironments.secondaryEnvironment.id)
  private val credential3: Credential = Credential(clientId3, LocalDateTime.now(), None, None, FakeHipEnvironments.primaryEnvironment.id)
  private val credential4: Credential = Credential(clientId4, LocalDateTime.now(), None, None, FakeHipEnvironments.secondaryEnvironment.id)
  private val scopeName1: String = "test-scope-name-1"
  private val scopeName2: String = "test-scope-name-2"
  private val scopeName3: String = "test-scope-name-3"

  private val baseApplication: Application = Application(Some("test-id"), "test-name", Creator("test-email"), Seq.empty)
  private val applicationWithCredentials: Application = baseApplication
    .addCredential(FakeHipEnvironments.primaryEnvironment, credential1)
    .addCredential(FakeHipEnvironments.secondaryEnvironment, credential2)

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
      cancelled = None,
      environmentId = "test"
    )
  }

}
