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

package uk.gov.hmrc.apihubapplications.testhelpers

import org.scalacheck.rng.Seed
import org.scalacheck.{Arbitrary, Gen}
import uk.gov.hmrc.apihubapplications.models.api.{ApiDetail, ApiStatus, ApiType, Endpoint, EndpointMethod}

import java.time.Instant

trait ApiDetailGenerators {

  private val listSizeQuota = 10
  private val maxSensibleStringSize = 500

  private def sensiblySizedAlphaNumStr: Gen[String] = Gen.resize(maxSensibleStringSize, Gen.alphaNumStr)

  private def genEndpointMethod: Gen[EndpointMethod] = Gen.sized {size =>
    for {
      httpMethod <- Gen.oneOf("GET", "POST", "PUT", "PATCH", "DELETE")
      summary <- Gen.option(sensiblySizedAlphaNumStr)
      description <- Gen.option(sensiblySizedAlphaNumStr)
      scopes <- Gen.listOfN(size / listSizeQuota, sensiblySizedAlphaNumStr.suchThat(_.nonEmpty))
    } yield EndpointMethod(httpMethod, summary, description, scopes)
  }

  implicit lazy val arbitraryEndpointMethod: Arbitrary[EndpointMethod] = Arbitrary(genEndpointMethod)

  private def genEndpoint: Gen[Endpoint] = Gen.sized {size=>
    for {
      path <- sensiblySizedAlphaNumStr.suchThat(_.nonEmpty)
      methods <- Gen.listOfN(size / listSizeQuota, genEndpointMethod).suchThat(_.nonEmpty)
    } yield Endpoint(path, methods)
  }

  implicit lazy val arbitraryEndpoint: Arbitrary[Endpoint] = Arbitrary(genEndpoint)

  private def genInstant: Gen[Instant] = {
    Gen.calendar.map(calendar => calendar.toInstant)
  }

  private def genApiDetail: Gen[ApiDetail] = Gen.sized {size =>
    for {
      id <- Gen.uuid
      title <- sensiblySizedAlphaNumStr
      description <- sensiblySizedAlphaNumStr
      publisherReference <- sensiblySizedAlphaNumStr
      version <- sensiblySizedAlphaNumStr
      endpoints <- Gen.listOfN(size / listSizeQuota, arbitraryEndpoint.arbitrary).suchThat(_.nonEmpty)
      shortDescription <- sensiblySizedAlphaNumStr
      openApiSpecification <- sensiblySizedAlphaNumStr
      apiStatus <- Gen.oneOf(ApiStatus.values)
      domain <- sensiblySizedAlphaNumStr
      subDomain <- sensiblySizedAlphaNumStr
      hods <- Gen.listOfN(size/ listSizeQuota, sensiblySizedAlphaNumStr).suchThat(_.nonEmpty)
      reviewedDate <- genInstant
      apiType <- Gen.oneOf(ApiType.values.toIndexedSeq)
    } yield ApiDetail(
      id.toString,
      publisherReference,
      title,
      description,
      version,
      endpoints,
      Some(shortDescription),
      openApiSpecification,
      apiStatus,
      domain = Some(domain),
      subDomain = Some(subDomain),
      hods = hods,
      apiType = Some(apiType),
    )
  }

  implicit lazy val arbitraryApiDetail: Arbitrary[ApiDetail] = Arbitrary(genApiDetail)

  implicit val arbitraryApiDetails: Arbitrary[Seq[ApiDetail]] =
    Arbitrary {
      Gen.nonEmptyListOf(arbitraryApiDetail.arbitrary)
    }

  private val parameters = Gen.Parameters.default

  def sampleApiDetail(): ApiDetail =
    genApiDetail.pureApply(parameters, Seed.random())

  def sampleOas: String =
    """openapi: 3.0.3
      |info:
      |  title: Swagger Sample - OpenAPI 3.0
      |  description: |-
      |    This is a sample
      |  version: 1.0.0
      |tags:
      |  - name: thing
      |    description: put thing
      |paths:
      |  /thing:
      |    put:
      |      tags:
      |        - thing
      |      summary: Update a thing
      |      description: Update an existing pet by Id
      |      operationId: updatePet
      |      requestBody:
      |        description: Update a thing
      |        content:
      |          application/json:
      |            schema:
      |              $ref: '#/components/schemas/Thing'
      |          application/xml:
      |            schema:
      |              $ref: '#/components/schemas/Thing'
      |          application/x-www-form-urlencoded:
      |            schema:
      |              $ref: '#/components/schemas/Thing'
      |        required: true
      |      responses:
      |        '200':
      |          description: Successful operation
      |          content:
      |            application/json:
      |              schema:
      |                $ref: '#/components/schemas/Thing'
      |            application/xml:
      |              schema:
      |                $ref: '#/components/schemas/Thing'
      |        '400':
      |          description: Invalid ID supplied
      |        '404':
      |          description: Pet not found
      |        '405':
      |          description: Validation exception
      |      security:
      |        - petstore_auth:
      |            - write:pets
      |            - read:pets
      |
      |components:
      |  schemas:
      |    Thing:
      |      properties:
      |        id:
      |          type: integer
      |          example: 10
      |      xml:
      |        name: thing
      |  requestBodies:
      |    Thing:
      |      description: Thing that needs to be added
      |      content:
      |        application/json:
      |          schema:
      |            $ref: '#/components/schemas/Thing'
      |        application/xml:
      |          schema:
      |            $ref: '#/components/schemas/Thing'
      |  securitySchemes:
      |    petstore_auth:
      |      type: oauth2
      |      flows:
      |        implicit:
      |          authorizationUrl: https://petstore3.swagger.io/oauth/authorize
      |          scopes:
      |            write:pets: modify pets in your account
      |            read:pets: read your pets
      |""".stripMargin

}
