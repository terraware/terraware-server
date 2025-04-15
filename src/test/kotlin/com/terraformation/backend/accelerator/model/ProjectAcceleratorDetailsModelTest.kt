package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.DealStage
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Region
import java.math.BigDecimal
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProjectAcceleratorDetailsModelTest {

  @Test
  fun `can convert to ProjectAcceleratorVariableValuesModel`() {
    val model =
        ProjectAcceleratorDetailsModel(
            accumulationRate = BigDecimal(20),
            annualCarbon = BigDecimal(7),
            applicationReforestableLand = BigDecimal(1),
            carbonCapacity = BigDecimal(8),
            clickUpLink = "https://click.up",
            confirmedReforestableLand = BigDecimal(2),
            countryCode = "US",
            dealDescription = "description",
            dealName = "dealName",
            dealStage = DealStage.Phase0DocReview,
            dropboxFolderPath = "/dropbox/path",
            expectedMarketCredits = BigDecimal(21),
            failureRisk = "failure",
            fileNaming = "naming",
            gisReportsLink = "https://gisReportsLink",
            googleFolderUrl = URI("https://google.com/"),
            hubSpotUrl = URI("https://hubspot.com/"),
            investmentThesis = "thesis",
            landUseModelTypes = setOf(LandUseModelType.Mangroves, LandUseModelType.Silvopasture),
            landUseModelHectares =
                mapOf(
                    LandUseModelType.Mangroves to BigDecimal(1001),
                    LandUseModelType.Silvopasture to BigDecimal(2002)),
            maxCarbonAccumulation = BigDecimal(5),
            methodologyNumber = "methodologyNumber",
            minCarbonAccumulation = BigDecimal(4),
            minProjectArea = BigDecimal(22),
            numCommunities = 2,
            numNativeSpecies = 1,
            perHectareBudget = BigDecimal(6),
            pipeline = Pipeline.AcceleratorProjects,
            projectArea = BigDecimal(23),
            projectId = ProjectId(1),
            projectLead = "lead",
            region = Region.LatinAmericaCaribbean,
            riskTrackerLink = "https://riskTrackerLink",
            sdgList =
                setOf(
                    SustainableDevelopmentGoal.AffordableEnergy,
                    SustainableDevelopmentGoal.ClimateAction),
            slackLink = "https://slackLink",
            standard = "standard",
            totalCarbon = BigDecimal(9),
            totalExpansionPotential = BigDecimal(3),
            totalVCU = BigDecimal(24),
            verraLink = "https://verraLink",
            whatNeedsToBeTrue = null,
        )

    assertEquals(
        ProjectAcceleratorVariableValuesModel(
            accumulationRate = BigDecimal(20),
            annualCarbon = BigDecimal(7),
            applicationReforestableLand = BigDecimal(1),
            carbonCapacity = BigDecimal(8),
            clickUpLink = "https://click.up",
            confirmedReforestableLand = BigDecimal(2),
            countryCode = "US",
            dealName = "dealName",
            dealDescription = "description",
            expectedMarketCredits = BigDecimal(21),
            failureRisk = "failure",
            gisReportsLink = "https://gisReportsLink",
            investmentThesis = "thesis",
            landUseModelTypes = setOf(LandUseModelType.Mangroves, LandUseModelType.Silvopasture),
            landUseModelHectares =
                mapOf(
                    LandUseModelType.Mangroves to BigDecimal(1001),
                    LandUseModelType.Silvopasture to BigDecimal(2002)),
            maxCarbonAccumulation = BigDecimal(5),
            methodologyNumber = "methodologyNumber",
            minCarbonAccumulation = BigDecimal(4),
            minProjectArea = BigDecimal(22),
            numNativeSpecies = 1,
            perHectareBudget = BigDecimal(6),
            projectId = ProjectId(1),
            projectArea = BigDecimal(23),
            region = Region.LatinAmericaCaribbean,
            riskTrackerLink = "https://riskTrackerLink",
            sdgList =
                setOf(
                    SustainableDevelopmentGoal.AffordableEnergy,
                    SustainableDevelopmentGoal.ClimateAction),
            slackLink = "https://slackLink",
            standard = "standard",
            totalCarbon = BigDecimal(9),
            totalExpansionPotential = BigDecimal(3),
            totalVCU = BigDecimal(24),
            verraLink = "https://verraLink",
            whatNeedsToBeTrue = null,
        ),
        model.toVariableValuesModel())
  }
}
