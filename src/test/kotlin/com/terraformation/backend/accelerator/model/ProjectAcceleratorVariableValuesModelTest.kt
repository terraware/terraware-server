package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableValueId
import java.math.BigDecimal
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProjectAcceleratorVariableValuesModelTest {

  @Test
  fun `can convert to ProjectAcceleratorDetailsModel, with null values for non-variable fields`() {
    val values =
        ProjectAcceleratorVariableValuesModel(
            accumulationRate = BigDecimal(20),
            annualCarbon = BigDecimal(7),
            applicationReforestableLand = BigDecimal(1),
            carbonCapacity = BigDecimal(8),
            carbonCertifications = setOf(CarbonCertification.CcbVerraStandard),
            confirmedReforestableLand = BigDecimal(2),
            clickUpLink = URI("https://click.up"),
            dealDescription = "description",
            dealName = "dealName",
            failureRisk = "failure",
            gisReportsLink = URI("https://gisReportsLink"),
            investmentThesis = "thesis",
            landUseModelTypes = setOf(LandUseModelType.NativeForest, LandUseModelType.Monoculture),
            landUseModelHectares =
                mapOf(
                    LandUseModelType.Monoculture to BigDecimal(101),
                    LandUseModelType.NativeForest to BigDecimal(102),
                ),
            maxCarbonAccumulation = BigDecimal(5),
            methodologyNumber = "methodologyNumber",
            minCarbonAccumulation = BigDecimal(4),
            minProjectArea = BigDecimal(22),
            numNativeSpecies = 1,
            perHectareBudget = BigDecimal(6),
            projectArea = BigDecimal(23),
            projectHighlightPhotoValueId = VariableValueId(234),
            projectId = ProjectId(1),
            projectZoneFigureValueId = VariableValueId(567),
            riskTrackerLink = URI("https://riskTrackerLink"),
            sdgList =
                setOf(
                    SustainableDevelopmentGoal.LifeBelowWater,
                    SustainableDevelopmentGoal.DecentWork,
                ),
            slackLink = URI("https://slackLink"),
            standard = "standard",
            totalCarbon = BigDecimal(9),
            totalExpansionPotential = BigDecimal(3),
            totalVCU = BigDecimal(24),
            verraLink = URI("https://verraLink"),
            whatNeedsToBeTrue = null,
        )

    assertEquals(
        ProjectAcceleratorDetailsModel(
            accumulationRate = BigDecimal(20),
            annualCarbon = BigDecimal(7),
            applicationReforestableLand = BigDecimal(1),
            carbonCapacity = BigDecimal(8),
            carbonCertifications = setOf(CarbonCertification.CcbVerraStandard),
            confirmedReforestableLand = BigDecimal(2),
            clickUpLink = URI("https://click.up"),
            dealDescription = "description",
            dealName = "dealName",
            dealStage = null,
            dropboxFolderPath = null,
            failureRisk = "failure",
            fileNaming = null,
            gisReportsLink = URI("https://gisReportsLink"),
            googleFolderUrl = null,
            hubSpotUrl = null,
            investmentThesis = "thesis",
            landUseModelTypes = setOf(LandUseModelType.NativeForest, LandUseModelType.Monoculture),
            landUseModelHectares =
                mapOf(
                    LandUseModelType.NativeForest to BigDecimal(102),
                    LandUseModelType.Monoculture to BigDecimal(101),
                ),
            maxCarbonAccumulation = BigDecimal(5),
            methodologyNumber = "methodologyNumber",
            minCarbonAccumulation = BigDecimal(4),
            minProjectArea = BigDecimal(22),
            numCommunities = null,
            numNativeSpecies = 1,
            perHectareBudget = BigDecimal(6),
            pipeline = null,
            projectArea = BigDecimal(23),
            projectHighlightPhotoValueId = VariableValueId(234),
            projectId = ProjectId(1),
            projectZoneFigureValueId = VariableValueId(567),
            riskTrackerLink = URI("https://riskTrackerLink"),
            sdgList =
                setOf(
                    SustainableDevelopmentGoal.DecentWork,
                    SustainableDevelopmentGoal.LifeBelowWater,
                ),
            slackLink = URI("https://slackLink"),
            standard = "standard",
            projectLead = null,
            totalCarbon = BigDecimal(9),
            totalExpansionPotential = BigDecimal(3),
            totalVCU = BigDecimal(24),
            verraLink = URI("https://verraLink"),
            whatNeedsToBeTrue = null,
        ),
        values.toProjectAcceleratorDetails(),
    )
  }
}
