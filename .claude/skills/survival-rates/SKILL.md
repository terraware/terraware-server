---
name: survival-rates
description: Use when working on monitoring observation survival rates in terraware-server, including changing or debugging how survival rates are calculated, t0 (baseline) plant densities for plots or strata, the observed species totals or observation results tables, temporary plot inclusion, survival rate recalculation jobs, or writing survival rate tests and CSV scenarios.
---

# Survival Rates

## Overview

A survival rate is the percentage of planted trees still alive since a baseline ("t0") point. It
is computed in SQL inside `ObservationStore` and stored in database tables; nothing computes it at
read time. The one principle behind every rule below: **the numerator and the denominator must be
aggregated over exactly the same set of (monitoring plot, species) pairs, and that set is the pairs
that have t0 data.** A plot or species without t0 data contributes to neither side.

Read this whole file before changing anything. Several rules are product decisions that cannot be
inferred from the code. This document describes current behavior only; it does not record how the
calculation used to work. For test infrastructure, see [testing.md](testing.md).

## Vocabulary

| Term                  | Meaning                                                                                                                                                                                                                                                                                                                                                                                               |
|-----------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| t0 density            | Baseline plants per hectare for one species, either per permanent plot (`plot_t0_densities`) or per stratum for temporary plots (`stratum_t0_temp_densities`).                                                                                                                                                                                                                                        |
| t0 observation        | The observation a permanent plot's t0 densities were derived from (`plot_t0_observations`). Not every plot has one; densities can also be entered manually.                                                                                                                                                                                                                                           |
| Permanent plot        | A monitoring plot included in each observation of the substratum to which it belongs (`monitoring_plots.permanent_index` is set). Only permanent plots have their own t0 densities. Permanence belongs to the plot but can change over time, so each observation records whether the plot was permanent at the time (`observation_plots.is_permanent`), and the calculation uses that recorded value. |
| Temporary plot        | A plot chosen fresh for an observation. It takes its t0 density from its stratum, and only if the planting site has `survival_rate_includes_temp_plots` set.                                                                                                                                                                                                                                          |
| Scope                 | The level a rate is computed for: plot, substratum, stratum, or planting site.                                                                                                                                                                                                                                                                                                                        |
| Species totals tables | `observed_{plot,substratum,stratum,site}_species_totals`: one row per observation, scope, and species. Hold per-species live/dead/existing counts and a per-species `survival_rate`.                                                                                                                                                                                                                  |
| Results tables        | `observation_{plot,substratum,stratum,site}_results`: one row per observation and scope, all species combined. Hold the aggregate `survival_rate`, plus `survival_rate_std_dev` and `survival_rate_area` above plot level.                                                                                                                                                                            |
| Roll-forward          | When an observation does not cover a substratum, the substratum's latest earlier observation stands in for it at stratum and site level, recorded in `observation_dependent_substrata`.                                                                                                                                                                                                               |
| `HECTARES_PER_PLOT`   | 0.09. Plots are 30 m squares. A denominator is the sum of t0 densities times this constant, so it is in plants, matching the live count.                                                                                                                                                                                                                                                              |

## The Calculation

```
survival_rate = live_plants_in_t0_pairs * 100 / (sum(t0_density over t0_pairs) * HECTARES_PER_PLOT)
```

- **Live only.** Dead plants never count. Existing plants (already there before planting) never
  count in either the numerator or a t0 density.
  - Technically dead plants can be used for determining the t0 density, but once that is set, dead
    plants do not factor in to the survival rate calculation.
- **Known species only.** t0 densities exist only for known species. Plants recorded with Other or
  Unknown certainty never have t0 data, so they never appear in any survival rate numerator,
  including the all-species aggregate.
- **Per (plot, species).** A permanent plot's species counts only if `plot_t0_densities` has a row
  for that plot and species. A temporary plot's species counts only if its stratum has a
  `stratum_t0_temp_densities` row for that species and the site includes temp plots.
- **Zero-density rows are t0 data.** A row with density 0 puts the species in the plot set, so its
  live plants count in the numerator while adding nothing to the denominator. Rates above 100% are
  therefore normal and expected, not bugs.
