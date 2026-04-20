package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumHistoryId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.records.SimplifiedPlantingSiteHistoriesRecord
import com.terraformation.backend.db.tracking.tables.records.SimplifiedPlantingSitesRecord
import com.terraformation.backend.db.tracking.tables.records.SimplifiedStrataRecord
import com.terraformation.backend.db.tracking.tables.records.SimplifiedStratumHistoriesRecord
import com.terraformation.backend.db.tracking.tables.records.SimplifiedSubstrataRecord
import com.terraformation.backend.db.tracking.tables.records.SimplifiedSubstratumHistoriesRecord
import com.terraformation.backend.point
import com.terraformation.backend.util.Turtle
import com.terraformation.backend.util.equalsOrBothNull
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.MultiPolygon

internal class PlantingSiteStoreSimplifySiteTest : BasePlantingSiteStoreTest() {
  private val gridOrigin = point(0)
  private val siteBoundary: MultiPolygon = Turtle(gridOrigin).makeMultiPolygon { square(100) }
  private val exclusion: MultiPolygon =
      Turtle(gridOrigin).makeMultiPolygon {
        east(50)
        north(50)
        square(50)
      }

  private val stratumBoundaryA: MultiPolygon =
      Turtle(gridOrigin).makeMultiPolygon { rectangle(100, 50) }
  private val stratumBoundaryB: MultiPolygon =
      Turtle(gridOrigin).makeMultiPolygon {
        north(50)
        square(50)
      }

  private val substratumBoundaryA1: MultiPolygon =
      Turtle(gridOrigin).makeMultiPolygon { square(50) }
  private val substratumBoundaryA2: MultiPolygon =
      Turtle(gridOrigin).makeMultiPolygon {
        east(50)
        square(50)
      }
  private val substratumBoundaryB: MultiPolygon = stratumBoundaryB

  private val simplifiedSiteBoundary: MultiPolygon =
      Turtle(gridOrigin).makeMultiPolygon { square(99) }
  private val simplifiedExclusion: MultiPolygon =
      Turtle(gridOrigin).makeMultiPolygon {
        east(50)
        north(50)
        square(49)
      }

  private val simplifiedStratumBoundaryA: MultiPolygon =
      Turtle(gridOrigin).makeMultiPolygon { rectangle(99, 49) }
  private val simplifiedStratumBoundaryB: MultiPolygon =
      Turtle(gridOrigin).makeMultiPolygon {
        north(50)
        square(49)
      }

  private val simplifiedSubstratumBoundaryA1: MultiPolygon =
      Turtle(gridOrigin).makeMultiPolygon { square(50) }
  private val simplifiedSubstratumBoundaryA2: MultiPolygon =
      Turtle(gridOrigin).makeMultiPolygon {
        east(50)
        square(49)
      }
  private val simplifiedSubstratumBoundaryB: MultiPolygon = simplifiedStratumBoundaryB

  private val otherBoundary = Turtle(gridOrigin).makeMultiPolygon { square(900) }
  private val otherSimplifiedBoundary = Turtle(gridOrigin).makeMultiPolygon { square(300) }

  lateinit var siteId: PlantingSiteId
  lateinit var stratumIdA: StratumId
  lateinit var stratumIdB: StratumId
  lateinit var substratumIdA1: SubstratumId
  lateinit var substratumIdA2: SubstratumId
  lateinit var substratumIdB: SubstratumId

  lateinit var siteHistoryId: PlantingSiteHistoryId
  lateinit var stratumHistoryIdA: StratumHistoryId
  lateinit var stratumHistoryIdB: StratumHistoryId
  lateinit var substratumHistoryIdA1: SubstratumHistoryId
  lateinit var substratumHistoryIdA2: SubstratumHistoryId
  lateinit var substratumHistoryIdB: SubstratumHistoryId

  lateinit var otherSiteId: PlantingSiteId
  lateinit var otherStratumId: StratumId
  lateinit var otherSubstratumId: SubstratumId

  lateinit var otherSiteHistoryId: PlantingSiteHistoryId
  lateinit var otherStratumHistoryId: StratumHistoryId
  lateinit var otherSubstratumHistoryId: SubstratumHistoryId

