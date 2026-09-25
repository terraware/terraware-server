# Testing Survival Rates

Three test styles cover survival rates. Pick by what you are exercising. Run any of them with a
single Gradle invocation; the whole tracking package takes about a minute:

```
./gradlew test --tests "com.terraformation.backend.tracking.*"
```

Never derive an expected value from a test's actual output. Compute it by hand from the inputs
using the formula in SKILL.md, then compare.

## 1. Kotlin unit tests: `ObservationStoreSurvivalRateCalculationTest`

Path: `src/test/kotlin/com/terraformation/backend/tracking/db/observationStore/`. Extends
`ObservationScenarioTest`, which provides a planting site with one stratum, one substratum, one
permanent plot, and one observation as inherited fixtures: `plantingSiteId`, `stratumId`,
`substratumId`, `plotId`, `observationId`, `observedTime`, `user`.

Building blocks:

- `insertPlotT0Density(speciesId, monitoringPlotId = plotId, plotDensity = BigDecimal.valueOf(n).toPlantsPerHectare())`
- `insertStratumT0TempDensity(stratumId, speciesId, stratumDensity)`
- `insertMonitoringPlot(permanentIndex = 2)` for another permanent plot; omit `permanentIndex` for a
  temporary plot. Follow with `insertObservationPlot(claimedBy = user.userId, isPermanent = ...)`.
- `insertObservationRequestedSubstratum()` after inserting a new substratum, or the completion
  path will not attribute its plots to the observation.
- `createPlantsRows(mapOf(speciesA to 40, speciesB to 30), RecordedPlantStatus.Live)` accepts
  several species.
- `observationStore.completePlot(observationId, plotId, emptySet(), "Notes", observedTime, plants)`
- To include temp plots, update `PLANTING_SITES.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS` for the site or
  use `insertPlantingSite(survivalRateIncludesTempPlots = true)`.

Asserting with `assertSurvivalRates(SurvivalRates(plotRates, substratumRates, stratumRates, siteRates), message)`:

- Each argument maps an id to a map of `SpeciesId?` to expected rate. A `null` species key is the
  aggregate all-species rate. Doubles are rounded to integers before comparison, so write
  `100.0 * 40 / 50` rather than `80`.
- **To assert that an aggregate rate is null, omit the null key entirely.** The actual map built
  from the results never contains a null key when the aggregate is null, and every expected key
  must be present in the actual map. `mapOf(speciesId to rate)` with no null key means "species
  rate is `rate`, aggregate is null".
- A per-species null is `speciesId to null`.
- The helper checks both the species totals (through `ObservationResultsStoreV2`) and the raw
  results tables. The results-table check only runs for levels whose expected map has a null key.

`runSurvivalRateScenario("/tracking/observation/SurvivalRate...", numSpecies, changeFunction)`
drives the CSV-based survival rate scenarios (style 3 below) from this test class.

## 2. Kotlin scenario DSL

Used by `ObservationStoreUpdateMonitoringSpeciesTest` and other observation store tests. Classes
live in `src/test/kotlin/com/terraformation/backend/tracking/scenario/`.

```kotlin
scenario {
  siteCreated { stratum(1) { substratum(1) { plot(1) } } }
  t0DensitySet { plot(1) { species(0, density = 100) } }   // or plot(1) { observation(1) }
  observation(1) { plot(1) { species(0, live = 5, dead = 1, existing = 2) } }
  expectResults(observation = 1) {
    survivalRate(percent(5, 100))
    stratum(1) { substratum(1) { plot(1) { survivalRate(5) } } }
  }
}
```

`t0DensitySet { plot(n) { observation(m) } }` assigns t0 from an observation through the real
`T0Store.assignT0PlotObservation`, so zero-density rows appear exactly as in production.
`species(OTHER, ...)` and `species(UNKNOWN, ...)` record non-known plants; they never count toward
survival rates.

## 3. CSV scenarios: `src/test/resources/tracking/observation/<Scenario>/`

Two families share the site definition files `Strata.csv`, `Substrata.csv`, and `Plots.csv`
(`Substratum,Name,Type` with `Permanent` or `Temporary`).

**Observation results scenarios**, run by `ObservationScenarioV2Test` through
`importFromCsvFiles(prefix, numObservations, sizeMeters)`:

- Inputs: `Plants-1.csv`, `Plants-2.csv`, ... with `Plot,Certainty,Species,Status,x,y` per plant.
- Expected: `PlotStats.csv`, `PlotStatsPerSpecies.csv`, `SubstratumStats.csv`,
  `SubstratumStatsPerSpecies.csv`, `StratumStats.csv`, `StratumStatsPerSpecies.csv`,
  `SiteStats.csv`, `SiteStatsPerSpecies.csv`, `OverallStats.csv`. Rates are written like `67%`; a
  null is an empty cell. The assertion compares a subset of columns, so match a failure message's
  column positions against the header row before editing.
- t0 derivation: if the folder has no `T0Densities.csv`, the importer (`importPlantsCsv` in
  `ObservationScenarioTest.kt`) assigns t0 from observation 1 to every permanent plot observed in
  it. Each known species observed in the plot gets a row whose density is its live plus dead count
  in observation 1, so a species with only Existing plants gets density 0. Other and Unknown plants
  never get rows. Plots first observed later, and species first observed later, have no t0 row.
  This mirrors `T0Store.assignT0PlotObservation` but not `assignNewObservationSpeciesZero`, so a
  species first seen in a later observation is excluded here where production would give it a
  zero-density row and include it. Use an explicit `T0Densities.csv` when a scenario depends on
  that. The same logic appears twice in the file (`importPlantsCsv` and `importObservationsCsv`),
  so change both.

**Survival rate scenarios** (`SurvivalRate*` folders), run by `runSurvivalRateScenario`:

- Inputs: `T0Densities.csv` (`Plot,Species 0 Density,Species 1 Density,...`, plants per hectare
  before conversion), optional `T0StratumDensities.csv` for temp plots, and `Observation-1.csv`
  with per-species Existing/Live/Dead counts per plot.
- Expected: `PlotRates.csv`, `SubstratumRates.csv`, `StratumRates.csv`, `SiteRates.csv`, with the
  aggregate rate first and then one column per species. An optional `AfterUpdate/` folder holds
  the expectations after the scenario's change function runs.

**Companion spreadsheets.** Some of these scenario folders have a corresponding Google Sheet in
which the expected values are worked out from the inputs. The sheets are maintained by the team and
are not linked from the repo. When a scenario's expectations change, compute the new values yourself
from the scenario inputs and update the CSV files, then tell the user which scenarios and cells
changed so they can update the corresponding sheets and compare them against your results. Do not
ask for the sheets in order to make the change.

## Which style to use

- A rule about which plots or species count, or a null rule: Kotlin unit test in style 1. It is
  the most direct and its helper checks both table families.
- A behavior involving observation edits, t0 reassignment, or Other/Unknown species: style 2.
- A multi-observation site with roll-forward, geometry changes, or many plots: style 3, because
  the expected values for every level are visible side by side.