- **Integer percent.** Postgres rounds the numeric result when storing it in the integer column.
- **Units.** Densities are stored in plants per hectare, so a plot that had 50 plants at t0 is
  stored as 50 / 0.09, and the denominator multiplies back by `HECTARES_PER_PLOT` to get plants.
  When computing by hand from a plant count, the rate is simply live plants over t0 plants. The test
  helper `BigDecimal.toPlantsPerHectare()` does the conversion from a per-plot count.
- **Plot must be completed.** A plot joins the set only if it has a completed observation whose
  permanence matches the set (permanent set for permanent plots, temp set for temp plots), judged
  by the plot's most recently completed observation. During plot completion, the plot being
  completed counts as completed via `alternateCompletedCondition`.

### Where zero-density rows come from

Production writes t0 rows with density 0 in several cases, all in `T0Store`:

- `assignT0PlotObservation`: every known species in the t0 observation's plot totals gets a row,
  including species whose only plants were Existing (count 0). Species withdrawn to the substratum
  but not observed, and species observed in other completed observations, also get 0.
- `assignNewObservationSpeciesZero`, on `ObservationStateUpdatedEvent` to Completed or Abandoned:
  species first seen in a later observation of a plot that has a t0 observation get 0.

So in production, "species has no t0 row" is uncommon for plots with a t0 observation. It mostly
arises for plots with no t0 at all, or manually entered t0 that omits a species.

## Rules You Cannot Infer From the Code

These are product decisions. Do not "fix" them without checking with the user.

1. **Null rule (results tables only).** If any plot observed in an observation has no t0 row,
   that substratum's aggregate rate is null. A t0 density of zero counts as t0 data. Permanent plots always
   count toward this check, using `plot_t0_densities`. Temporary plots count toward it only if the
   site has `survival_rate_includes_temp_plots` set, using their stratum's
   `stratum_t0_temp_densities`. A stratum's aggregate rate is null if any of its substrata's
   latest result rows at or before the observation (found through
   `observation_dependent_substrata`, the same rows its totals roll forward from) has a null rate
   and either the site includes temp plots or that substratum result had permanent plots. So a
   null substratum survival rate propagates upwards to the stratum's survival rate in later
   observations that skip the substratum, until it is observed again with a rate. A rolled-
   forward substratum that has since been deleted from the site is ignored, matching the
   rolled-forward totals. The site rate is null if any stratum's latest rate is null. Per-
   species rates are never nulled by this rule; they simply exclude the plot. Implemented
   by `anyChildHasNullSurvivalRateCondition` in `ObservationResultsScope.kt`.

2. **Excluding plots without t0 is intentional.** The plot set has one row per plot and species
   with t0 data, so a species with no t0 density in a plot contributes neither its live plants nor
   a density, while a species with a t0 density of 0 still contributes its live plants. The
   numerator is derived from the same plot set as the denominator, never from the stored
   `total_live` or `permanent_live` columns. Those
   columns count every completed plot regardless of t0 data and exist for the API and search; using
   either as a survival rate numerator inflates the rate.

3. **A zero denominator stores 0.** When every t0 density in the set is zero, every path stores a
   rate of 0, in both the species totals and results tables. A rate is null only when there is no
   t0 data at all.

4. **The site aggregate rate is not numerator over denominator.** In the results tables the site
   rate is the area-weighted average of its strata's rates, weighting each stratum by its
   `survival_rate_area` (the area of substrata observed at or before the observation). See
   `ObservationResultsSite.survivalRateValue`. Standard deviations at substratum and stratum level
   are computed across plot rates weighted by plot planting density.

5. **Temp plots are opt-in per site.** `planting_sites.survival_rate_includes_temp_plots`
   controls whether temporary plots count at all. Changing it fires
   `SurvivalRateIncludesTempPlotsChangedEvent`, which flags the whole site for recalculation.

## Plot Attribution

Every rate needs to know which observation a plot's live counts come from.
`latestObservationForPlotCondition`, built on `latestObservationForSubstratumField`, attributes each
plot to its substratum's latest observation at or before the target, which is how the live totals
are rolled up. It reads `observation_dependent_substrata`, so `completePlot` marks the plot complete
and runs `recordSubstratumDependencies` before it writes the plot's species totals.

