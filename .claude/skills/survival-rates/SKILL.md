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

| Term                  | Meaning                                                                                                                                                                                                                                                                                                                                                       |
|-----------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| t0 density            | Baseline plants per hectare for one species, either per permanent plot (`plot_t0_densities`) or per stratum for temporary plots (`stratum_t0_temp_densities`).                                                                                                                                                                                                |
| t0 observation        | The observation a permanent plot's t0 densities were derived from (`plot_t0_observations`). Not every plot has one; densities can also be entered manually.                                                                                                                                                                                                   |
| Permanent plot        | A monitoring plot revisited each observation (`monitoring_plots.permanent_index` is set). Only permanent plots have their own t0 densities. Permanence belongs to the plot but can change over time, so each observation records whether the plot was permanent at the time (`observation_plots.is_permanent`), and the calculation uses that recorded value. |
| Temporary plot        | A plot chosen fresh for an observation. It takes its t0 density from its stratum, and only if the planting site has `survival_rate_includes_temp_plots` set.                                                                                                                                                                                                  |
| Scope                 | The level a rate is computed for: plot, substratum, stratum, or planting site.                                                                                                                                                                                                                                                                                |
| Species totals tables | `observed_{plot,substratum,stratum,site}_species_totals`: one row per observation, scope, and species. Hold per-species live/dead/existing counts and a per-species `survival_rate`.                                                                                                                                                                          |
| Results tables        | `observation_{plot,substratum,stratum,site}_results`: one row per observation and scope, all species combined. Hold the aggregate `survival_rate`, plus `survival_rate_std_dev` and `survival_rate_area` above plot level.                                                                                                                                    |
| Roll-forward          | When an observation does not cover a substratum, the substratum's latest earlier observation stands in for it at stratum and site level, recorded in `observation_dependent_substrata`.                                                                                                                                                                       |
| `HECTARES_PER_PLOT`   | 0.09. Plots are 30 m squares. A denominator is the sum of t0 densities times this constant, so it is in plants, matching the live count.                                                                                                                                                                                                                      |

## The Calculation

```
survival_rate = live_plants_in_t0_pairs * 100 / (sum(t0_density over t0_pairs) * HECTARES_PER_PLOT)
```

- **Live only.** Dead plants never count. Existing plants (already there before planting) never
  count in either the numerator or a t0 density.
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

1. **Null rule (results tables only).** If any permanent plot observed in an observation has no
   t0 row with density greater than zero, that substratum's aggregate rate is null. A stratum's
   aggregate rate is null if any of its substratum result rows in that observation has a null
   rate and either the site includes temp plots or the substratum had permanent plots in the
   observation. The site rate is null if any stratum's latest rate is null. Per-species rates are
   never nulled by this rule; they simply exclude the plot. Implemented by
   `anyChildHasNullSurvivalRateCondition` in `ObservationResultsScope.kt`. The stratum check only
   sees substratum rows that exist for that observation, so a substratum skipped by the observation
   does not trigger it even if it would have.

2. **Excluding plots without t0 is intentional.** The numerator is derived from the same plot set
   as the denominator, never from the stored `total_live` or `permanent_live` columns. Those
   columns count every completed plot regardless of t0 data and exist for the API and search; using
   either as a survival rate numerator inflates the rate.

3. **Zero denominator handling differs by path, and this is known.** When every t0 density in the
   set is zero, the plot-completion path and the stratum/site roll-forward path store 0%, while the
   species totals recalculation path and all results table paths store null. The read side in
   `ObservationMultisets` shows 0 for a null per-species rate whenever a t0 density exists, so the
   API hides most of the difference.

4. **The site aggregate rate is not numerator over denominator.** In the results tables the site
   rate is the area-weighted average of its strata's rates, weighting each stratum by its
   `survival_rate_area` (the area of substrata observed at or before the observation). See
   `ObservationResultsSite.survivalRateValue`. Standard deviations at substratum and stratum level
   are computed across plot rates weighted by plot planting density.

5. **Temp plots are opt-in per site.** `planting_sites.survival_rate_includes_temp_plots`
   controls whether temporary plots count at all. Changing it fires
   `SurvivalRateIncludesTempPlotsChangedEvent`, which recalculates the site.

