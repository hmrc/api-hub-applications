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
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}
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
  idmsConnector: IdmsConnector,
  hipEnvironments: HipEnvironments
)(implicit ec: ExecutionContext) {

  // Manipulate the model first, adding and removing APIs and endpoints, THEN call this method to fix scopes
  // The application does not have to be enriched
  def fix(application: Application, accessRequests: Seq[AccessRequest])(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] =
    fix(application, accessRequests, application.credentials)

  def fix(application: Application, accessRequests: Seq[AccessRequest], credential: Credential)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] =
    fix(application, accessRequests, Set(credential))

  def fix(application: Application, accessRequests: Seq[AccessRequest], credentials: Set[Credential])(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    (for {
      requiredScopes <- EitherT(requiredScopes(application))
      credentials <- EitherT(credentialsScopes(credentials))
      _ <- EitherT(processEnvironments(accessRequests, credentials, requiredScopes))
    } yield ()).value
  }

  def fix(
    application: Application,
    accessRequests: Seq[AccessRequest],
    hipEnvironment: HipEnvironment
  )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    val environmentCredentials = application.getCredentials(hipEnvironment).toSet
    fix(application, accessRequests, environmentCredentials)
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

  private def credentialsScopes(credentials: Set[Credential])(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Seq[CredentialScopes]]] = {
    Future.sequence(
      hipEnvironments.environments.flatMap(
        hipEnvironment =>
          credentials.filter(_.environmentId == hipEnvironment.id).map(
            credential =>
              idmsConnector
                .fetchClientScopes(hipEnvironment, credential.clientId)
                .map(_.map(
                  scopes =>
                    CredentialScopes(hipEnvironment.id, credential.clientId, credential.created, scopes.map(_.clientScopeId))
                ))
          )
      )
    ).map(useFirstException)
  }

  private def processEnvironments(
    accessRequests: Seq[AccessRequest],
    credentials: Seq[CredentialScopes],
    requiredScopes: Set[String]
  )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    Future.sequence(
      hipEnvironments.environments.flatMap {
        hipEnvironment =>
          val scopes = allowedScopes(hipEnvironment, requiredScopes, accessRequests)
          Seq(
            minimiseScopesInEnvironment(hipEnvironment, credentials, scopes),
            addScopesToEnvironment(hipEnvironment, credentials, scopes)
          )
      }
    )
      .map(useFirstApplicationsException)
      .map(result => result.map(_ => ()))
  }

  private def allowedScopes(
    hipEnvironment: HipEnvironment,
    requiredScopes: Set[String],
    accessRequests: Seq[AccessRequest]
  ): Set[String] = {
    if (hipEnvironment.isProductionLike) {
      allowedProductionLikeScopes(requiredScopes, accessRequests, hipEnvironment)
    }
    else {
      requiredScopes
    }
  }

  private def allowedProductionLikeScopes(requiredScopes: Set[String], accessRequests: Seq[AccessRequest], hipEnvironment: HipEnvironment): Set[String] = {
    accessRequests
      .filter(ar => ar.status == Approved && ar.environmentId == hipEnvironment.id)
      .flatMap(_.endpoints.flatMap(_.scopes))
      .toSet
      .intersect(requiredScopes)
  }

  private def scopesToRemove(credential: CredentialScopes, requiredScopes: Set[String]): Set[String] = {
    credential.scopes.toSet -- requiredScopes
  }

  private def minimiseScopesInEnvironment(
    hipEnvironment: HipEnvironment,
    credentials: Seq[CredentialScopes],
    allowedScopes: Set[String]
  )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    Future.sequence(
      credentials
        .filter(_.environmentId == hipEnvironment.id)
        .flatMap(
          credential =>
            scopesToRemove(credential, allowedScopes).map(
              scopeName =>
                idmsConnector.deleteClientScope(hipEnvironment, credential.clientId, scopeName)
            )
        )
    )
      .map(useFirstApplicationsException)
      .map(_.map(_ => ()))
  }

  private def addScopesToEnvironment(
    hipEnvironment: HipEnvironment,
    credentials: Seq[CredentialScopes],
    allowedScopes: Set[String]
  )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    Future.sequence(
      credentials
        .filter(_.environmentId == hipEnvironment.id)
        .flatMap(
          credential =>
            allowedScopes.map(
              scopeName =>
                idmsConnector.addClientScope(hipEnvironment, credential.clientId, scopeName)
            )
        )
    )
      .map(useFirstApplicationsException)
      .map(_.map(_ => ()))
  }

}