`latestObservationForPlotCondition` feeds `permanentT0PlotSet` and `tempT0PlotSet` in
`SurvivalRateTerms.kt`, which define the plot set once. `getSurvivalRateTerms` derives the numerator
and denominator from those sets for SQL expressions; `getSurvivalRateTermsBySpecies` does the same
as a grouped query for the completion path, which then writes all species' rates with one `CASE`
update.

## When Rates Are Recalculated

Stored rates go stale whenever their inputs change. Changes never recalculate anything directly.
Instead, the code that changes an input sets `needs_recalculation` on the affected
`observation_*_results` rows, in the same transaction as the change, and
`ObservationResultsRecalculator` recalculates them. When adding a new way to change observation data
or t0 data, call `ObservationResultsInvalidator` from inside the transaction that makes the
change.

The only values written synchronously are raw data: `recorded_plants`, `observation_plots`, the
t0 tables, and the counts in `observed_plot_species_totals`. Plot-level species totals counts are
source data, because species count edits are applied to them directly rather than to
`recorded_plants`. Everything derived from them is written only by the recalculation job: plot
species survival rates, the substratum, stratum, and site species totals, and all the results
tables.

A flagged results row covers that row and all the species totals rows for the same observation
and scope. When a results row doesn't exist yet, the invalidator inserts a placeholder with zero
counts so there is somewhere to put the flag. The API reports flagged rows as `pending`, and the
survival rate calculation in-progress endpoint is true while any of a site's results are flagged.
Publishing a funder activity fails with `ObservationResultsPendingException` if any of its
observations' site results are flagged.
`POST /api/v1/tracking/sites/{id}/completeSurvivalRateCalculation` recalculates a site's flagged
results synchronously through `ObservationResultsRecalculator.completeSiteRecalculation`, waiting
for any recalculation that is already running. Clients use it to let users wait for pending
numbers, and it is handy when testing.

| Trigger                                                             | What gets flagged                                                                                                                                                                                              |
|---------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| A plot is completed                                                 | `completePlot` writes plot species totals and calls `invalidateObservationPlots` for that observation and plot, which also flags later observations that roll it forward. Earlier observations are not flagged, so a later completion never changes an earlier observation. |
| An observation is completed or abandoned                            | `completeObservation` and `abandonObservation` call `invalidateObservation`.                                                                                                                                   |
| A plot's species counts are edited, or an Other species is merged   | `updateMonitoringSpecies` and `mergeOtherSpeciesForMonitoring` adjust plot species totals and call `invalidateObservationPlots`.                                                                                |
| An edited observation is a plot's t0 observation                    | `T0Store.on(MonitoringSpeciesTotalsEditedEvent)` re-derives that plot's t0 densities and publishes `T0PlotDataAssignedEvent`.                                                                                  |
| t0 data is assigned for a plot                                      | `ObservationResultsInvalidator.on(T0PlotDataAssignedEvent)` calls `invalidatePlot`, which flags the plot in every observation where it was completed.                                                         |
| t0 data is assigned for a stratum                                   | `on(T0StratumDataAssignedEvent)` calls `invalidateStratum`.                                                                                                                                                    |
| The temp-plot flag changes                                          | `on(SurvivalRateIncludesTempPlotsChangedEvent)` calls `invalidateSite`. The event is published inside the transaction that changes the setting.                                                                |
| An observation is deleted or merged into another                    | `ObservationService.deleteObservation` and `mergeObservations` call `invalidateSite`.                                                                                                                          |
| An admin requests it                                                | `POST /admin/recalculateSurvivalRates` flags one observation, one site, or every site, then recalculates them immediately rather than waiting for the job. Use this to correct stored data after deploying a calculation change. |

Editing a site's map doesn't flag anything, because each observation's results use the site's
geometry at the time of that observation. Active observations in edited strata are abandoned,
which flags them like any other abandonment.

