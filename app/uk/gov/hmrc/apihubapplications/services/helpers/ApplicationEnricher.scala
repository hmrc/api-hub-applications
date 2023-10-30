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

import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.ClientNotFound
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, ClientScope}
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.useFirstException
import uk.gov.hmrc.http.HeaderCarrier

import java.time.Clock
import scala.concurrent.{ExecutionContext, Future}

trait ApplicationEnricher {

  def enrich(application: Application): Application

}

object ApplicationEnrichers {

  type EnricherProvider = (Application, IdmsConnector, Boolean) => Future[Either[IdmsException, ApplicationEnricher]]

  def process(
    application: Application,
    enrichers: Seq[Future[Either[IdmsException, ApplicationEnricher]]]
  )(implicit ec: ExecutionContext): Future[Either[IdmsException, Application]] = {
    Future.sequence(enrichers)
      .map(useFirstException)
      .map {
        case Right(enrichers) =>
          Right(enrichers.foldLeft(application)((newApplication, enricher) => enricher.enrich(newApplication)))
        case Left(e) => Left(e)
      }
  }

  def processAll(
    applications: Seq[Application],
    enricherProvider: EnricherProvider,
    idmsConnector: IdmsConnector,
    failOnError: Boolean
  )(implicit ec: ExecutionContext): Future[Either[IdmsException, Seq[Application]]] = {
    Future.sequence(
      applications.map(
        application =>
          enricherProvider(application, idmsConnector, failOnError).map {
            case Right(enricher) => Right(enricher.enrich(application))
            case Left(error: IdmsException) => Left(error)
          }
      )
    ).map(useFirstException)
  }

  private val noOpApplicationEnricher = new ApplicationEnricher {
    override def enrich(application: Application): Application = {
      application
    }
  }

