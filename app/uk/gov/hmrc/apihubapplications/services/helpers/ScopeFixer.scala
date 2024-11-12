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

import cats.data.EitherT
import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.apihubapplications.connectors.{IdmsConnector, IntegrationCatalogueConnector}
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, Approved}
import uk.gov.hmrc.apihubapplications.models.api.ApiDetail
import uk.gov.hmrc.apihubapplications.models.api.ApiDetailLenses.*
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.exception.{ApiNotFoundException, ApplicationsException}
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.{useFirstApplicationsException, useFirstException}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ScopeFixer @Inject()(
  integrationCatalogueConnector: IntegrationCatalogueConnector,
  idmsConnector: IdmsConnector
)(implicit ec: ExecutionContext) {

  // Manipulate the model first, adding and removing APIs and endpoints, THEN call this method to fix scopes
  // The application does not have to be enriched
  def fix(application: Application, accessRequests: Seq[AccessRequest])(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    (for {
      requiredScopes <- EitherT(requiredScopes(application))
      credentials <- EitherT(allCredentials(application))
      application <- EitherT(minimiseScopesInEnvironment(Primary, application, credentials, allowedProductionScopes(requiredScopes, accessRequests)))
      application <- EitherT(minimiseScopesInEnvironment(Secondary, application, credentials, requiredScopes))
      application <- EitherT(addScopesToEnvironment(Primary, application, allowedProductionScopes(requiredScopes, accessRequests)))
      application <- EitherT(addScopesToEnvironment(Secondary, application, requiredScopes))
    } yield application).value
  }

  private def allCredentials(application: Application)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Seq[CredentialScopes]]] = {
    Future.sequence(
      Seq(Primary, Secondary).flatMap(
        environmentName =>
          application.getCredentialsFor(environmentName).map(
            credential =>
              idmsConnector
                .fetchClientScopes(environmentName, credential.clientId)
                .map(_.map(
                  scopes =>
                    CredentialScopes(environmentName, credential.clientId, credential.created, scopes.map(_.clientScopeId))
                ))
          )
      )
    ).map(useFirstException)
  }

  private def requiredScopes(application: Application)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Set[String]]] = {
    Future
      .sequence(
        application.apis.map(
          api =>
            integrationCatalogueConnector.findById(api.id)
        )
      )
      .map(
        maybeApis =>
          maybeApis.filter {
            case Left(_: ApiNotFoundException) => false
            case _ => true
          }
      )
      .map(useFirstApplicationsException)
      .map(_.map(_.toSet.flatMap((apiDetail: ApiDetail) => apiDetail.getRequiredScopeNames(application))))
  }

  private def allowedProductionScopes(requiredScopes: Set[String], accessRequests: Seq[AccessRequest]): Set[String] = {
    accessRequests
      .filter(_.status == Approved)
      .flatMap(_.endpoints.flatMap(_.scopes))
      .toSet
      .intersect(requiredScopes)
  }

  private def scopesToRemove(credential: CredentialScopes, requiredScopes: Set[String]): Set[String] = {
    credential.scopes.toSet -- requiredScopes
  }

  private def minimiseScopesInEnvironment(
    environmentName: EnvironmentName,
    application: Application,
    credentials: Seq[CredentialScopes],
    allowedScopes: Set[String]
  )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    ApplicationEnrichers.process(
      application,
      credentials
        .filter(_.environmentName == environmentName)
        .flatMap(
          credential =>
            scopesToRemove(credential, allowedScopes).map(
              scopeName =>
                ApplicationEnrichers.scopeRemovingApplicationEnricher(environmentName, application, idmsConnector, scopeName, Some(credential.clientId))
            )
        )
    )
  }

  private def addScopesToEnvironment(
    environmentName: EnvironmentName,
    application: Application,
    allowedScopes: Set[String]
  )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Application]] = {
    ApplicationEnrichers.process(
      application,
      allowedScopes
        .toSeq
        .map(
          scopeName =>
            ApplicationEnrichers.scopeAddingApplicationEnricher(environmentName, application, idmsConnector, scopeName)
        )
    )
  }

}