`ObservationResultsRecalculator` is a JobRunr recurring job that runs every 5 minutes. For each
planting site with flagged results, in a new REPEATABLE READ transaction holding a per-site
advisory lock, it calls `ObservationRecalculationStore.recalculateFlaggedResults`, then clears
the flags. That recalculates only the flagged rows, one level at a time (plots, substrata, strata,
then the site), with one set-based statement per step, so each level reads only levels that are
already recalculated. The snapshot means a recalculation never sees a change that lands while it
runs; such a change conflicts with the recalculation's writes, the recalculation rolls back, and
the flags stay set for the next run.

## Code Map

All paths below are under `src/main/kotlin/com/terraformation/backend/`.

**Calculation, `tracking/db/ObservationStore.kt`**

| Function                                                                                                         | Role                                                                                                                                                                                                                                                        |
|------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `completePlot`                                                                                                   | Entry point when a plot is completed. Writes plot species totals, marks the plot complete, runs `recordSubstratumDependencies`, and flags results. On the last plot, `completeObservation`.                                                                |
| `ObservationRecalculationStore.recalculateFlaggedResults` | Called by the recalculation job. Recalculates a site's flagged results rows and their species totals, level by level. Plot species totals counts are the source; everything above is derived from them. |
| `ObservationSpeciesPlotRow`, `ObservationResultsPlotRow`, and scopes built from `DSL.select(<table column>)` | Scopes that refer to the row being updated rather than a fixed scope, so one statement can calculate rates for every flagged row at a level. |
| `updateSpeciesTotalsTable`                                                                                       | Incremental per-species counts for one scope, then survival rates for all species in one update.                                                                                                                                                            |
| `updateObservationResults`, `updatePlotObservationResults` | Sum species totals into the results tables' count and density columns for one plot's scopes. Only used when merging observations. |
| `T0PlotSet`, `permanentT0PlotSet`, `tempT0PlotSet`, `getSurvivalRateTerms` (in `SurvivalRateTerms.kt`), `getSurvivalRateTermsBySpecies` | The shared plot-set definition and the terms derived from it. Change these to change what counts. |
| `plotHasCompletedObservations` (in `SurvivalRateTerms.kt`) | Completed-plot and permanence check used by the plot sets. |
| `getSurvivalRateWeightedStandardDeviation` (in `SurvivalRateTerms.kt`) | Std dev across plot results. |
| `updateMonitoringSpecies`                                                                                        | Edit path for a plot's species counts in a completed observation. Adjusts plot species totals and flags results.                                                                                                                                            |

**Recalculation**

| Class                                                  | Role                                                                                                                                                         |
|--------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `tracking/db/ObservationResultsInvalidator.kt`         | Flags results rows for recalculation, following `observation_dependent_substrata` to later observations. Also listens for the t0, temp-plot, and map events. |
| `tracking/ObservationResultsRecalculator.kt`           | Recurring job that recalculates flagged results, one planting site per REPEATABLE READ transaction.                                                              |

**Scopes, `tracking/util/`**

`ObservationSpeciesScope.kt` (species totals tables) and `ObservationResultsScope.kt` (results
tables, extends the former) have one class per scope level. They supply the table, the row
condition, `t0DensityCondition` and `tempStratumCondition` for the plot sets,
`alternateCompletedCondition` for the in-progress plot, and on the results side
`anyChildHasNullSurvivalRateCondition`, `survivalRateValue`, `survivalRateAreaValue`, and
`latestPlotResultsCondition`. A rule that applies to one level lives in that level's class.

**Shared queries, `tracking/db/Queries.kt`**

`latestObservationForSubstratumField`, `latestObservationForStratumField`,
`observationIdForPlot`, `substratumObservedAtOrBefore`.

**t0 data, `tracking/db/T0Store.kt`**

`assignT0PlotObservation`, `assignT0PlotSpeciesDensities`, `assignT0TempStratumSpeciesDensities`,
`assignNewObservationSpeciesZero`, `fetchAllT0SiteDataSet`. Editing a plot's species counts in an
observation that is its t0 observation re-derives its t0 (`on(MonitoringSpeciesTotalsEditedEvent)`).

**Admin and API**

- `admin/AdminPlantingSitesController.kt`: `POST /admin/recalculateSurvivalRates` for one
  observation, one site, or every site.
- `admin/AdminSurvivalRatesController.kt` and `SurvivalRatesPageModel.kt`: an admin page showing
  various values related to calculating a survival rate and the aggregate values. Useful when
  debugging a real site.
