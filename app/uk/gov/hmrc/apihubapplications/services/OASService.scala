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

package uk.gov.hmrc.apihubapplications.services
import com.google.inject.{Inject, Singleton}
import io.circe.*
import uk.gov.hmrc.apihubapplications.connectors.APIMConnector
import uk.gov.hmrc.apihubapplications.models.apim.{Error as ApimError, *}
import uk.gov.hmrc.apihubapplications.models.exception.ApimException
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class OASService @Inject()(apimConnector: APIMConnector) {

  val oasTitleError = ApimError("APIM", "Oas title is too long.")

  def validateInPrimary(oas: String, validateTitle: Boolean)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Either[ApimException, ValidateResponse]] = {
    val validOasTitle = isValidOasTitle(oas)
    
    if (validateTitle) {
      apimConnector.validateInPrimary(oas) map {
        case Right(SuccessfulValidateResponse) if validOasTitle => Right(SuccessfulValidateResponse)
        case Right(SuccessfulValidateResponse) => Right(InvalidOasResponse(newFailuresResponse))
        case Right(invalidOasResponse: InvalidOasResponse) if validOasTitle => Right(invalidOasResponse)
        case Right(invalidOasResponse: InvalidOasResponse) if invalidOasResponse.failure.errors.isDefined =>
          val failure = invalidOasResponse.failure
          val existingErrors = failure.errors.get
          Right(invalidOasResponse.copy(failure = failure.copy(errors = Some(existingErrors ++ Seq(oasTitleError)))))
        case Right(invalidOasResponse: InvalidOasResponse) =>
          val failure = invalidOasResponse.failure
          Right(invalidOasResponse.copy(failure = failure.copy(errors = Some(Seq(oasTitleError)))))
        case Left(exception) => Left(exception)
      }
    } else {
      apimConnector.validateInPrimary(oas)
    }
  }

  private def newFailuresResponse = {
    FailuresResponse("BAD_REQUEST", "oas title is longer than 46 characters", Some(Seq(oasTitleError)))
  }

  private def isValidOasTitle(oas: String) = oasTitle(oas).forall(title => title.length <= 46)

  private def oasTitle(oas: String): Option[String] = {
    val oasYamlOrFail = yaml.parser.parse(oas)
    oasYamlOrFail match
      case Right(json) => Some(json.findAllByKey("title").head.toString)
      case Left(e) => None
  }

}
