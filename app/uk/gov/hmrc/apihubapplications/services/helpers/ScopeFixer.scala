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
  def fix(application: Application, accessRequests: Seq[AccessRequest])(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    (for {
      requiredScopes <- EitherT(requiredScopes(application))
      credentials <- EitherT(allCredentials(application))
      _ <- EitherT(processEnvironments(accessRequests, credentials, requiredScopes))
    } yield ()).value
  }

  def fix(
           application: Application,
           credential: Credential,
           hipEnvironment: HipEnvironment,
           accessRequests: Seq[AccessRequest],
         )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    (for {
      requiredScopes <- EitherT(requiredScopes(application))
      credentialScopes <- EitherT(credentialScopes(hipEnvironment, credential))
      _ <- EitherT(processCredentialScopes(hipEnvironment, accessRequests, Seq(credentialScopes), requiredScopes))
    } yield ()).value
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

  private def allCredentials(application: Application)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Seq[CredentialScopes]]] = {
    Future.sequence(
      hipEnvironments.environments.flatMap(
        environmentCredentials(application, _)
      )
    ).map(useFirstException)
  }

  private def environmentCredentials(application: Application, hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier) =
    application.getCredentials(hipEnvironment).map(
      credential =>
        credentialScopes(hipEnvironment, credential)
    )

  private def credentialScopes(hipEnvironment: HipEnvironment, credential: Credential)(implicit hc: HeaderCarrier) =
    idmsConnector.fetchClientScopes(hipEnvironment, credential.clientId)
      .map(_.map(
        scopes =>
          CredentialScopes(hipEnvironment.id, credential.clientId, credential.created, scopes.map(_.clientScopeId))
      ))

  private def processEnvironments(
                                   accessRequests: Seq[AccessRequest],
                                   credentialScopes: Seq[CredentialScopes],
                                   requiredScopes: Set[String]
  )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    Future.sequence(
      hipEnvironments.environments.map(
        hipEnvironment =>
          processCredentialScopes(hipEnvironment, accessRequests, credentialScopes, requiredScopes)
      )
    )
      .map(useFirstApplicationsException)
      .map(result => result.map(_ => ()))
  }

  private def processCredentialScopes(
                                 hipEnvironment: HipEnvironment,
                                 accessRequests: Seq[AccessRequest],
                                 credentialScopes: Seq[CredentialScopes],
                                 requiredScopes: Set[String]
                                 )(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] =
      Future.sequence(
        Seq(
          minimiseScopesInEnvironment(hipEnvironment, credentialScopes, allowedScopes(hipEnvironment, requiredScopes, accessRequests)),
          addScopesToEnvironment(hipEnvironment, credentialScopes, allowedScopes(hipEnvironment, requiredScopes, accessRequests))
        )
      )
      .map(useFirstApplicationsException)
      .map(result => result.map(_ => ()))

  private def allowedScopes(
    hipEnvironment: HipEnvironment,
    requiredScopes: Set[String],
    accessRequests: Seq[AccessRequest]
  ): Set[String] = {
    if (hipEnvironment.isProductionLike) {
      allowedProductionScopes(requiredScopes, accessRequests)
    }
    else {
      requiredScopes
    }
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
