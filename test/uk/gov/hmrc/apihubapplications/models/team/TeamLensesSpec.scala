/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.apihubapplications.models.team

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import uk.gov.hmrc.apihubapplications.models.team.TeamLenses.*

import java.time.LocalDateTime

class TeamLensesSpec extends AnyFreeSpec with Matchers with TableDrivenPropertyChecks {

  import TeamLensesSpec.*

  "hasEgress" - {
    "must return true/false depending on whether the team has an egress" in {
      val egressId = "test-egress-id"

      val scenarios = Table(
        ("egresses", "expected"),
        (Seq.empty, false),
        (Seq("other-egress-1", "other-egress-2"), false),
        (Seq(egressId, "other-egress"), true)
      )

      forAll(scenarios) {(egresses, expected) =>
        basicTeam.copy(egresses = egresses).hasEgress(egressId) mustBe expected
      }
    }
  }

  "removeEgress" - {
    "must correctly remove an egress when it exists" in {
      val egressId1 = "test-egress-id-1"
      val egressId2 = "test-egress-id-2"
      val egressId3 = "test-egress-id-3"

      val team = basicTeam.copy(egresses = Seq(egressId1, egressId2, egressId3))

      val scenarios = Table(
        ("egressId", "expected"),
        (egressId1, Seq(egressId2, egressId3)),
        (egressId2, Seq(egressId1, egressId3)),
        (egressId3, Seq(egressId1, egressId2)),
        ("another-egress", team.egresses)
      )

      forAll(scenarios) {(egressId, expected) =>
        team.removeEgress(egressId).egresses must contain theSameElementsInOrderAs expected
      }
    }
  }

}

private object TeamLensesSpec {

  val basicTeam: Team = Team(
    id = None,
    name = "test-name",
    created = LocalDateTime.now(),
    teamMembers = Seq.empty,
    teamType = TeamType.ConsumerTeam,
    egresses = Seq.empty
  )

}
