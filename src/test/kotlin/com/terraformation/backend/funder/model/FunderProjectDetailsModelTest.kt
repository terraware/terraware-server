package com.terraformation.backend.funder.model

import com.terraformation.backend.accelerator.model.CarbonCertification
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.accelerator.model.SustainableDevelopmentGoal
import com.terraformation.backend.db.accelerator.DealStage
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Region
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.funder.tables.pojos.PublishedProjectDetailsRow
import com.terraformation.backend.db.funder.tables.records.PublishedProjectDetailsRecord
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FunderProjectDetailsModelTest {

  @Test
  fun `can convert from ProjectAcceleratorDetailsModel`() {
    val projectAcceleratorDetailsModel =
        ProjectAcceleratorDetailsModel(
            accumulationRate = BigDecimal(20),
            annualCarbon = BigDecimal(7),
            applicationReforestableLand = BigDecimal(1),
            carbonCapacity = BigDecimal(8),
            carbonCertifications = setOf(CarbonCertification.CcbVerraStandard),
            clickUpLink = URI("https://click.up"),
            confirmedReforestableLand = BigDecimal(2),
            countryCode = "US",
            dealDescription = "description",
            dealName = "dealName",
            dealStage = DealStage.Phase0DocReview,
            dropboxFolderPath = "/dropbox/path",
            failureRisk = "failure",
            fileNaming = "naming",
            gisReportsLink = URI("https://gisReportsLink"),
            googleFolderUrl = URI("https://google.com/"),
            hubSpotUrl = URI("https://hubspot.com/"),
            investmentThesis = "thesis",
            landUseModelTypes = setOf(LandUseModelType.Mangroves, LandUseModelType.Silvopasture),
            landUseModelHectares =
                mapOf(
                    LandUseModelType.Mangroves to BigDecimal(1001),
                    LandUseModelType.Silvopasture to BigDecimal(2002),
                ),
            maxCarbonAccumulation = BigDecimal(5),
            methodologyNumber = "methodologyNumber",
            minCarbonAccumulation = BigDecimal(4),
            minProjectArea = BigDecimal(22),
            numCommunities = 2,
            numNativeSpecies = 1,
            perHectareBudget = BigDecimal(6),
            pipeline = Pipeline.AcceleratorProjects,
            projectArea = BigDecimal(23),
            projectHighlightPhotoValueId = VariableValueId(123),
            projectId = ProjectId(1),
            projectLead = "lead",
            projectZoneFigureValueId = VariableValueId(456),
            region = Region.LatinAmericaCaribbean,
            riskTrackerLink = URI("https://riskTrackerLink"),
            sdgList =
                setOf(
                    SustainableDevelopmentGoal.AffordableEnergy,
                    SustainableDevelopmentGoal.ClimateAction,
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
        FunderProjectDetailsModel(
            accumulationRate = BigDecimal(20),
            annualCarbon = BigDecimal(7),
            carbonCertifications = setOf(CarbonCertification.CcbVerraStandard),
            confirmedReforestableLand = BigDecimal(2),
            countryCode = "US",
            dealDescription = "description",
            dealName = "dealName",
            landUseModelTypes = setOf(LandUseModelType.Mangroves, LandUseModelType.Silvopasture),
            landUseModelHectares =
                mapOf(
                    LandUseModelType.Mangroves to BigDecimal(1001),
                    LandUseModelType.Silvopasture to BigDecimal(2002),
                ),
            methodologyNumber = "methodologyNumber",
            minProjectArea = BigDecimal(22),
            numNativeSpecies = 1,
            perHectareBudget = BigDecimal(6),
            projectArea = BigDecimal(23),
            projectHighlightPhotoValueId = VariableValueId(123),
            projectId = ProjectId(1),
            projectZoneFigureValueId = VariableValueId(456),
            sdgList =
                setOf(
                    SustainableDevelopmentGoal.AffordableEnergy,
                    SustainableDevelopmentGoal.ClimateAction,
                ),
            standard = "standard",
            totalExpansionPotential = BigDecimal(3),
            totalVCU = BigDecimal(24),
            verraLink = URI("https://verraLink"),
        ),
        FunderProjectDetailsModel.of(projectAcceleratorDetailsModel),
    )
  }

  @Test
  fun `can convert from PublishedProjectDetailsRecord`() {
    val record =
        PublishedProjectDetailsRecord(
            PublishedProjectDetailsRow(
                projectId = ProjectId(10),
                accumulationRate = BigDecimal(11),
                annualCarbon = BigDecimal(12),
                countryCode = "US",
                dealDescription = "dealDescription",
                dealName = "dealName",
                methodologyNumber = "methodology",
                minProjectArea = BigDecimal(13),
                numNativeSpecies = 14,
                perHectareEstimatedBudget = BigDecimal(15),
                projectArea = BigDecimal(16),
                projectHighlightPhotoValueId = 17,
                projectZoneFigureValueId = 18,
                standard = "standard",
                tfReforestableLand = BigDecimal(19),
                totalExpansionPotential = BigDecimal(20),
                totalVcu = BigDecimal(21),
                verraLink = "https://verraLink",
                publishedBy = UserId(2),
                publishedTime = Instant.EPOCH,
            )
        )
    val sdgList =
        setOf(SustainableDevelopmentGoal.CleanWater, SustainableDevelopmentGoal.ClimateAction)
    val carbonCerts = setOf(CarbonCertification.CcbVerraStandard)
    val landUsages = mapOf(LandUseModelType.Mangroves to BigDecimal(10))

    assertEquals(
        FunderProjectDetailsModel(
            projectId = ProjectId(10),
            accumulationRate = BigDecimal(11),
            annualCarbon = BigDecimal(12),
            countryCode = "US",
            dealDescription = "dealDescription",
            dealName = "dealName",
            methodologyNumber = "methodology",
            minProjectArea = BigDecimal(13),
            numNativeSpecies = 14,
            perHectareBudget = BigDecimal(15),
            projectArea = BigDecimal(16),
            projectHighlightPhotoValueId = VariableValueId(17),
            projectZoneFigureValueId = VariableValueId(18),
            standard = "standard",
            confirmedReforestableLand = BigDecimal(19),
            totalExpansionPotential = BigDecimal(20),
            totalVCU = BigDecimal(21),
            verraLink = URI("https://verraLink"),
            sdgList = sdgList,
            carbonCertifications = carbonCerts,
            landUseModelTypes = landUsages.keys,
            landUseModelHectares = landUsages,
        ),
        FunderProjectDetailsModel.of(record, carbonCerts, sdgList, landUsages),
    )
  }
}