## Two Write Paths With Different Plot Attribution

Every rate needs to know which observation a plot's live counts come from. There are two answers,
and using the wrong one silently produces wrong numbers.

| Path                                                         | Attribution helper                                                                      | Why                                                                                                                                                                        |
|--------------------------------------------------------------|-----------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Plot completion, species totals (`updateSpeciesTotalsTable`) | `requestedObservationForPlotCondition`, built on `observationIdForPlot` in `Queries.kt` | Runs before `recordSubstratumDependencies`, so `observation_dependent_substrata` has no rows for this observation yet. Uses the observation's requested substrata instead. |
| Everything else: recalculation, roll-forward, results tables | `latestObservationForPlotCondition`, built on `latestObservationForSubstratumField`     | Attributes each plot to its substratum's latest observation at or before the target, which is how the live totals are rolled up.                                           |

The two helpers are meant to agree: for a substratum the observation actually covers, both resolve
to that observation, and once `recordSubstratumDependencies` has run every later recalculation uses
the latest-observation form. The split exists only because of write ordering during completion.
There is no known case where the two attributions store different values for the same row; the
null-versus-zero difference described in rule 3 below is a separate matter of how a zero
denominator is expressed, not of which observation is attributed.

Both helpers feed `permanentT0PlotSet` and `tempT0PlotSet` in `ObservationStore`, which define the
plot set once. `getSurvivalRateTerms` derives the numerator and both denominator variants from
those sets for SQL expressions; `getSurvivalRateTermsBySpecies` does the same as a grouped query
for the completion path, which then writes all species' rates with one `CASE` update.

## When Rates Are Recalculated

Stored rates go stale whenever their inputs change, so every input change has a recalculation path.
When adding a new way to change observation data or t0 data, make sure one of these runs.

| Trigger                                                                    | What runs                                                                                                                                                                                                                                                                                                                                                                                      |
|----------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| A plot is completed                                                        | `completePlot` updates species totals and results for that observation immediately. When the last plot completes, `completeObservation` rolls stratum and site totals forward and recalculates results.                                                                                                                                                                                        |
| An observation is abandoned                                                | `abandonObservation` does the same roll-forward and results recalculation for the plots that were completed.                                                                                                                                                                                                                                                                                   |
| A plot's species counts are edited in a completed observation              | `updateMonitoringSpecies` rewrites that plot's totals and recalculates the observation, then walks every later observation that depends on it through `observation_dependent_substrata`, in completion order, recalculating each one's roll-forward totals, results counts, and results rates. It then publishes `MonitoringSpeciesTotalsEditedEvent`.                                         |
| An edited observation is a plot's t0 observation                           | `T0Store.on(MonitoringSpeciesTotalsEditedEvent)` re-derives that plot's t0 densities, which publishes `T0PlotDataAssignedEvent` and triggers the asynchronous site recalculation below.                                                                                                                                                                                                        |
| An observation is deleted or merged into another                           | `ObservationService.deleteObservation` and `mergeObservations` call `recalculateSurvivalRates(plantingSiteId)` synchronously for the whole site.                                                                                                                                                                                                                                               |
| t0 data is assigned for a plot or a stratum, or the temp-plot flag changes | `ObservationStore.on(...)` for `T0PlotDataAssignedEvent`, `T0StratumDataAssignedEvent`, and `SurvivalRateIncludesTempPlotsChangedEvent` enqueues an asynchronous job through `enqueueSurvivalRateCalculation`. The lock table `planting_site_survival_rate_calculations` coalesces concurrent requests, and `fetchSurvivalRateCalculationInProgress` exposes the in-progress state to the API. |
| An admin requests it                                                       | `POST /admin/recalculateSurvivalRates` for one observation, one site, or every site. Use this to correct stored data after deploying a calculation change.                                                                                                                                                                                                                                     |

Planting site geometry changes do not trigger a recalculation by themselves. If a change to strata
or substrata should affect stored rates, call `recalculateSurvivalRates(stratumId)` or the site
variant explicitly.

## Code Map

