package com.terraformation.backend.report.model

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.report.ReportNotCompleteException
import java.time.LocalDate
import java.util.stream.Stream
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource

class ReportBodyModelV1Test {
  private val validModel =
      ReportBodyModelV1(
          annualDetails =
              ReportBodyModelV1.AnnualDetails(
                  bestMonthsForObservation = setOf(1, 2, 3),
                  budgetNarrativeSummary = "budget narrative",
                  catalyticDetail = "catalytic detail",
                  challenges = "challenges",
                  isCatalytic = true,
                  keyLessons = "key lessons",
                  nextSteps = "next steps",
                  opportunities = "opportunities",
                  projectImpact = "project impact",
                  projectSummary = "project summary",
                  socialImpact = "social impact",
                  successStories = "success stories",
                  sustainableDevelopmentGoals =
                      listOf(
                          ReportBodyModelV1.AnnualDetails.GoalProgress(
                              SustainableDevelopmentGoal.CleanWater,
                              "clean water progress",
                          ),
                      ),
              ),
          isAnnual = true,
          nurseries =
              listOf(
                  ReportBodyModelV1.Nursery(
                      buildCompletedDate = LocalDate.of(2023, 1, 2),
                      buildStartedDate = LocalDate.of(2023, 1, 1),
                      capacity = 1,
                      id = FacilityId(2),
                      name = "nursery name",
                      notes = "nursery notes",
                      operationStartedDate = LocalDate.of(2023, 1, 3),
                      workers = ReportBodyModelV1.Workers(1, 2, 3),
                  ),
              ),
          notes = "top-level notes",
          organizationName = "org name",
          plantingSites =
              listOf(
                  ReportBodyModelV1.PlantingSite(
                      id = PlantingSiteId(1),
                      mortalityRate = 10,
                      name = "planting site name",
                      notes = "planting site notes",
                      selected = true,
                      species =
                          listOf(
                              ReportBodyModelV1.PlantingSite.Species(
                                  growthForm = GrowthForm.Forb,
                                  id = SpeciesId(1),
                                  mortalityRateInField = 9,
                                  mortalityRateInNursery = 11,
                                  scientificName = "Species name",
                                  totalPlanted = 99,
                              ),
                          ),
                      totalPlantedArea = 10,
                      totalPlantingSiteArea = 11,
                      totalPlantsPlanted = 12,
                      totalTreesPlanted = 13,
                      workers = ReportBodyModelV1.Workers(4, 5, 6),
                  ),
              ),
          seedBanks =
              listOf(
                  ReportBodyModelV1.SeedBank(
                      buildCompletedDate = LocalDate.of(2023, 2, 2),
                      buildCompletedDateEditable = false,
                      buildStartedDate = LocalDate.of(2023, 3, 1),
                      id = FacilityId(1),
                      name = "seedbank name",
                      notes = "seedbank notes",
                      operationStartedDate = LocalDate.of(2023, 4, 1),
                      totalSeedsStored = 1000L,
                      workers = ReportBodyModelV1.Workers(7, 8, 9),
                  ),
              ),
          summaryOfProgress = "summary of progress",
          totalNurseries = 1,
          totalPlantingSites = 1,
          totalSeedBanks = 1,
      )

  @Test
  fun `valid model passes validation`() {
    assertDoesNotThrow { validModel.validate() }
  }

  @Test
  fun `annual details are not validated if isAnnual is false`() {
    val withoutAnnual =
        validModel.copy(isAnnual = false, annualDetails = ReportBodyModelV1.AnnualDetails())

    assertDoesNotThrow { withoutAnnual.validate() }
  }

  @Test
  fun `catalytic details are not required if isCatalytic is false`() {
    val withoutCatalytic =
        validModel.copy(
            annualDetails =
                validModel.annualDetails!!.copy(isCatalytic = false, catalyticDetail = null))

    assertDoesNotThrow { withoutCatalytic.validate() }
  }

  @Test
  fun `required fields are only required on selected facilities and planting sites`() {
    val withoutSelected =
        validModel.copy(
            nurseries =
                listOf(validModel.nurseries[0].copy(selected = false, buildStartedDate = null)),
            plantingSites =
                listOf(validModel.plantingSites[0].copy(selected = false, mortalityRate = null)),
            seedBanks =
                listOf(validModel.seedBanks[0].copy(selected = false, buildStartedDate = null)))

    assertDoesNotThrow { withoutSelected.validate() }
  }

  @MethodSource("missingRequiredFields")
  @ParameterizedTest
  fun `validation fails if required fields are missing`(
      func: ReportBodyModelV1.() -> ReportBodyModelV1
  ) {
    assertThrows<ReportNotCompleteException> { validModel.func().validate() }
  }