  def secondaryCredentialApplicationEnricher(
    original: Application,
    idmsConnector: IdmsConnector
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {
    type IssueOrClientResponse = Either[String, ClientResponse]

    def toIssuesOrClientResponses(results: Seq[Either[IdmsException, ClientResponse]]): Seq[Either[IdmsException, IssueOrClientResponse]] = {
      results.map {
        case Right(clientResponse) => Right(Right(clientResponse))
        case Left(e @ IdmsException(_, _, ClientNotFound)) => Right(Left(Issues.secondaryCredentialNotFound(e)))
        case Left(e) => Left(e)
      }
    }

    def buildEnricher(issuesOrResponses: Seq[IssueOrClientResponse]): ApplicationEnricher = {
      (application: Application) => {
        issuesOrResponses.foldLeft(application)(
          (app, issueOrResponse) =>
            issueOrResponse match {
              case Right(clientResponse) => app.updateSecondaryCredential(clientResponse.clientId, clientResponse.secret)
              case Left(issue) => app.addIssue(issue)
            }
        )
      }
    }

    Future.sequence(
      original.getSecondaryCredentials.map {
        credential =>
          idmsConnector.fetchClient(Secondary, credential.clientId)
      }
    )
      .map(toIssuesOrClientResponses)
      .map(useFirstException)
      .map {
        case Right(issuesOrResponses) => Right(buildEnricher(issuesOrResponses))
        case Left(e) => Left(e)
      }
  }

  def secondaryScopeApplicationEnricher(
    original: Application,
    idmsConnector: IdmsConnector,
    failOnError: Boolean
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {

    def buildEnricher(clientScopes: Seq[ClientScope]): ApplicationEnricher = {
      (application: Application) => {
        application.setSecondaryScopes(
          clientScopes.map(clientScope => Scope(clientScope.clientScopeId, Approved))
        )
      }
    }

    def buildIssuesEnricher(idmsException: IdmsException): ApplicationEnricher = {
      (application: Application) => {
        application.addIssue(Issues.secondaryScopesNotFound(idmsException))
      }
    }

    if (original.getSecondaryCredentials.nonEmpty) {
      idmsConnector
        .fetchClientScopes(Secondary, original.getSecondaryMasterCredential.clientId)
        .map {
          case Right(clientScopes) => Right(buildEnricher(clientScopes))
          case Left(e @ IdmsException(_, _, ClientNotFound)) if !failOnError => Right(buildIssuesEnricher(e))
          case Left(e) => Left(e)
        }
    }
    else {
      Future.successful(Right(noOpApplicationEnricher))
    }
  }

  def primaryScopeApplicationEnricher(
    original: Application,
    idmsConnector: IdmsConnector
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {

    def buildEnricher(clientScopes: Seq[ClientScope]): ApplicationEnricher = {
      (application: Application) => {
        val approved = clientScopes.map(clientScope => Scope(clientScope.clientScopeId, Approved))
        val pending = application.getPrimaryScopes.filter(scope => scope.status == Pending)
        application.setPrimaryScopes(
          approved ++ pending
        )
      }
    }

    def buildIssuesEnricher(idmsException: IdmsException): ApplicationEnricher = {
      (application: Application) => {
        application.addIssue(Issues.primaryScopesNotFound(idmsException))
      }
    }

    if (original.getPrimaryCredentials.nonEmpty) {
      idmsConnector
        .fetchClientScopes(Primary, original.getPrimaryMasterCredential.clientId)
        .map {
          case Right(clientScopes) => Right(buildEnricher(clientScopes))
          case Left(e @ IdmsException(_, _, ClientNotFound)) => Right(buildIssuesEnricher(e))
          case Left(e) => Left(e)
        }
    }
    else {
      Future.successful(Right(noOpApplicationEnricher))
    }
  }

  def credentialCreatingApplicationEnricher(
    environmentName: EnvironmentName,
    original: Application,
    idmsConnector: IdmsConnector,
    clock: Clock
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {
    idmsConnector.createClient(environmentName, Client(original)).map {
      case Right(clientResponse) =>
        Right(
          (application: Application) => {
            environmentName match {
              case Primary => application.addPrimaryCredential(clientResponse.asNewHiddenCredential(clock))
              case Secondary => application.addSecondaryCredential(clientResponse.asNewCredential(clock))
            }
          }
        )
      case Left(e) => Left(e)
    }
  }

  def credentialDeletingApplicationEnricher(
    environmentName: EnvironmentName,
    clientId: String,
    idmsConnector: IdmsConnector
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {
    def buildEnricher(): ApplicationEnricher = {
      (application: Application) => {
        environmentName match {
          case Primary => application.removePrimaryCredential(clientId)
          case Secondary => application.removeSecondaryCredential(clientId)
        }
      }
    }

    idmsConnector.deleteClient(environmentName, clientId).map {
      case Right(()) => Right(buildEnricher())
      case Left(IdmsException(_, _, ClientNotFound)) => Right(buildEnricher())
      case Left(e) => Left(e)
    }
  }

  def scopeAddingApplicationEnricher(
    environmentName: EnvironmentName,
    original: Application,
    idmsConnector: IdmsConnector,
    scopeName: String
  )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {
    Future.sequence(
      (environmentName match {
        case Primary => original.getPrimaryCredentials
        case Secondary => original.getSecondaryCredentials
      }).map(
        credential =>
          idmsConnector.addClientScope(environmentName, credential.clientId, scopeName)
      )
    )
      .map(useFirstException)
      .map {
        case Right(_) =>
          Right(
            (application: Application) => {
              environmentName match {
                case Primary => application.addPrimaryScope(Scope(scopeName, Approved))
                case Secondary => application.addSecondaryScope(Scope(scopeName, Approved))
              }
            }
          )
        case Left(e) => Left(e)
      }
  }

  def scopesSettingApplicationEnricher(
                                        environmentName: EnvironmentName,
                                        original: Application,
                                        idmsConnector: IdmsConnector,
                                        approvedScopes: Seq[Scope]
                                    )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {


    val idmsFutures = approvedScopes.map(scope => idmsConnector.addClientScope(environmentName, original.getMasterCredentialFor(environmentName).clientId, scope.name))

    Future.sequence(idmsFutures)
      .map(useFirstException)
      .map {
        case Right(_) =>
          Right(
            (application: Application) => {
              environmentName match {
                case Primary => application.setPrimaryScopes(approvedScopes)
                case Secondary => application.setSecondaryScopes(approvedScopes)
              }
            }
          )
        case Left(e) => Left(e)
      }
  }


}
