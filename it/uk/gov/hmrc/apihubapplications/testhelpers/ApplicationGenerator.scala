package uk.gov.hmrc.apihubapplications.testhelpers

import org.scalacheck.{Arbitrary, Gen}
import uk.gov.hmrc.apihubapplications.models.application._

import java.time.{Instant, LocalDateTime, ZoneId}

trait ApplicationGenerator {

  implicit val appIdGenerator: Gen[Option[String]] = {
    Gen.option(Gen.listOfN(24, Gen.hexChar).map(_.mkString))
  }

  implicit val localDateTimeGenerator: Gen[LocalDateTime] = {
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

  implicit val creatorGenerator: Gen[Creator] = {
    for {
      email <- emailGenerator
    } yield Creator(email)
  }

  implicit val teamMemberGenerator: Gen[TeamMember] = {
    for {
      email <- emailGenerator
    } yield TeamMember(email)
  }

  implicit val scopeGenerator: Gen[Scope] = {
    for {
      name <- Gen.alphaStr
      status <- Gen.oneOf(ScopeStatus.values)
    } yield Scope(name, status)
  }

  implicit val credentialGenerator: Gen[Credential] = {
    for {
      clientId <- Gen.uuid
      clientSecret <- Gen.uuid
    } yield Credential(clientId.toString, clientSecret.toString)
  }

  implicit val environmentGenerator: Gen[Environment] = {
    for {
      scopes <- Gen.listOf(scopeGenerator)
      credentials <- Gen.listOf(credentialGenerator)
    } yield Environment(scopes, credentials)
  }

  implicit val environmentsGenerator: Gen[Environments] = {
    for {
      dev <- environmentGenerator
      test <- environmentGenerator
      preProd <- environmentGenerator
      prod <- environmentGenerator
    } yield Environments(dev, test, preProd, prod)
  }

  implicit val applicationGenerator: Arbitrary[Application] =
  Arbitrary {
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

  implicit val newApplicationGenerator: Arbitrary[NewApplication] =
    Arbitrary {
      for {
        name <- Gen.alphaStr
        createdBy <- creatorGenerator
      } yield
        NewApplication(
          name,
          createdBy
        )
    }


}