  companion object {
    private fun testCase(name: String, func: ReportBodyModelV1.() -> ReportBodyModelV1): Arguments {
      return arguments(named(name, func))
    }

    private fun annualDetailsCase(
        name: String,
        func: ReportBodyModelV1.AnnualDetails.() -> ReportBodyModelV1.AnnualDetails
    ): Arguments {
      return testCase("annualDetails.$name") { copy(annualDetails = annualDetails!!.func()) }
    }

    private fun nurseryCase(
        name: String,
        func: ReportBodyModelV1.Nursery.() -> ReportBodyModelV1.Nursery
    ): Arguments {
      return testCase("nursery.$name") { copy(nurseries = listOf(nurseries[0].func())) }
    }

    private fun plantingSiteCase(
        name: String,
        func: ReportBodyModelV1.PlantingSite.() -> ReportBodyModelV1.PlantingSite
    ): Arguments {
      return testCase("plantingSite.$name") {
        copy(plantingSites = listOf(plantingSites[0].func()))
      }
    }

    private fun plantingSiteSpeciesCase(
        name: String,
        func: ReportBodyModelV1.PlantingSite.Species.() -> ReportBodyModelV1.PlantingSite.Species
    ): Arguments {
      return plantingSiteCase("species.$name") { copy(species = listOf(species[0].func())) }
    }

    private fun seedBankCase(
        name: String,
        func: ReportBodyModelV1.SeedBank.() -> ReportBodyModelV1.SeedBank
    ): Arguments {
      return testCase("seedBank.$name") { copy(seedBanks = listOf(seedBanks[0].func())) }
    }

    @JvmStatic
    fun missingRequiredFields(): Stream<Arguments> =
        Stream.of(
            annualDetailsCase("bestMonthsForObservation") {
              copy(bestMonthsForObservation = emptySet())
            },
            annualDetailsCase("budgetNarrativeSummary") { copy(budgetNarrativeSummary = null) },
            annualDetailsCase("catalyticDetail") { copy(catalyticDetail = null) },
            annualDetailsCase("challenges") { copy(challenges = null) },
            annualDetailsCase("keyLessons") { copy(keyLessons = null) },
            annualDetailsCase("nextSteps") { copy(nextSteps = null) },
            annualDetailsCase("opportunities") { copy(opportunities = null) },
            annualDetailsCase("projectImpact") { copy(projectImpact = null) },
            annualDetailsCase("projectSummary") { copy(projectSummary = null) },
            annualDetailsCase("socialImpact") { copy(socialImpact = null) },
            annualDetailsCase("successStories") { copy(successStories = null) },
            nurseryCase("buildCompletedDate") { copy(buildCompletedDate = null) },
            nurseryCase("buildStartedDate") { copy(buildStartedDate = null) },
            nurseryCase("capacity") { copy(capacity = null) },
            nurseryCase("operationStartedDate") { copy(operationStartedDate = null) },
            nurseryCase("workers.femalePaidWorkers") {
              copy(workers = workers.copy(femalePaidWorkers = null))
            },
            nurseryCase("workers.paidWorkers") { copy(workers = workers.copy(paidWorkers = null)) },
            nurseryCase("workers.volunteers") { copy(workers = workers.copy(volunteers = null)) },
            plantingSiteCase("mortalityRate") { copy(mortalityRate = null) },
            plantingSiteCase("totalPlantedArea") { copy(totalPlantedArea = null) },
            plantingSiteCase("totalPlantingSiteArea") { copy(totalPlantingSiteArea = null) },
            plantingSiteCase("totalPlantsPlanted") { copy(totalPlantsPlanted = null) },
            plantingSiteCase("totalTreesPlanted") { copy(totalTreesPlanted = null) },
            plantingSiteSpeciesCase("mortalityRateInField") { copy(mortalityRateInField = null) },
            plantingSiteSpeciesCase("mortalityRateInNursery") {
              copy(mortalityRateInNursery = null)
            },
            plantingSiteSpeciesCase("totalPlanted") { copy(totalPlanted = null) },
            seedBankCase("buildCompletedDate") { copy(buildCompletedDate = null) },
            seedBankCase("buildStartedDate") { copy(buildStartedDate = null) },
            seedBankCase("operationStartedDate") { copy(operationStartedDate = null) },
            seedBankCase("workers.femalePaidWorkers") {
              copy(workers = workers.copy(femalePaidWorkers = null))
            },
            seedBankCase("workers.paidWorkers") {
              copy(workers = workers.copy(paidWorkers = null))
            },
            seedBankCase("workers.volunteers") { copy(workers = workers.copy(volunteers = null)) },
        )
  }
}
