package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.default_schema.ProjectId
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProjectAcceleratorVariableValuesModelTest {
  @Test
  fun `can convert to ProjectAcceleratorDetailsModel, with null values for non-variable fields`() {
    val values =
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
            whatNeedsToBeTrue = null)

    assertEquals(
        ProjectAcceleratorDetailsModel(
            annualCarbon = BigDecimal(7),
            applicationReforestableLand = BigDecimal(1),
            carbonCapacity = BigDecimal(8),
            confirmedReforestableLand = BigDecimal(2),
            dealDescription = "description",
            dealStage = null,
            dropboxFolderPath = null,
            failureRisk = "failure",
            fileNaming = null,
            googleFolderUrl = null,
            hubSpotUrl = null,
            investmentThesis = "thesis",
            maxCarbonAccumulation = BigDecimal(5),
            minCarbonAccumulation = BigDecimal(4),
            numCommunities = null,
            numNativeSpecies = 1,
            perHectareBudget = BigDecimal(6),
            pipeline = null,
            projectId = ProjectId(1),
            projectLead = null,
            totalCarbon = BigDecimal(9),
            totalExpansionPotential = BigDecimal(3),
            whatNeedsToBeTrue = null),
        values.toProjectAcceleratorDetails())
  }
}