  @BeforeEach
  fun setUpSimplifiedSites() {
    every { geometrySimplifier.simplify(match { siteBoundary.equalsOrBothNull(it) }) } returns
        simplifiedSiteBoundary
    every { geometrySimplifier.simplify(match { exclusion.equalsOrBothNull(it) }) } returns
        simplifiedExclusion
    every { geometrySimplifier.simplify(match { stratumBoundaryA.equalsOrBothNull(it) }) } returns
        simplifiedStratumBoundaryA
    every { geometrySimplifier.simplify(match { stratumBoundaryB.equalsOrBothNull(it) }) } returns
        simplifiedStratumBoundaryB
    every {
      geometrySimplifier.simplify(match { substratumBoundaryA1.equalsOrBothNull(it) })
    } returns simplifiedSubstratumBoundaryA1
    every {
      geometrySimplifier.simplify(match { substratumBoundaryA2.equalsOrBothNull(it) })
    } returns simplifiedSubstratumBoundaryA2
    every {
      geometrySimplifier.simplify(match { substratumBoundaryB.equalsOrBothNull(it) })
    } returns simplifiedSubstratumBoundaryB

    siteId =
        insertPlantingSite(boundary = siteBoundary, exclusion = exclusion, insertHistory = false)
    siteHistoryId = insertPlantingSiteHistory()

    stratumIdA = insertStratum(boundary = stratumBoundaryA, insertHistory = false)
    stratumHistoryIdA = insertStratumHistory()

    substratumIdA1 = insertSubstratum(boundary = substratumBoundaryA1, insertHistory = false)
    substratumHistoryIdA1 = insertSubstratumHistory()
    substratumIdA2 = insertSubstratum(boundary = substratumBoundaryA2, insertHistory = false)
    substratumHistoryIdA2 = insertSubstratumHistory()

    stratumIdB = insertStratum(boundary = stratumBoundaryB, insertHistory = false)
    stratumHistoryIdB = insertStratumHistory()
    substratumIdB = insertSubstratum(boundary = substratumBoundaryB, insertHistory = false)
    substratumHistoryIdB = insertSubstratumHistory()

    otherSiteId = insertPlantingSite(boundary = otherBoundary, insertHistory = false)
    otherSiteHistoryId = insertPlantingSiteHistory()
    insertSimplifiedPlantingSite(boundary = otherSimplifiedBoundary)
    insertSimplifiedPlantingSiteHistory(boundary = otherSimplifiedBoundary)
    otherStratumId = insertStratum(boundary = otherBoundary, insertHistory = false)
    otherStratumHistoryId = insertStratumHistory()
    insertSimplifiedStratum(boundary = otherSimplifiedBoundary)
    insertSimplifiedStratumHistory(boundary = otherSimplifiedBoundary)
    otherSubstratumId = insertSubstratum(boundary = otherBoundary, insertHistory = false)
    otherSubstratumHistoryId = insertSubstratumHistory()
    insertSimplifiedSubstratum(boundary = otherSimplifiedBoundary)
    insertSimplifiedSubstratumHistory(boundary = otherSimplifiedBoundary)

    insertPlantingSite(name = "Non-simplified site")
    insertStratum()
    insertSubstratum()
  }

  @Test
  fun `simplifies and inserts new row for simplified planting site`() {
    store.upsertSimplifiedPlantingSite(siteId)
    assertSimplifiedSiteCorrect()
  }

  @Test
  fun `simplifies and inserts new row for simplified planting site history`() {
    store.upsertSimplifiedPlantingSiteHistory(siteId, siteHistoryId)
    assertSimplifiedSiteHistoryCorrect()
  }

  @Test
  fun `overwrites existing simplified planting site rows`() {
    insertSimplifiedPlantingSite(plantingSiteId = siteId, boundary = otherSimplifiedBoundary)
    insertSimplifiedStratum(stratumId = stratumIdB, boundary = otherSimplifiedBoundary)
    insertSimplifiedSubstratum(substratumId = substratumIdB, boundary = otherSimplifiedBoundary)

    store.upsertSimplifiedPlantingSite(siteId)
    assertSimplifiedSiteCorrect()
  }

  @Test
  fun `overwrites existing simplified planting site histories rows`() {
    insertSimplifiedPlantingSiteHistory(
        plantingSiteHistoryId = siteHistoryId,
        boundary = otherSimplifiedBoundary,
    )
    insertSimplifiedStratumHistory(
        stratumHistoryId = stratumHistoryIdB,
        boundary = otherSimplifiedBoundary,
    )
    insertSimplifiedSubstratumHistory(
        substratumHistoryId = substratumHistoryIdB,
        boundary = otherSimplifiedBoundary,
    )

    store.upsertSimplifiedPlantingSiteHistory(siteId, siteHistoryId)
    assertSimplifiedSiteHistoryCorrect()
  }

