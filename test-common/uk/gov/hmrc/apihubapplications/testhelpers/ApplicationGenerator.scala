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

import org.scalacheck.{Arbitrary, Gen}
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps

import java.time.{Instant, LocalDateTime, ZoneId}

trait ApplicationGenerator {

  val appIdGenerator: Gen[Option[String]] = {
    Gen.some(Gen.listOfN(24, Gen.hexChar).map(_.mkString.toLowerCase))
  }

  val localDateTimeGenerator: Gen[LocalDateTime] = {
    for {
      calendar <- Gen.calendar
    } yield LocalDateTime.ofInstant(Instant.ofEpochMilli(calendar.getTimeInMillis), ZoneId.of("GMT"))
  }

  val emailGenerator: Gen[String] = {
    for {
      username <- Gen.alphaLowerStr
      emailDomain <- Gen.oneOf(Seq("@hmrc.gov.uk", "@digital.hmrc.gov.uk"))
    } yield s"$username$emailDomain"
  }

  val creatorGenerator: Gen[Creator] = {
    for {
      email <- emailGenerator
    } yield Creator(email)
  }

  val teamMemberGenerator: Gen[TeamMember] = {
    for {
      email <- emailGenerator
    } yield TeamMember(email)
  }

  val scopeGenerator: Gen[Scope] = {
    for {
      name <- Gen.alphaStr
      status <- Gen.oneOf(ScopeStatus.values)
    } yield Scope(name, status)
  }

  val credentialGenerator: Gen[Credential] = {
    for {
      clientId <- Gen.uuid
      clientSecret <- Gen.uuid
    } yield Credential(clientId.toString, Some(clientSecret.toString), Some(clientSecret.toString.takeRight(4)))
  }

  val environmentGenerator: Gen[Environment] = {
    for {
      scopes <- Gen.listOf(scopeGenerator)
      credentials <- Gen.listOf(credentialGenerator)
    } yield Environment(scopes, credentials)
  }

  val environmentsGenerator: Gen[Environments] = {
    for {
      primary <- environmentGenerator
      secondary <- environmentGenerator
    } yield Environments(primary,secondary)
  }

  implicit val applicationArbitrary: Arbitrary[Application] = Arbitrary {
    for {
      appId <- appIdGenerator
      name <- Gen.alphaStr
      created <- localDateTimeGenerator
      createdBy <- creatorGenerator
      lastUpdated <- localDateTimeGenerator
      teamMembers <- Gen.listOf(teamMemberGenerator)
      environments <- environmentsGenerator
    } yield
    Application(
      appId,
      name,
      created,
      createdBy,
      lastUpdated,
      teamMembers,
      environments
    )
  }

  val applicationGenerator: Gen[Application] = applicationArbitrary.arbitrary

  implicit val newApplicationGenerator: Arbitrary[NewApplication] =
    Arbitrary {
      for {
        name <- Gen.alphaStr
        createdBy <- creatorGenerator
        teamMembers <- Gen.listOf(teamMemberGenerator)
      } yield
        NewApplication(
          name,
          createdBy,
          teamMembers
        )
    }

  val newScopeGenerator: Gen[NewScope] = {
    for {
      name <- Gen.alphaStr.suchThat(_.nonEmpty)
      environments <- Gen.atLeastOne(EnvironmentName.values)
    } yield NewScope(name, environments.toSeq)
  }

  implicit val newScopesGenerator: Arbitrary[Seq[NewScope]] =
    Arbitrary {
      Gen.listOf(newScopeGenerator)
    }

  //--------------------------------
  val nonEmptyText: Gen[String] = {
    Gen.alphaStr.suchThat(_.nonEmpty)
  }

  val pendingScopeGenerator: Gen[Scope] = {
    for {
      name <- nonEmptyText
    } yield Scope(name, Pending)
  }

  val approvedScopeGenerator: Gen[Seq[Scope]] = {
    for {
      scopeNames <- Gen.nonEmptyListOf(nonEmptyText)
    } yield scopeNames.map(scopeName => Scope(scopeName, Approved))
  }

  val basicEnvironmentsGen: Gen[Environments] = {
    for {
      primaryClientId <- Gen.uuid
      primarySecret <- Gen.uuid
      primaryScopes <- Gen.nonEmptyListOf(pendingScopeGenerator)
      secondaryClientId <- Gen.uuid
      secondarySecret <- Gen.uuid
    } yield
      Environments(
        primary = Environment(
          primaryScopes,  // Only pending scopes are stored
          Seq(Credential(primaryClientId.toString, None, Some(primarySecret.toString.takeRight(4))))
        ),
        secondary = Environment(
          Seq.empty,  // We don't store these any more
          Seq(Credential(secondaryClientId.toString, Some(secondarySecret.toString), Some(secondarySecret.toString.takeRight(4))))
        )
      )
  }

  val emailGenerator2: Gen[String] = {
    for {
      firstName <- nonEmptyText
      lastName <- nonEmptyText
      emailDomain <- Gen.oneOf(Seq("hmrc.gov.uk", "digital.hmrc.gov.uk"))
    } yield s"$firstName.$lastName@$emailDomain"
  }

  val creatorGenerator2: Gen[Creator] = {
    for {
      email <- emailGenerator2
    } yield Creator(email)
  }

  val basicApplicationGen: Gen[Application] = {
    for {
      appId <- appIdGenerator
      name <- nonEmptyText
      created <- localDateTimeGenerator
      createdBy <- creatorGenerator2
      lastUpdated <- localDateTimeGenerator
      environments <- basicEnvironmentsGen
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

  val applicationsGen: Gen[Seq[Application]] = {
    for {
      applications <- Gen.nonEmptyListOf(basicApplicationGen)
    } yield applications
  }

}

object ApplicationGenerator extends ApplicationGenerator {

  implicit class ApplicationGenOps(gen: Gen[Application]) {

    def withoutPrimaryCredentials(): Gen[Application] = {
      gen.map(_.setPrimaryCredentials(Seq.empty))
    }

    def withPrimaryCredentialWithoutSecret(): Gen[Application] = {
      gen.flatMap {
        application =>
          for {
            existing <- Gen.const(application)
            credential <- credentialGenerator
          } yield existing.setPrimaryCredentials(Seq(credential.copy(clientSecret = None, secretFragment = None)))
      }
    }
  }

  implicit class SeqApplicationGenOps(gen: Gen[Seq[Application]]) {

    def withTeamMember(email: String): Gen[Seq[Application]] = {
      gen.map {
        applications =>
          applications.map(_.addTeamMember(email))
      }
    }

  }

}
