package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.DealStage
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.default_schema.ProjectId
import java.math.BigDecimal
import java.net.URI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProjectAcceleratorDetailsModelTest {
  @Test
  fun `can convert to ProjectAcceleratorVariableValuesModel`() {
    val model =
        ProjectAcceleratorDetailsModel(
            annualCarbon = BigDecimal(7),
            applicationReforestableLand = BigDecimal(1),
            carbonCapacity = BigDecimal(8),
            confirmedReforestableLand = BigDecimal(2),
            dealDescription = "description",
            dealStage = DealStage.Phase0DocReview,
            dropboxFolderPath = "/dropbox/path",
            failureRisk = "failure",
            fileNaming = "naming",
            googleFolderUrl = URI("https://google.com/"),
            hubSpotUrl = URI("https://hubspot.com/"),
            investmentThesis = "thesis",
            maxCarbonAccumulation = BigDecimal(5),
            minCarbonAccumulation = BigDecimal(4),
            numCommunities = 2,
            numNativeSpecies = 1,
            perHectareBudget = BigDecimal(6),
            pipeline = Pipeline.AcceleratorProjects,
            projectId = ProjectId(1),
            projectLead = "lead",
            totalCarbon = BigDecimal(9),
            totalExpansionPotential = BigDecimal(3),
            whatNeedsToBeTrue = null)

    assertEquals(
        ProjectAcceleratorVariableValuesModel(
            annualCarbon = BigDecimal(7),
            applicationReforestableLand = BigDecimal(1),
            carbonCapacity = BigDecimal(8),
            confirmedReforestableLand = BigDecimal(2),
            dealDescription = "description",
            failureRisk = "failure",
            investmentThesis = "thesis",
            maxCarbonAccumulation = BigDecimal(5),
            minCarbonAccumulation = BigDecimal(4),
            numNativeSpecies = 1,
            perHectareBudget = BigDecimal(6),
            projectId = ProjectId(1),
            totalCarbon = BigDecimal(9),
            totalExpansionPotential = BigDecimal(3),
            whatNeedsToBeTrue = null,
        ),
        model.toVariableValuesModel())
  }
}