- `tracking/api/PlantingSitesController.kt`, `T0Controller.kt`, `MonitoringResultsPayloads.kt`:
  the user-facing API.

**Read side**

`tracking/db/ObservationResultsStoreV2.kt` with `ObservationMultisets.kt` reads the stored rates
into `ObservationResultsModel`. `TrackingStatsStore.getSurvivalRate` aggregates the latest stratum
results, area-weighted, for a site, project, or organization. `search/table/Observation*ResultTable`
expose results columns to search. `funder/db/PublishedActivityStore` and
`accelerator/db/ReportStore` also read results tables.

**Schema documentation**

Column comments live in `src/main/resources/db/migration/R__Comments.sql`. Update them when a
column's meaning changes.

## Recipes

### Change what counts toward a survival rate

1. Confirm the rule with the user against the list above; most "obvious fixes" here are policy.
2. Make the change in the plot sets or `SurvivalRateTermFields`, not in individual call sites. If
   it cannot be expressed there, you are about to break the same-plot-set principle.
3. Check all four write paths still agree: completion (`updateSpeciesTotalsTable`), species
   recalculation, roll-forward, and results. The results site rate is derived from strata and
   usually needs nothing.
4. Update the doc comments in `ObservationResultsModel.kt` and `R__Comments.sql`.
5. Run the tracking tests (see testing.md). Expect CSV scenario expectations to move; recompute
   each moved cell by hand from the scenario inputs before accepting it.
6. Note in the PR that existing data needs the admin recalculation endpoint run after deploy.

### Change how t0 data is assigned

1. Work in `T0Store`. Remember the zero-density conventions above; the survival rate code treats
   a zero row as t0 data.
2. Publish or reuse the events so the affected results are flagged. The events must be published
   inside the transaction that changes the t0 data, so the flags commit with the change.
3. If the test scenario importer's t0 derivation should mirror your change, update both copies of
   the counting logic in `ObservationScenarioTest.kt` (see testing.md).

### Debug a wrong or null rate for a real site

Work down this list; most reports are one of these.

1. Does the (plot, species) have a t0 row? Zero counts as a row. No row means the species is out.
2. Was the plot recorded as permanent in its most recently completed observation? A plot whose
   permanence has changed moves between the permanent and temp sets from that observation on.
3. Is the site's `survival_rate_includes_temp_plots` flag what the reporter expects?
4. Is the aggregate null while per-species rates are populated? That is the null rule; find the
   permanent plot without t0 densities, or, if the site includes temp plots, the temp plot whose
   stratum has no t0 temp densities.
5. Is the observation complete? Results table rates are only written once every plot in scope is
   completed or marked not observed.
6. Is a substratum missing from the observation? Its numbers roll forward from its latest earlier
   observation; check `observation_dependent_substrata`.
7. Is a recalculation pending? Check `needs_recalculation` on the observation's results rows. If
   flags stay set, look for recalculation job errors in the logs.
8. Use the admin survival rates page for the site to see all levels at once, then reproduce with a
   Kotlin test before changing code.

### Explain a rate to someone

Compute it by hand from `plot_t0_densities` (or the stratum temp densities), the plot's species
totals, and `HECTARES_PER_PLOT`, then compare with the stored value. If they differ, the stored
value is stale (recalculate) or one of the rules above applies.

## Common Mistakes

- Using `total_live` or `permanent_live` as a numerator. They count plots without t0 data.
- Using `latestObservationForSubstratumField` during plot completion. The dependency rows do not
  exist yet; the results are silently wrong.
- Referencing `OBSERVED_PLOT_SPECIES_TOTALS` unaliased inside a correlated subquery whose outer
  table is the same table. Alias it.
- Treating a rate above 100% as a bug. Zero-density t0 rows and species planted after t0 make it
  legitimate.
- Treating a density-0 t0 row as "no t0 data". It counts as t0 data everywhere, including the
  null rule.
- Copying test actuals into expectations without recomputing them by hand from the inputs.
- Recomputing survival rates in Kotlin at read time. Everything is stored; the read side only
  coalesces null to 0 when t0 data exists.
