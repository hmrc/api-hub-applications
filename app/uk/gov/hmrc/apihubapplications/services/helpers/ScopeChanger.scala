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

import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.apihubapplications.connectors.{IdmsConnector, IntegrationCatalogueConnector}
import uk.gov.hmrc.apihubapplications.models.api.ApiDetail
import uk.gov.hmrc.apihubapplications.models.api.ApiDetailLenses._
import uk.gov.hmrc.apihubapplications.models.application.{Application, EnvironmentName, Primary, Secondary}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses._
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, IdmsException}
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.useFirstApplicationsException
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ScopeChanger @Inject()(
  integrationCatalogueConnector: IntegrationCatalogueConnector,
  idmsConnector: IdmsConnector
)(implicit ec: ExecutionContext) {

  def minimiseScopes(application: Application)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    determineRequiredScopes(application).flatMap {
      case Right(requiredScopes) =>
        minimiseScopesInEnvironment(application, requiredScopes, Primary).flatMap {
          case Right(_) => minimiseScopesInEnvironment(application, requiredScopes, Primary)
          case Left(e) => Future.successful(Left(e))
        }
      case Left(e) => Future.successful(Left(e))
    }
  }

  private def determineRequiredScopes(application: Application)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Set[String]]] = {
    Future
      .sequence(
        application.apis.map(
          api =>
            integrationCatalogueConnector.findById(api.id)
        )
      )
      .map(useFirstApplicationsException)
      .map(_.map(_.toSet.flatMap((x: ApiDetail) => x.getRequiredScopeNames)))
  }

  private def determineScopesToRemove(application: Application, requiredScopes: Set[String], environmentName: EnvironmentName): Set[String] = {
    val currentScopes = environmentName match {
      case Primary => application.getPrimaryScopes.toSet
      case Secondary => application.getSecondaryScopes.toSet
    }

    currentScopes.map(_.name) -- requiredScopes
  }

  private def minimiseScopesInEnvironment(
    application: Application,
    requiredScopes: Set[String],
    environmentName: EnvironmentName
  )(implicit hc: HeaderCarrier): Future[Either[IdmsException, Unit]] = {

    ApplicationEnrichers.process(
      application,
      determineScopesToRemove(application, requiredScopes, environmentName)
        .toSeq
        .map(
          scopeName =>
            ApplicationEnrichers.scopeRemovingApplicationEnricher(environmentName, application, idmsConnector, scopeName)
        )
    ).map(_.map(_ => ()))

  }

}
