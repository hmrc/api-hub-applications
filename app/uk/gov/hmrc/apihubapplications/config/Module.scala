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

package uk.gov.hmrc.apihubapplications.config

import play.api.inject.{Binding, bind => bindz}
import play.api.{Configuration, Environment}
import uk.gov.hmrc.apihubapplications.connectors.{APIMConnector, APIMConnectorImpl, EmailConnector, EmailConnectorImpl, IdmsConnector, IdmsConnectorImpl, IntegrationCatalogueConnector, IntegrationCatalogueConnectorImpl}
import uk.gov.hmrc.apihubapplications.controllers.actions.{AuthenticatedIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.services.{ApplicationsApiService, ApplicationsApiServiceImpl, ApplicationsCredentialsService, ApplicationsCredentialsServiceImpl, ApplicationsLifecycleService, ApplicationsLifecycleServiceImpl, ApplicationsSearchService, ApplicationsSearchServiceImpl}
import uk.gov.hmrc.apihubapplications.tasks.DatabaseStatisticMetricOrchestratorTask
import uk.gov.hmrc.mongo.metrix.MetricOrchestrator

import java.time.Clock
import scala.collection.immutable.Seq

class Module extends play.api.inject.Module {

  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    val bindings = Seq(
      bindz(classOf[AppConfig]).toSelf.eagerly(),
      bindz(classOf[Clock]).toInstance(Clock.systemUTC()),
      bindz(classOf[IdentifierAction]).to(classOf[AuthenticatedIdentifierAction]).eagerly(),
      bindz(classOf[IdmsConnector]).to(classOf[IdmsConnectorImpl]).eagerly(),
      bindz(classOf[MetricOrchestrator]).toProvider(classOf[DatabaseStatisticsMetricOrchestratorProvider]).eagerly(),
      bindz(classOf[DatabaseStatisticMetricOrchestratorTask]).toSelf.eagerly(),
      bindz(classOf[EmailConnector]).to(classOf[EmailConnectorImpl]).eagerly(),
      bindz(classOf[APIMConnector]).to(classOf[APIMConnectorImpl]).eagerly(),
      bindz(classOf[IntegrationCatalogueConnector]).to(classOf[IntegrationCatalogueConnectorImpl]).eagerly(),
      bindz(classOf[ApplicationsApiService]).to(classOf[ApplicationsApiServiceImpl]).eagerly(),
      bindz(classOf[ApplicationsCredentialsService]).to(classOf[ApplicationsCredentialsServiceImpl]).eagerly(),
      bindz(classOf[ApplicationsLifecycleService]).to(classOf[ApplicationsLifecycleServiceImpl]).eagerly(),
      bindz(classOf[ApplicationsSearchService]).to(classOf[ApplicationsSearchServiceImpl]).eagerly()
    )

    val authTokenInitialiserBindings: Seq[Binding[_]] = if (configuration.get[Boolean]("create-internal-auth-token-on-start")) {
      Seq(bindz(classOf[InternalAuthTokenInitialiser]).to(classOf[InternalAuthTokenInitialiserImpl]).eagerly())
    } else {
      Seq(bindz(classOf[InternalAuthTokenInitialiser]).to(classOf[NoOpInternalAuthTokenInitialiser]).eagerly())
    }

    bindings ++ authTokenInitialiserBindings
  }

}