  private fun assertSimplifiedSiteCorrect() {
    assertTableEquals(
        listOf(
            SimplifiedPlantingSitesRecord(plantingSiteId = siteId),
            SimplifiedPlantingSitesRecord(plantingSiteId = otherSiteId),
        ),
        message = "Simplified Sites Table",
    ) {
      SimplifiedPlantingSitesRecord(
          plantingSiteId = it.plantingSiteId,
          boundary = null,
          exclusion = null,
      )
    }

    assertTableEquals(
        listOf(
            SimplifiedStrataRecord(stratumId = stratumIdA),
            SimplifiedStrataRecord(stratumId = stratumIdB),
            SimplifiedStrataRecord(stratumId = otherStratumId),
        ),
        message = "Simplified Strata Table",
    ) {
      SimplifiedStrataRecord(it.stratumId, boundary = null)
    }

    assertTableEquals(
        listOf(
            SimplifiedSubstrataRecord(substratumId = substratumIdA1),
            SimplifiedSubstrataRecord(substratumId = substratumIdA2),
            SimplifiedSubstrataRecord(substratumId = substratumIdB),
            SimplifiedSubstrataRecord(substratumId = otherSubstratumId),
        ),
        message = "Simplified Substrata Table",
    ) {
      SimplifiedSubstrataRecord(it.substratumId, boundary = null)
    }

    val simplifiedSiteRecord = simplifiedPlantingSitesDao.fetchOneByPlantingSiteId(siteId)!!
    assertGeometryEquals(simplifiedSiteBoundary, simplifiedSiteRecord.boundary, "Site boundary")
    assertGeometryEquals(simplifiedExclusion, simplifiedSiteRecord.exclusion, "Site exclusion")

    val simplifiedStratumA = simplifiedStrataDao.fetchOneByStratumId(stratumIdA)!!
    assertGeometryEquals(
        simplifiedStratumBoundaryA,
        simplifiedStratumA.boundary,
        "Stratum A boundary",
    )
    val simplifiedStratumB = simplifiedStrataDao.fetchOneByStratumId(stratumIdB)!!
    assertGeometryEquals(
        simplifiedStratumBoundaryB,
        simplifiedStratumB.boundary,
        "Stratum B boundary",
    )

    val simplifiedSubstratumA1 = simplifiedSubstrataDao.fetchOneBySubstratumId(substratumIdA1)!!
    assertGeometryEquals(
        simplifiedSubstratumBoundaryA1,
        simplifiedSubstratumA1.boundary,
        "Substratum A1 boundary",
    )
    val simplifiedSubstratumA2 = simplifiedSubstrataDao.fetchOneBySubstratumId(substratumIdA2)!!
    assertGeometryEquals(
        simplifiedSubstratumBoundaryA2,
        simplifiedSubstratumA2.boundary,
        "Substratum A2 boundary",
    )
    val simplifiedSubstratumB = simplifiedSubstrataDao.fetchOneBySubstratumId(substratumIdB)!!
    assertGeometryEquals(
        simplifiedSubstratumBoundaryB,
        simplifiedSubstratumB.boundary,
        "Substratum B boundary",
    )

    val otherSiteRecord = simplifiedPlantingSitesDao.fetchOneByPlantingSiteId(otherSiteId)!!
    val otherStratumRecord = simplifiedStrataDao.fetchOneByStratumId(otherStratumId)!!
    val otherSubstratumRecord = simplifiedSubstrataDao.fetchOneBySubstratumId(otherSubstratumId)!!

    assertGeometryEquals(
        otherSimplifiedBoundary,
        otherSiteRecord.boundary,
        "Unchanged simplified site boundary",
    )

    assertGeometryEquals(
        otherSimplifiedBoundary,
        otherStratumRecord.boundary,
        "Unchanged simplified stratum boundary",
    )

    assertGeometryEquals(
        otherSimplifiedBoundary,
        otherSubstratumRecord.boundary,
        "Unchanged simplified substratum boundary",
    )
  }

