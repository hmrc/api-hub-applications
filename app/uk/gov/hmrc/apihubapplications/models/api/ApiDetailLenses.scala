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

package uk.gov.hmrc.apihubapplications.models.api

import uk.gov.hmrc.apihubapplications.models.Lens
import uk.gov.hmrc.apihubapplications.models.application.Application

object ApiDetailLenses {

  val apiDetailDomain: Lens[ApiDetail, Option[String]] =
    Lens[ApiDetail, Option[String]](
      get = _.domain,
      set = (apiDetail, domain) => apiDetail.copy(domain = domain)
    )

  val apiDetailSubDomain: Lens[ApiDetail, Option[String]] =
    Lens[ApiDetail, Option[String]](
      get = _.subDomain,
      set = (apiDetail, subDomain) => apiDetail.copy(subDomain = subDomain)
    )

  val apiEndpoints: Lens[ApiDetail, Seq[Endpoint]] =
    Lens[ApiDetail, Seq[Endpoint]](
      get = _.endpoints,
      set = (apiDetail, endpoints) => apiDetail.copy(endpoints = endpoints)
    )

  implicit class ApiDetailLensOps(apiDetail: ApiDetail) {

    def getRequiredScopeNames(application: Application): Set[String] = {
      apiDetail.endpoints.flatMap(
        apiEndpoint =>
          apiEndpoint.methods.filter(
            endpointMethod =>
              application.apis.exists(
                api =>
                  api.id.equals(apiDetail.id) &&
                    api.endpoints.exists(
                      applicationEndpoint =>
                        applicationEndpoint.path.equals(apiEndpoint.path) &&
                          applicationEndpoint.httpMethod.equalsIgnoreCase(endpointMethod.httpMethod)
                    )
              )
          )
          .flatMap(_.scopes)
      )
      .toSet
    }

    def setDomain(domainCode: Option[String]): ApiDetail =
      apiDetailDomain.set(apiDetail, domainCode)

    def setDomain(domainCode: String): ApiDetail =
      setDomain(Some(domainCode))

    def setSubDomain(subDomainCode: Option[String]): ApiDetail =
      apiDetailSubDomain.set(apiDetail, subDomainCode)

    def setSubDomain(subDomainCode: String): ApiDetail =
      setSubDomain(Some(subDomainCode))

    def addEndpoint(endpoint: Endpoint): ApiDetail =
      apiEndpoints.set(apiDetail, apiDetail.endpoints :+ endpoint)

    def removeEndpoint(path: String): ApiDetail =
      apiEndpoints.set(
        apiDetail,
        apiDetail.endpoints.filterNot(_.path.equals(path))
      )

  }

}
