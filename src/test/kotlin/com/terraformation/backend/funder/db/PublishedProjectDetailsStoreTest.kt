package com.terraformation.backend.funder.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.model.CarbonCertification
import com.terraformation.backend.accelerator.model.SustainableDevelopmentGoal
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.funder.tables.pojos.PublishedProjectDetailsRow
import com.terraformation.backend.documentproducer.model.StableIds
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.PathGenerator
import com.terraformation.backend.funder.model.FunderProjectDetailsModel
import io.mockk.mockk
import java.math.BigDecimal
import java.net.URI
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PublishedProjectDetailsStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val random: Random = mockk()
  private val pathGenerator: PathGenerator by lazy { PathGenerator(random) }
  private val fileStore: InMemoryFileStore by lazy { InMemoryFileStore(pathGenerator) }

  private val store: PublishedProjectDetailsStore by lazy {
    PublishedProjectDetailsStore(clock, dslContext, eventPublisher)
  }
  private val variableIdsByStableId: Map<StableId, VariableId> by lazy { setupStableIdVariables() }
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setup() {
    insertOrganization(timeZone = ZoneOffset.UTC)
    projectId = insertProject()
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `can retrieve published project details when empty`() {
      insertPublishedProjectDetails(projectId = projectId)

      val expected =
          FunderProjectDetailsModel(
              projectId = projectId,
          )
      assertEquals(
          expected,
          store.fetchOneById(projectId),
          "Fetch should return published project details from db")
    }

    @Test
    fun `can retrieve published project details when full`() {
      val uri1 = URI("https://test1")
      val uri2 = URI("https://test2")
      val contents = byteArrayOf(1, 2, 3, 4)
      val highlightFileId = insertFile(size = contents.size.toLong(), storageUrl = uri1)
      val zoneFileId = insertFile(size = contents.size.toLong(), storageUrl = uri2)
      fileStore.write(uri1, contents.inputStream())
      fileStore.write(uri2, contents.inputStream())
      val highlightValueId =
          insertImageValue(
              variableIdsByStableId[StableIds.projectHighlightPhoto]!!, highlightFileId)
      val zoneFigureValueId =
          insertImageValue(variableIdsByStableId[StableIds.projectZoneFigure]!!, zoneFileId)

      val publishedProjectDetailsRow =
          PublishedProjectDetailsRow(
              projectId = projectId,
              accumulationRate = BigDecimal(1),
              annualCarbon = BigDecimal(2),
              countryCode = "US",
              dealDescription = "description",
              dealName = "name",
              methodologyNumber = "methodology",
              minProjectArea = BigDecimal(3),
              numNativeSpecies = 4,
              perHectareEstimatedBudget = BigDecimal(5),
              projectArea = BigDecimal(6),
              projectHighlightPhotoValueId = highlightValueId.value,
              projectZoneFigureValueId = zoneFigureValueId.value,
              standard = "standard",
              tfReforestableLand = BigDecimal(7),
              totalExpansionPotential = BigDecimal(8),
              totalVcu = BigDecimal(9),
              verraLink = "https://verraLink",
          )
      val carbonCerts = setOf(CarbonCertification.CcbVerraStandard)
      val sdgList =
          setOf(SustainableDevelopmentGoal.CleanWater, SustainableDevelopmentGoal.ClimateAction)
      val landUsages =
          mapOf(LandUseModelType.Mangroves to BigDecimal(10), LandUseModelType.NativeForest to null)
      insertPublishedProjectDetails(
          publishedProjectDetailsRow,
          carbonCertifications = carbonCerts,
          sdgList = sdgList,
          landUseModelHectares = landUsages,
      )

      val expected =
          FunderProjectDetailsModel(
              projectId = projectId,
              accumulationRate = BigDecimal(1),
              annualCarbon = BigDecimal(2),
              countryCode = "US",
              dealDescription = "description",
              dealName = "name",
              methodologyNumber = "methodology",
              minProjectArea = BigDecimal(3),
              numNativeSpecies = 4,
              perHectareBudget = BigDecimal(5),
              projectArea = BigDecimal(6),
              projectHighlightPhotoValueId = highlightValueId,
              projectZoneFigureValueId = zoneFigureValueId,
              standard = "standard",
              confirmedReforestableLand = BigDecimal(7),
              totalExpansionPotential = BigDecimal(8),
              totalVCU = BigDecimal(9),
              verraLink = URI("https://verraLink"),
              carbonCertifications = carbonCerts,
              sdgList = sdgList,
              landUseModelTypes = landUsages.keys,
              landUseModelHectares =
                  landUsages.filterValues { it != null }.mapValues { it.value!! },
          )
      assertEquals(
          expected,
          store.fetchOneById(projectId),
          "Fetch should return published project details from db")
    }
  }

  @Nested
  inner class Publish {
    @Test
    fun `can publish new project details with minimal data`() {
      val projectModel =
          FunderProjectDetailsModel(
              projectId = projectId,
          )

      store.publish(projectModel)

      assertEquals(
          projectModel,
          store.fetchOneById(projectId),
          "Minimal published project details should be stored in db")
    }

    @Test
    fun `can publish new project details with full data`() {
      val uri1 = URI("https://test1")
      val uri2 = URI("https://test2")
      val contents = byteArrayOf(1, 2, 3, 4)
      val highlightFileId = insertFile(size = contents.size.toLong(), storageUrl = uri1)
      val zoneFileId = insertFile(size = contents.size.toLong(), storageUrl = uri2)
      fileStore.write(uri1, contents.inputStream())
      fileStore.write(uri2, contents.inputStream())
      val highlightValueId =
          insertImageValue(
              variableIdsByStableId[StableIds.projectHighlightPhoto]!!, highlightFileId)
      val zoneFigureValueId =
          insertImageValue(variableIdsByStableId[StableIds.projectZoneFigure]!!, zoneFileId)
      val carbonCerts = setOf(CarbonCertification.CcbVerraStandard)
      val sdgList =
          setOf(
              SustainableDevelopmentGoal.NoPoverty,
              SustainableDevelopmentGoal.LifeOnLand,
              SustainableDevelopmentGoal.LifeBelowWater)
      val landUsages =
          mapOf(
              LandUseModelType.Silvopasture to null,
              LandUseModelType.Agroforestry to BigDecimal(20))

      val projectModel =
          FunderProjectDetailsModel(
              projectId = projectId,
              accumulationRate = BigDecimal(1),
              annualCarbon = BigDecimal(2),
              countryCode = "US",
              dealDescription = "description",
              dealName = "name",
              methodologyNumber = "methodology",
              minProjectArea = BigDecimal(3),
              numNativeSpecies = 4,
              perHectareBudget = BigDecimal(5),
              projectArea = BigDecimal(6),
              projectHighlightPhotoValueId = highlightValueId,
              projectZoneFigureValueId = zoneFigureValueId,
              standard = "standard",
              confirmedReforestableLand = BigDecimal(7),
              totalExpansionPotential = BigDecimal(8),
              totalVCU = BigDecimal(9),
              verraLink = URI("https://verraLink"),
              sdgList = sdgList,
              carbonCertifications = carbonCerts,
              landUseModelTypes = landUsages.keys,
              landUseModelHectares =
                  landUsages.filterValues { it != null }.mapValues { it.value!! },
          )

      store.publish(projectModel)

      assertEquals(
          projectModel,
          store.fetchOneById(projectId),
          "Full published project details should be stored in db")
    }

    @Test
    fun `can publish project details that override`() {
      val uri1 = URI("https://test1")
      val uri2 = URI("https://test2")
      val contents = byteArrayOf(1, 2, 3, 4)
      val highlightFileId = insertFile(size = contents.size.toLong(), storageUrl = uri1)
      val zoneFileId = insertFile(size = contents.size.toLong(), storageUrl = uri2)
      fileStore.write(uri1, contents.inputStream())
      fileStore.write(uri2, contents.inputStream())
      val highlightValueId =
          insertImageValue(
              variableIdsByStableId[StableIds.projectHighlightPhoto]!!, highlightFileId)
      val zoneFigureValueId =
          insertImageValue(variableIdsByStableId[StableIds.projectZoneFigure]!!, zoneFileId)

      val publishedProjectDetailsRow =
          PublishedProjectDetailsRow(
              projectId = projectId,
              accumulationRate = BigDecimal(1),
              annualCarbon = BigDecimal(2),
              countryCode = "US",
              dealDescription = "description",
              dealName = "name",
              methodologyNumber = "methodology",
              minProjectArea = BigDecimal(3),
              numNativeSpecies = 4,
              perHectareEstimatedBudget = BigDecimal(5),
              projectArea = BigDecimal(6),
              projectHighlightPhotoValueId = highlightValueId.value,
              projectZoneFigureValueId = zoneFigureValueId.value,
              standard = "standard",
              tfReforestableLand = BigDecimal(7),
              totalExpansionPotential = BigDecimal(8),
              totalVcu = BigDecimal(9),
              verraLink = "https://verraLink",
          )
      val carbonCerts = setOf(CarbonCertification.CcbVerraStandard)
      val sdgList =
          setOf(SustainableDevelopmentGoal.CleanWater, SustainableDevelopmentGoal.ClimateAction)
      val landUsages =
          mapOf(LandUseModelType.Mangroves to BigDecimal(10), LandUseModelType.NativeForest to null)
      insertPublishedProjectDetails(
          publishedProjectDetailsRow,
          carbonCertifications = carbonCerts,
          sdgList = sdgList,
          landUseModelHectares = landUsages,
      )

      val newLandUsages =
          mapOf(
              LandUseModelType.OtherLandUseModel to BigDecimal(100),
              LandUseModelType.Monoculture to BigDecimal(200))
      val projectModel =
          FunderProjectDetailsModel(
              projectId = projectId,
              accumulationRate = BigDecimal(10),
              annualCarbon = BigDecimal(20),
              countryCode = "MX",
              dealDescription = "descriptionUpdated",
              dealName = "nameUpdated",
              methodologyNumber = "methodologyUpdated",
              minProjectArea = BigDecimal(30),
              numNativeSpecies = 40,
              perHectareBudget = BigDecimal(50),
              projectArea = BigDecimal(60),
              projectHighlightPhotoValueId = highlightValueId,
              projectZoneFigureValueId = zoneFigureValueId,
              standard = "standardUpdated",
              confirmedReforestableLand = BigDecimal(70),
              totalExpansionPotential = BigDecimal(80),
              totalVCU = BigDecimal(90),
              verraLink = URI("https://verraLink/updated"),
              carbonCertifications = emptySet(),
              sdgList =
                  setOf(
                      SustainableDevelopmentGoal.DecentWork,
                      SustainableDevelopmentGoal.GenderEquality),
              landUseModelTypes = newLandUsages.keys,
              landUseModelHectares = newLandUsages,
          )

      store.publish(projectModel)
      assertEquals(
          projectModel, store.fetchOneById(projectId), "Project details should be updated in db")
    }
  }
}