All paths below are under `src/main/kotlin/com/terraformation/backend/`.

**Calculation, `tracking/db/ObservationStore.kt`**

| Function                                                                                                         | Role                                                                                                                                                                                                                                                        |
|------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `completePlot`                                                                                                   | Entry point when a plot is completed. Order matters: species totals, then mark complete, then `recordSubstratumDependencies`, then `updateObservationResults`, then results rates. On the last plot, `completeObservation`.                                 |
| `updateSpeciesTotalsTable`                                                                                       | Incremental per-species counts for one scope, then survival rates for all species in one update.                                                                                                                                                            |
| `recalculateSurvivalRates(observationId, plantingSiteId)`                                                        | Rolls substratum species totals forward into stratum and site species totals for an observation, with rates. Called from `completeObservation`, `abandonObservation`, `populateObservationResults`, and, for later observations, `updateMonitoringSpecies`. |
| `recalculateSurvivalRate(ObservationSpeciesScope)`                                                               | Recomputes `survival_rate` on a species totals table for a scope, all observations.                                                                                                                                                                         |
| `updateObservationResults`                                                                                       | Sums species totals into the results tables' count and density columns. Not rates.                                                                                                                                                                          |
| `recalculateSurvivalRateResults(...)`                                                                            | Recomputes results table rates, std dev, and area for a scope. Only for observations whose plots in scope are all completed.                                                                                                                                |
| `recalculateSurvivalRates(plantingSiteId / stratumId / monitoringPlotId)`, `recalculateAllSurvivalRates`         | Public recalculation entry points. Each walks plot, substratum, stratum, site for both table families.                                                                                                                                                      |
| `T0PlotSet`, `permanentT0PlotSet`, `tempT0PlotSet`, `getSurvivalRateTerms`, `getSurvivalRateTermsBySpecies`      | The shared plot-set definition and the terms derived from it. Change these to change what counts.                                                                                                                                                           |
| `plotHasCompletedObservations`                                                                                   | Completed-plot and permanence check used by the plot sets.                                                                                                                                                                                                  |
| `getSurvivalRateWeightedStandardDeviation`                                                                       | Std dev across plot results.                                                                                                                                                                                                                                |
| `updateMonitoringSpecies`                                                                                        | Edit path for a plot's species counts in a completed observation. See "When Rates Are Recalculated" for the chain it triggers.                                                                                                                              |
| `on(T0PlotDataAssignedEvent)`, `on(T0StratumDataAssignedEvent)`, `on(SurvivalRateIncludesTempPlotsChangedEvent)` | Enqueue the asynchronous site recalculation described in "When Rates Are Recalculated".                                                                                                                                                                     |

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
  every level's latest rate for a site, useful when debugging a real site.
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
2. Publish or reuse the events so recalculation runs. Recalculation is asynchronous and locked per
   site.
3. If the test scenario importer's t0 derivation should mirror your change, update both copies of
   the counting logic in `ObservationScenarioTest.kt` (see testing.md).

### Debug a wrong or null rate for a real site

Work down this list; most reports are one of these.

1. Does the (plot, species) have a t0 row? Zero counts as a row. No row means the species is out.
2. Was the plot recorded as permanent in its most recently completed observation? A plot whose
   permanence has changed moves between the permanent and temp sets from that observation on.
3. Is the site's `survival_rate_includes_temp_plots` flag what the reporter expects?
4. Is the aggregate null while per-species rates are populated? That is the null rule; find the
   permanent plot without a positive t0 density.
5. Is the observation complete? Results table rates are only written once every plot in scope is
   completed or marked not observed.
6. Is a substratum missing from the observation? Its numbers roll forward from its latest earlier
   observation; check `observation_dependent_substrata`.
7. Is a recalculation still running? Check `planting_site_survival_rate_calculations`.
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
- Treating a density-0 t0 row as "no t0 data". It is t0 data for the calculation, but not for the
  null rule, which requires density greater than zero.
- Copying test actuals into expectations without recomputing them by hand from the inputs.
- Recomputing survival rates in Kotlin at read time. Everything is stored; the read side only
  coalesces null to 0 when t0 data exists.
