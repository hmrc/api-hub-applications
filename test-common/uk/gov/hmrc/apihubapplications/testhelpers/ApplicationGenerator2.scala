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

package uk.gov.hmrc.apihubapplications.testhelpers

import org.scalacheck.Gen
import uk.gov.hmrc.apihubapplications.models.application.{Application, Approved, Creator, Credential, Environment, Environments, Pending, Scope, TeamMember}

import java.time.{Instant, LocalDateTime, ZoneId}

trait ApplicationGenerator2 {

  val appIdGenerator: Gen[Option[String]] = {
    Gen.some(Gen.listOfN(24, Gen.hexChar).map(_.mkString.toLowerCase))
  }

  val nonEmptyTextGenerator: Gen[String] = {
    Gen.alphaStr.suchThat(_.nonEmpty)
  }

  val localDateTimeGenerator: Gen[LocalDateTime] = {
    for {
      calendar <- Gen.calendar
    } yield LocalDateTime.ofInstant(Instant.ofEpochMilli(calendar.getTimeInMillis), ZoneId.of("GMT"))
  }

  val pendingScopeGenerator: Gen[Scope] = {
    for {
      name <- nonEmptyTextGenerator
    } yield Scope(name, Pending)
  }

  val approvedScopeGenerator: Gen[Seq[Scope]] = {
    for {
      scopeNames <- Gen.nonEmptyListOf(nonEmptyTextGenerator)
    } yield scopeNames.map(scopeName => Scope(scopeName, Approved))
  }

  val clientIdGenerator: Gen[String] = {
    for {
      clientId <- Gen.uuid
    } yield clientId.toString
  }

  val secretGenerator: Gen[String] = {
    for {
      secret <- Gen.uuid
    } yield secret.toString
  }

  val environmentsGenerator: Gen[Environments] = {
    for {
      primaryClientId <- clientIdGenerator
      primarySecret <- secretGenerator
      primaryScopes <- Gen.nonEmptyListOf(pendingScopeGenerator)
      secondaryClientId <- clientIdGenerator
      secondarySecret <- secretGenerator
    } yield
      Environments(
        primary = Environment(
          primaryScopes,  // Only pending scopes are stored
          Seq(Credential(primaryClientId, None, Some(primarySecret.takeRight(4))))
        ),
        secondary = Environment(
          Seq.empty,  // We don't store these any more
          Seq(Credential(secondaryClientId, Some(secondarySecret), Some(secondarySecret.takeRight(4))))
        )
      )
  }

  val emailGenerator: Gen[String] = {
    for {
      firstName <- nonEmptyTextGenerator
      lastName <- nonEmptyTextGenerator
      emailDomain <- Gen.oneOf(Seq("hmrc.gov.uk", "digital.hmrc.gov.uk"))
    } yield s"$firstName.$lastName@$emailDomain"
  }

  val creatorGenerator: Gen[Creator] = {
    for {
      email <- emailGenerator
    } yield Creator(email)
  }

  val applicationGenerator: Gen[Application] = {
    for {
      appId <- appIdGenerator
      name <- nonEmptyTextGenerator
      created <- localDateTimeGenerator
      createdBy <- creatorGenerator
      lastUpdated <- localDateTimeGenerator
      environments <- environmentsGenerator
    } yield
      Application(
        appId,
        name,
        created,
        createdBy,
        lastUpdated,
        Seq(TeamMember(createdBy.email)),
        environments
      )
  }

  val applicationsGenerator: Gen[Seq[Application]] = {
    for {
      applications <- Gen.nonEmptyListOf(applicationGenerator)
    } yield applications
  }

}
