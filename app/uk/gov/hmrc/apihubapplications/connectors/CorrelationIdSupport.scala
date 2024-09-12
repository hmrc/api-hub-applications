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

package uk.gov.hmrc.apihubapplications.connectors

import uk.gov.hmrc.apihubapplications.models.application.{EnvironmentName, Primary, Secondary}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.client.RequestBuilder

trait CorrelationIdSupport:
  object CorrelationIdSupport:
    val correlationIdHeader = "X-Correlation-Id"

    extension (requestBuilder: RequestBuilder) {
      def withCorrelationId()(using hc: HeaderCarrier): RequestBuilder =
        hc.requestId.fold(requestBuilder)(requestId => requestBuilder.setHeader(correlationIdHeader -> requestId.value))
    }

    extension (context: Seq[(String, AnyRef)]) {
      def withCorrelationId()(using hc: HeaderCarrier): Seq[(String, AnyRef)] =
        hc.requestId.fold(context)(requestId => context :+ (correlationIdHeader -> requestId.value))
    }
