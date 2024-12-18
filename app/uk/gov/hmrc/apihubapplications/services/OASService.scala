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
import com.fasterxml.jackson.databind.module.SimpleModule
import com.google.inject.{Inject, Singleton}
import io.circe.yaml
import play.api.Logging
import uk.gov.hmrc.apihubapplications.connectors.APIMConnector
import uk.gov.hmrc.apihubapplications.models.apim.{Error as ApimError, *}
import uk.gov.hmrc.apihubapplications.models.exception.ApimException
import uk.gov.hmrc.http.HeaderCarrier
//import io.swagger.oas.inflector.examples.ExampleBuilder
import io.swagger.oas.inflector.processors.JsonNodeExampleSerializer
import io.swagger.v3.oas.models.media.{Content, MediaType, Schema}
import io.swagger.v3.oas.models.servers.Server
import io.swagger.v3.oas.models.*
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.{ParseOptions, SwaggerParseResult}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class OASService @Inject()(apimConnector: APIMConnector) {

  val oasTitleMaxSize = 46

  def validateInPrimary(oas: String, validateTitle: Boolean)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Either[ApimException, ValidateResponse]] = {

    if (validateTitle) {
      val title = oasTitle(oas)
      val validOasTitle = isValidOasTitle(oas)
      apimConnector.validateInPrimary(oas) map {
        case Right(SuccessfulValidateResponse) if validOasTitle => Right(SuccessfulValidateResponse)
        case Right(SuccessfulValidateResponse) => Right(InvalidOasResponse(newFailuresResponse(title)))
        case Right(invalidOasResponse: InvalidOasResponse) if validOasTitle => Right(invalidOasResponse)
        case Right(invalidOasResponse: InvalidOasResponse) =>
          val failure = invalidOasResponse.failure
          Right(invalidOasResponse.copy(
            failure = failure.copy(
              errors = Some(failure.errors.toList.flatten ++ Seq(oasTitleError(title))))))
        case Left(exception) => Left(exception)
      }
    } else {
      apimConnector.validateInPrimary(oas)
    }
  }

  private def oasTitleError(maybeTitle: Option[String]) = {
    maybeTitle match {
      case Some(title) => ApimError("APIM", s"Oas title has ${title.length} characters. Maximum is $oasTitleMaxSize.")
      case _ => ApimError("APIM", "Oas has no title.")
    }
  }
  
  private def newFailuresResponse(maybeTitle: Option[String]) = {
    maybeTitle match {
      case Some(title) => FailuresResponse("BAD_REQUEST", s"oas title is longer than $oasTitleMaxSize characters", Some(Seq(oasTitleError(maybeTitle))))
      case _ => FailuresResponse("BAD_REQUEST", "Oas has no title.", Some(Seq(oasTitleError(maybeTitle))))
    }
  }

  private def isValidOasTitle(oas: String) = oasTitle(oas).exists(title => title.length <= oasTitleMaxSize)

  private def oasTitle(oas: String): Option[String] = parse(oas) map (_.getInfo) map (_.getTitle)

  private def parse(openApiSpecification: String): Option[OpenAPI] = 
    Option(new OpenAPIV3Parser().readContents(openApiSpecification, null, null).getOpenAPI)

}