  fun assertSimplifiedSiteHistoryCorrect() {
    assertTableEquals(
        listOf(
            SimplifiedPlantingSiteHistoriesRecord(plantingSiteHistoryId = siteHistoryId),
            SimplifiedPlantingSiteHistoriesRecord(plantingSiteHistoryId = otherSiteHistoryId),
        ),
        message = "Simplified Site Histories Table",
    ) {
      SimplifiedPlantingSiteHistoriesRecord(
          plantingSiteHistoryId = it.plantingSiteHistoryId,
          boundary = null,
          exclusion = null,
      )
    }

    assertTableEquals(
        listOf(
            SimplifiedStratumHistoriesRecord(stratumHistoryId = stratumHistoryIdA),
            SimplifiedStratumHistoriesRecord(stratumHistoryId = stratumHistoryIdB),
            SimplifiedStratumHistoriesRecord(stratumHistoryId = otherStratumHistoryId),
        ),
        message = "Simplified Stratum Histories Table",
    ) {
      SimplifiedStratumHistoriesRecord(stratumHistoryId = it.stratumHistoryId, boundary = null)
    }

    assertTableEquals(
        listOf(
            SimplifiedSubstratumHistoriesRecord(substratumHistoryId = substratumHistoryIdA1),
            SimplifiedSubstratumHistoriesRecord(substratumHistoryId = substratumHistoryIdA2),
            SimplifiedSubstratumHistoriesRecord(substratumHistoryId = substratumHistoryIdB),
            SimplifiedSubstratumHistoriesRecord(substratumHistoryId = otherSubstratumHistoryId),
        ),
        message = "Simplified Substratum Histories Table",
    ) {
      SimplifiedSubstratumHistoriesRecord(
          substratumHistoryId = it.substratumHistoryId,
          boundary = null,
      )
    }

    val simplifiedSiteHistoryRecord =
        simplifiedPlantingSiteHistoriesDao.fetchOneByPlantingSiteHistoryId(siteHistoryId)!!
    assertGeometryEquals(
        simplifiedSiteBoundary,
        simplifiedSiteHistoryRecord.boundary,
        "Site history boundary",
    )
    assertGeometryEquals(
        simplifiedExclusion,
        simplifiedSiteHistoryRecord.exclusion,
        "Site history exclusion",
    )

    val simplifiedStratumA =
        simplifiedStratumHistoriesDao.fetchOneByStratumHistoryId(stratumHistoryIdA)!!
    assertGeometryEquals(
        simplifiedStratumBoundaryA,
        simplifiedStratumA.boundary,
        "Stratum history A boundary",
    )
    val simplifiedStratumB =
        simplifiedStratumHistoriesDao.fetchOneByStratumHistoryId(stratumHistoryIdB)!!
    assertGeometryEquals(
        simplifiedStratumBoundaryB,
        simplifiedStratumB.boundary,
        "Stratum history B boundary",
    )

    val simplifiedSubstratumA1 =
        simplifiedSubstratumHistoriesDao.fetchOneBySubstratumHistoryId(substratumHistoryIdA1)!!
    assertGeometryEquals(
        simplifiedSubstratumBoundaryA1,
        simplifiedSubstratumA1.boundary,
        "Substratum history A1 boundary",
    )
    val simplifiedSubstratumA2 =
        simplifiedSubstratumHistoriesDao.fetchOneBySubstratumHistoryId(substratumHistoryIdA2)!!
    assertGeometryEquals(
        simplifiedSubstratumBoundaryA2,
        simplifiedSubstratumA2.boundary,
        "Substratum history A2 boundary",
    )
    val simplifiedSubstratumB =
        simplifiedSubstratumHistoriesDao.fetchOneBySubstratumHistoryId(substratumHistoryIdB)!!
    assertGeometryEquals(
        simplifiedSubstratumBoundaryB,
        simplifiedSubstratumB.boundary,
        "Substratum history B boundary",
    )

    val otherSiteRecord =
        simplifiedPlantingSiteHistoriesDao.fetchOneByPlantingSiteHistoryId(otherSiteHistoryId)!!
    val otherStratumRecord =
        simplifiedStratumHistoriesDao.fetchOneByStratumHistoryId(otherStratumHistoryId)!!
    val otherSubstratumRecord =
        simplifiedSubstratumHistoriesDao.fetchOneBySubstratumHistoryId(otherSubstratumHistoryId)!!

    assertGeometryEquals(
        otherSimplifiedBoundary,
        otherSiteRecord.boundary,
        "Unchanged simplified site boundary",
    )

    assertGeometryEquals(
        otherSimplifiedBoundary,
        otherStratumRecord.boundary,
        "Unchanged simplified stratum boundary",
    )

    assertGeometryEquals(
        otherSimplifiedBoundary,
        otherSubstratumRecord.boundary,
        "Unchanged simplified substratum boundary",
    )
  }
}
