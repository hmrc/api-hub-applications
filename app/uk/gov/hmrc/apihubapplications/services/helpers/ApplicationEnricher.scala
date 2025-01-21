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

import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application.*
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

  private type EnricherProvider = (Application, IdmsConnector) => Future[Either[IdmsException, ApplicationEnricher]]

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
                  idmsConnector: IdmsConnector
                )(implicit ec: ExecutionContext): Future[Either[IdmsException, Seq[Application]]] = {
    Future.sequence(
      applications.map(
        application =>
          enricherProvider(application, idmsConnector).map {
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

  def credentialCreatingApplicationEnricher(
                                             hipEnvironment: HipEnvironment,
                                             original: Application,
                                             idmsConnector: IdmsConnector,
                                             clock: Clock,
                                           )(implicit ec: ExecutionContext, hc: HeaderCarrier): Future[Either[IdmsException, ApplicationEnricher]] = {
      idmsConnector.createClient(hipEnvironment, Client(original)).map {
        case Right(clientResponse) =>
          Right(
            (application: Application) => {
              application.addCredential(hipEnvironment, clientResponse.asNewCredential(clock, hipEnvironment))
            }
          )
        case Left(e) => Left(e)
      }
  }

}
