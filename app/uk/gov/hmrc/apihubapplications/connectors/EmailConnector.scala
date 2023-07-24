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

package uk.gov.hmrc.apihubapplications.connectors

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.json.{Json, OFormat}
import uk.gov.hmrc.apihubapplications.models.exception.{EmailException, ExceptionRaising}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class EmailConnector @Inject()(
  servicesConfig: ServicesConfig,
  httpClient: HttpClientV2
)(implicit ec: ExecutionContext) extends Logging with ExceptionRaising {

  private val addTeamMemberToApplicationTemplateId = {
    val templateId = servicesConfig.getConfString("email.addTeamMemberToApplicationTemplateId", "")

    if (templateId.isEmpty) {
      raiseEmailException.missingConfig("email.addTeamMemberToApplicationTemplateId")
    }

    templateId
  }

  def sendAddTeamMemberEmail(emails: Seq[String])(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {
    val url = url"${servicesConfig.baseUrl("email")}/hmrc/email"
    val request = SendEmailRequest(emails, addTeamMemberToApplicationTemplateId)

    httpClient.post(url)
      .withBody(Json.toJson(request))
      .execute[Either[UpstreamErrorResponse, Unit]]
      .map {
        case Right(()) => Right(())
        case Left(e) => Left(raiseEmailException.unexpectedResponse(e))
      }
      .recover {
        case throwable => Left(raiseEmailException.error(throwable))
      }
  }

}

// Elided class from the email api
// See https://github.com/hmrc/email/blob/main/app/uk/gov/hmrc/email/controllers/model/SendEmailRequest.scala
case class SendEmailRequest(
  to: Seq[String],
  templateId: String
)

object SendEmailRequest {

  implicit val formatSendEmailRequest: OFormat[SendEmailRequest] = Json.format[SendEmailRequest]

}
