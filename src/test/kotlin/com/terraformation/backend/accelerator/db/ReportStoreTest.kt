package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.ReportSubmittedEvent
import com.terraformation.backend.accelerator.model.ExistingProjectReportConfigModel
import com.terraformation.backend.accelerator.model.NewProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ProjectMetricModel
import com.terraformation.backend.accelerator.model.ReportMetricEntryModel
import com.terraformation.backend.accelerator.model.ReportModel
import com.terraformation.backend.accelerator.model.ReportProjectMetricModel
import com.terraformation.backend.accelerator.model.ReportStandardMetricModel
import com.terraformation.backend.accelerator.model.ReportSystemMetricEntryModel
import com.terraformation.backend.accelerator.model.ReportSystemMetricModel
import com.terraformation.backend.accelerator.model.StandardMetricModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.MetricComponent
import com.terraformation.backend.db.accelerator.MetricType
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.accelerator.tables.records.ProjectReportConfigsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportProjectMetricsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportStandardMetricsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportsRecord
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tracking.PlantingType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ReportStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val systemUser: SystemUser by lazy { SystemUser(usersDao) }
  private val store: ReportStore by lazy {
    ReportStore(clock, dslContext, eventPublisher, reportsDao, systemUser)
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setup() {
    organizationId = insertOrganization()
    projectId = insertProject()
    insertOrganizationUser(role = Role.Admin)
    insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
  }

  @Nested
  inner class Fetch {
    @Test
    fun `returns report details`() {
      val configId = insertProjectReportConfig()
      val reportId =
          insertReport(
              status = ReportStatus.NeedsUpdate,
              startDate = LocalDate.of(2030, Month.JANUARY, 1),
              endDate = LocalDate.of(2030, Month.DECEMBER, 31),
              internalComment = "internal comment",
              feedback = "feedback",
              createdBy = systemUser.userId,
              createdTime = Instant.ofEpochSecond(4000),
              modifiedBy = user.userId,
              modifiedTime = Instant.ofEpochSecond(8000),
              submittedBy = user.userId,
              submittedTime = Instant.ofEpochSecond(6000),
          )

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              status = ReportStatus.NeedsUpdate,
              startDate = LocalDate.of(2030, Month.JANUARY, 1),
              endDate = LocalDate.of(2030, Month.DECEMBER, 31),
              internalComment = "internal comment",
              feedback = "feedback",
              createdBy = systemUser.userId,
              createdTime = Instant.ofEpochSecond(4000),
              modifiedBy = user.userId,
              modifiedTime = Instant.ofEpochSecond(8000),
              submittedBy = user.userId,
              submittedTime = Instant.ofEpochSecond(6000),
          )

      clock.instant = LocalDate.of(2031, Month.JANUARY, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
      assertEquals(listOf(reportModel), store.fetch())
    }

    @Test
    fun `includes metrics`() {
      val configId = insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted)

      val projectMetricId =
          insertProjectMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project Metric description",
              name = "Project Metric Name",
              reference = "2.0",
              type = MetricType.Activity,
          )

      insertReportProjectMetric(
          reportId = reportId,
          metricId = projectMetricId,
          target = 100,
          modifiedTime = Instant.ofEpochSecond(1500),
          modifiedBy = user.userId,
      )

      val projectMetrics =
          listOf(
              ReportProjectMetricModel(
                  metric =
                      ProjectMetricModel(
                          id = projectMetricId,
                          projectId = projectId,
                          component = MetricComponent.ProjectObjectives,
                          description = "Project Metric description",
                          name = "Project Metric Name",
                          reference = "2.0",
                          type = MetricType.Activity,
                      ),
                  entry =
                      ReportMetricEntryModel(
                          target = 100,
                          modifiedTime = Instant.ofEpochSecond(1500),
                          modifiedBy = user.userId,
                      )),
          )

      val standardMetricId1 =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Climate standard metric description",
              name = "Climate Standard Metric",
              reference = "2.1",
              type = MetricType.Activity,
          )

      val standardMetricId2 =
          insertStandardMetric(
              component = MetricComponent.Community,
              description = "Community metric description",
              name = "Community Metric",
              reference = "10.0",
              type = MetricType.Outcome,
          )

      val standardMetricId3 =
          insertStandardMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project objectives metric description",
              name = "Project Objectives Metric",
              reference = "2.0",
              type = MetricType.Impact,
          )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          target = 55,
          value = 45,
          notes = "Almost at target",
          internalComment = "Not quite there yet",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
          target = 25,
          modifiedTime = Instant.ofEpochSecond(1500),
          modifiedBy = user.userId,
      )

      val standardMetrics =
          listOf(
              // ordered by reference
              ReportStandardMetricModel(
                  metric =
                      StandardMetricModel(
                          id = standardMetricId3,
                          component = MetricComponent.ProjectObjectives,
                          description = "Project objectives metric description",
                          name = "Project Objectives Metric",
                          reference = "2.0",
                          type = MetricType.Impact,
                      ),
                  // all fields are null because no target/value have been set yet
                  entry = ReportMetricEntryModel()),
              ReportStandardMetricModel(
                  metric =
                      StandardMetricModel(
                          id = standardMetricId1,
                          component = MetricComponent.Climate,
                          description = "Climate standard metric description",
                          name = "Climate Standard Metric",
                          reference = "2.1",
                          type = MetricType.Activity,
                      ),
                  entry =
                      ReportMetricEntryModel(
                          target = 55,
                          value = 45,
                          notes = "Almost at target",
                          internalComment = "Not quite there yet",
                          modifiedTime = Instant.ofEpochSecond(3000),
                          modifiedBy = user.userId,
                      )),
              ReportStandardMetricModel(
                  metric =
                      StandardMetricModel(
                          id = standardMetricId2,
                          component = MetricComponent.Community,
                          description = "Community metric description",
                          name = "Community Metric",
                          reference = "10.0",
                          type = MetricType.Outcome,
                      ),
                  entry =
                      ReportMetricEntryModel(
                          target = 25,
                          modifiedTime = Instant.ofEpochSecond(1500),
                          modifiedBy = user.userId,
                      )),
          )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.Seedlings,
          target = 1000,
          modifiedTime = Instant.ofEpochSecond(2500),
          modifiedBy = user.userId,
      )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SeedsCollected,
          target = 2000,
          systemValue = 1800,
          systemTime = Instant.ofEpochSecond(8000),
          modifiedTime = Instant.ofEpochSecond(500),
          modifiedBy = user.userId,
      )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.TreesPlanted,
          target = 600,
          systemValue = 300,
          systemTime = Instant.ofEpochSecond(7000),
          overrideValue = 250,
          modifiedTime = Instant.ofEpochSecond(700),
          modifiedBy = user.userId,
      )

      // These are ordered by reference.
      val systemMetrics =
          listOf(
              ReportSystemMetricModel(
                  metric = SystemMetric.SeedsCollected,
                  entry =
                      ReportSystemMetricEntryModel(
                          target = 2000,
                          systemValue = 1800,
                          systemTime = Instant.ofEpochSecond(8000),
                          modifiedTime = Instant.ofEpochSecond(500),
                          modifiedBy = user.userId,
                      )),
              ReportSystemMetricModel(
                  metric = SystemMetric.Seedlings,
                  entry =
                      ReportSystemMetricEntryModel(
                          target = 1000,
                          systemValue = 0,
                          modifiedTime = Instant.ofEpochSecond(2500),
                          modifiedBy = user.userId,
                      )),
              ReportSystemMetricModel(
                  metric = SystemMetric.TreesPlanted,
                  entry =
                      ReportSystemMetricEntryModel(
                          target = 600,
                          systemValue = 300,
                          systemTime = Instant.ofEpochSecond(7000),
                          overrideValue = 250,
                          modifiedTime = Instant.ofEpochSecond(700),
                          modifiedBy = user.userId,
                      )),
              ReportSystemMetricModel(
                  metric = SystemMetric.SpeciesPlanted,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 0,
                      )),
              ReportSystemMetricModel(
                  metric = SystemMetric.MortalityRate,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = -1,
                      )),
          )

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
              projectMetrics = projectMetrics,
              standardMetrics = standardMetrics,
              systemMetrics = systemMetrics)

      assertEquals(listOf(reportModel), store.fetch(includeMetrics = true))
    }

    @Test
    fun `queries Terraware data for system metrics`() {
      insertProjectReportConfig()
      insertReport(
          status = ReportStatus.NotSubmitted,
          startDate = LocalDate.of(2025, Month.JANUARY, 1),
          endDate = LocalDate.of(2025, Month.MARCH, 31))

      val otherProjectId = insertProject()
      val facilityId1 = insertFacility()
      val facilityId2 = insertFacility()

      // Seeds Collected
      listOf(
              AccessionsRow(
                  facilityId = facilityId1,
                  projectId = projectId,
                  collectedDate = LocalDate.of(2025, Month.JANUARY, 11),
                  estSeedCount = 25,
                  remainingQuantity = BigDecimal(25),
                  remainingUnitsId = SeedQuantityUnits.Seeds,
                  stateId = AccessionState.Processing,
              ),
              // Used-up accession
              AccessionsRow(
                  facilityId = facilityId1,
                  projectId = projectId,
                  collectedDate = LocalDate.of(2025, Month.FEBRUARY, 21),
                  estSeedCount = 0,
                  remainingQuantity = BigDecimal(0),
                  remainingUnitsId = SeedQuantityUnits.Seeds,
                  stateId = AccessionState.UsedUp,
                  totalWithdrawnCount = 35),
              // Weight-based accession
              AccessionsRow(
                  facilityId = facilityId2,
                  projectId = projectId,
                  collectedDate = LocalDate.of(2025, Month.MARCH, 17),
                  estSeedCount = 32,
                  remainingGrams = BigDecimal(32),
                  remainingQuantity = BigDecimal(32),
                  remainingUnitsId = SeedQuantityUnits.Grams,
                  stateId = AccessionState.Processing,
                  subsetCount = 10,
                  subsetWeightGrams = BigDecimal(10),
                  totalWithdrawnCount = 6,
                  totalWithdrawnWeightGrams = BigDecimal(6),
                  totalWithdrawnWeightUnitsId = SeedQuantityUnits.Grams,
                  totalWithdrawnWeightQuantity = BigDecimal(6),
              ),
              // Outside of report date range
              AccessionsRow(
                  facilityId = facilityId1,
                  projectId = projectId,
                  collectedDate = LocalDate.of(2024, Month.DECEMBER, 25),
                  estSeedCount = 2500,
                  remainingQuantity = BigDecimal(2500),
                  remainingUnitsId = SeedQuantityUnits.Seeds,
                  stateId = AccessionState.Processing,
              ),
              // Different project
              AccessionsRow(
                  facilityId = facilityId2,
                  projectId = otherProjectId,
                  collectedDate = LocalDate.of(2025, Month.JANUARY, 25),
                  estSeedCount = 1500,
                  remainingQuantity = BigDecimal(1500),
                  remainingUnitsId = SeedQuantityUnits.Seeds,
                  stateId = AccessionState.Processing,
              ),
          )
          .forEach { insertAccession(it) }

      val speciesId = insertSpecies()
      val otherSpeciesId = insertSpecies()

      val batchId1 =
          insertBatch(
              BatchesRow(
                  facilityId = facilityId1,
                  projectId = projectId,
                  addedDate = LocalDate.of(2025, Month.JANUARY, 30),
                  notReadyQuantity = 15,
                  germinatingQuantity = 7,
                  readyQuantity = 3,
                  totalLost = 100,
                  speciesId = speciesId,
              ))

      val batchId2 =
          insertBatch(
              BatchesRow(
                  facilityId = facilityId2,
                  projectId = projectId,
                  addedDate = LocalDate.of(2025, Month.FEBRUARY, 14),
                  notReadyQuantity = 4,
                  germinatingQuantity = 3,
                  readyQuantity = 2,
                  totalLost = 100,
                  speciesId = otherSpeciesId,
              ))

      // Other project
      val otherBatchId =
          insertBatch(
              BatchesRow(
                  facilityId = facilityId1,
                  projectId = otherProjectId,
                  addedDate = LocalDate.of(2025, Month.MARCH, 6),
                  notReadyQuantity = 100,
                  germinatingQuantity = 100,
                  readyQuantity = 100,
                  totalLost = 100,
                  speciesId = speciesId,
              ))

      // Outside of date range
      val outdatedBatchId =
          insertBatch(
              BatchesRow(
                  facilityId = facilityId2,
                  projectId = projectId,
                  addedDate = LocalDate.of(2024, Month.DECEMBER, 25),
                  notReadyQuantity = 100,
                  germinatingQuantity = 100,
                  readyQuantity = 100,
                  totalLost = 100,
                  speciesId = speciesId,
              ))

      val outplantWithdrawalId1 =
          insertWithdrawal(
              purpose = WithdrawalPurpose.OutPlant,
              withdrawnDate = LocalDate.of(2025, Month.MARCH, 30))
      insertBatchWithdrawal(
          batchId = batchId1,
          withdrawalId = outplantWithdrawalId1,
          readyQuantityWithdrawn = 10,
      )

      // Not counted towards seedlings, but counted towards planting
      insertBatchWithdrawal(
          batchId = otherBatchId,
          withdrawalId = outplantWithdrawalId1,
          readyQuantityWithdrawn = 8,
      )
      insertBatchWithdrawal(
          batchId = outdatedBatchId,
          withdrawalId = outplantWithdrawalId1,
          readyQuantityWithdrawn = 9,
      )

      val outplantWithdrawalId2 =
          insertWithdrawal(
              purpose = WithdrawalPurpose.OutPlant,
              withdrawnDate = LocalDate.of(2025, Month.MARCH, 27))
      insertBatchWithdrawal(
          batchId = batchId1,
          withdrawalId = outplantWithdrawalId2,
          readyQuantityWithdrawn = 6,
      )

      // This will count towards the seedlings metric, but not the trees planted metric.
      // This includes two species, but does not count towards species planted.
      val futureWithdrawalId =
          insertWithdrawal(
              purpose = WithdrawalPurpose.OutPlant,
              withdrawnDate = LocalDate.of(2025, Month.MAY, 30))
      insertBatchWithdrawal(
          batchId = batchId1,
          withdrawalId = futureWithdrawalId,
          readyQuantityWithdrawn = 7,
      )
      insertBatchWithdrawal(
          batchId = batchId2,
          withdrawalId = futureWithdrawalId,
          readyQuantityWithdrawn = 2,
      )

      val otherWithdrawalId =
          insertWithdrawal(
              purpose = WithdrawalPurpose.Other,
              withdrawnDate = LocalDate.of(2025, Month.MARCH, 30))
      insertBatchWithdrawal(
          batchId = batchId1,
          withdrawalId = otherWithdrawalId,
          germinatingQuantityWithdrawn = 1,
          notReadyQuantityWithdrawn = 2,
      )
      insertBatchWithdrawal(
          batchId = batchId2,
          withdrawalId = otherWithdrawalId,
          germinatingQuantityWithdrawn = 4,
          notReadyQuantityWithdrawn = 3,
      )

      val deadWithdrawalId =
          insertWithdrawal(
              purpose = WithdrawalPurpose.Dead, withdrawnDate = LocalDate.of(2025, Month.MARCH, 30))
      insertBatchWithdrawal(
          batchId = batchId1,
          withdrawalId = deadWithdrawalId,
          germinatingQuantityWithdrawn = 6,
      )
      insertBatchWithdrawal(
          batchId = batchId2,
          withdrawalId = deadWithdrawalId,
          germinatingQuantityWithdrawn = 8,
      )

      // This will not be counted towards seedlings, to prevent double-counting
      val nurseryTransferWithdrawalId =
          insertWithdrawal(
              purpose = WithdrawalPurpose.NurseryTransfer,
              withdrawnDate = LocalDate.of(2025, Month.MARCH, 30))
      insertBatchWithdrawal(
          batchId = batchId1,
          withdrawalId = nurseryTransferWithdrawalId,
          readyQuantityWithdrawn = 100,
          germinatingQuantityWithdrawn = 100,
          notReadyQuantityWithdrawn = 100,
      )
      insertBatchWithdrawal(
          batchId = batchId2,
          withdrawalId = nurseryTransferWithdrawalId,
          readyQuantityWithdrawn = 100,
          germinatingQuantityWithdrawn = 100,
          notReadyQuantityWithdrawn = 100,
      )

      // These two will be counted towards the seedlings metric, but should negate each other
      // These should not be counted towards species planted metric
      val undoneWithdrawalId =
          insertWithdrawal(
              purpose = WithdrawalPurpose.OutPlant,
              withdrawnDate = LocalDate.of(2025, Month.MARCH, 28))
      val undoWithdrawalId =
          insertWithdrawal(
              purpose = WithdrawalPurpose.Undo,
              undoesWithdrawalId = undoneWithdrawalId,
              withdrawnDate = LocalDate.of(2025, Month.MARCH, 29),
          )
      insertBatchWithdrawal(
          batchId = batchId1,
          withdrawalId = undoneWithdrawalId,
          readyQuantityWithdrawn = 100,
      )
      insertBatchWithdrawal(
          batchId = batchId2,
          withdrawalId = undoneWithdrawalId,
          readyQuantityWithdrawn = 100,
      )
      insertBatchWithdrawal(
          batchId = batchId1,
          withdrawalId = undoWithdrawalId,
          readyQuantityWithdrawn = -100,
      )
      insertBatchWithdrawal(
          batchId = batchId2,
          withdrawalId = undoWithdrawalId,
          readyQuantityWithdrawn = -100,
      )

      val plantingSiteId = insertPlantingSite(projectId = projectId)
      val otherPlantingSiteId = insertPlantingSite(projectId = otherProjectId)

      val deliveryId =
          insertDelivery(
              plantingSiteId = plantingSiteId,
              withdrawalId = outplantWithdrawalId1,
          )
      insertPlanting(
          plantingSiteId = plantingSiteId,
          deliveryId = deliveryId,
          numPlants = 27, // This should match up with the number of seedlings withdrawn
      )

      // These two should negate each other, in both tree planted and species planted
      val undoneDeliveryId =
          insertDelivery(
              plantingSiteId = plantingSiteId,
              withdrawalId = undoneWithdrawalId,
          )
      insertPlanting(
          plantingSiteId = plantingSiteId,
          deliveryId = undoneDeliveryId,
          numPlants = 200,
      )
      val undoDeliveryId =
          insertDelivery(
              plantingSiteId = plantingSiteId,
              withdrawalId = undoWithdrawalId,
          )
      insertPlanting(
          plantingSiteId = plantingSiteId,
          plantingTypeId = PlantingType.Undo,
          deliveryId = undoDeliveryId,
          numPlants = -200,
      )

      // Does not count towards trees or speces planted, since planting site is outside of project
      val otherDeliveryId =
          insertDelivery(
              plantingSiteId = otherPlantingSiteId,
              withdrawalId = outplantWithdrawalId2,
          )
      insertPlanting(
          plantingSiteId = otherPlantingSiteId,
          deliveryId = otherDeliveryId,
          numPlants = 6,
      )

      // Does not count, since the withdrawal date is not within the report date range
      val futureDeliveryId =
          insertDelivery(
              plantingSiteId = plantingSiteId,
              withdrawalId = futureWithdrawalId,
          )
      insertPlanting(
          plantingSiteId = plantingSiteId,
          deliveryId = futureDeliveryId,
          numPlants = 9,
      )

      assertEquals(
          listOf(
              ReportSystemMetricModel(
                  metric = SystemMetric.SeedsCollected,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 98,
                      )),
              ReportSystemMetricModel(
                  metric = SystemMetric.Seedlings,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 83,
                      )),
              ReportSystemMetricModel(
                  metric = SystemMetric.TreesPlanted,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 27,
                      )),
              ReportSystemMetricModel(
                  metric = SystemMetric.SpeciesPlanted,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 1,
                      )),
              ReportSystemMetricModel(
                  metric = SystemMetric.MortalityRate,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = -1,
                      )),
          ),
          store.fetch(includeFuture = true, includeMetrics = true).first().systemMetrics)
    }

    @Test
    fun `hides Not Needed reports by default`() {
      val configId = insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotNeeded)

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              status = ReportStatus.NotNeeded,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH)

      assertEquals(emptyList<ReportModel>(), store.fetch())

      assertEquals(listOf(reportModel), store.fetch(includeArchived = true))
    }

    @Test
    fun `hides reports ending more than 30 days into the future by default`() {
      val today = LocalDate.of(2025, Month.FEBRUARY, 15)
      clock.instant = today.atStartOfDay().toInstant(ZoneOffset.UTC)

      val configId = insertProjectReportConfig()
      val reportId = insertReport(startDate = today, endDate = today.plusDays(31))

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              status = ReportStatus.NotSubmitted,
              startDate = today,
              endDate = today.plusDays(31),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH)

      assertEquals(emptyList<ReportModel>(), store.fetch())

      assertEquals(listOf(reportModel), store.fetch(includeFuture = true))
    }

    @Test
    fun `hides internal comment for non global role users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val configId = insertProjectReportConfig()
      val reportId = insertReport(internalComment = "internal comment")

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
              internalComment = "internal comment")

      assertEquals(
          listOf(reportModel.copy(internalComment = null)),
          store.fetch(),
          "Org user cannot see internal comment")

      insertUserGlobalRole(role = GlobalRole.ReadOnly)

      assertEquals(
          listOf(reportModel), store.fetch(), "Read-only Global role can see internal comment")
    }

    @Test
    fun `filters by projectId or end year`() {
      val configId = insertProjectReportConfig()
      val reportId1 = insertReport(endDate = LocalDate.of(2030, Month.DECEMBER, 31))
      val reportId2 = insertReport(endDate = LocalDate.of(2035, Month.DECEMBER, 31))

      val otherProjectId = insertProject()
      val otherConfigId = insertProjectReportConfig()
      val otherReportId1 = insertReport(endDate = LocalDate.of(2035, Month.DECEMBER, 31))
      val otherReportId2 = insertReport(endDate = LocalDate.of(2040, Month.DECEMBER, 31))

      val reportModel1 =
          ReportModel(
              id = reportId1,
              configId = configId,
              projectId = projectId,
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.of(2030, Month.DECEMBER, 31),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
          )

      val reportModel2 =
          reportModel1.copy(
              id = reportId2,
              endDate = LocalDate.of(2035, Month.DECEMBER, 31),
          )

      val otherReportModel1 =
          reportModel2.copy(
              id = otherReportId1,
              configId = otherConfigId,
              projectId = otherProjectId,
          )

      val otherReportModel2 =
          otherReportModel1.copy(
              id = otherReportId2,
              endDate = LocalDate.of(2040, Month.DECEMBER, 31),
          )

      clock.instant = LocalDate.of(2041, Month.JANUARY, 1).atStartOfDay().toInstant(ZoneOffset.UTC)

      assertEquals(
          setOf(reportModel1, reportModel2, otherReportModel1, otherReportModel2),
          store.fetch().toSet(),
          "Fetches all")

      assertEquals(
          setOf(reportModel1, reportModel2),
          store.fetch(projectId = projectId).toSet(),
          "Fetches by projectId")

      assertEquals(
          setOf(reportModel2, otherReportModel1),
          store.fetch(year = 2035).toSet(),
          "Fetches by year")

      assertEquals(
          listOf(reportModel2),
          store.fetch(projectId = projectId, year = 2035),
          "Fetches by projectId and year")
    }

    @Test
    fun `returns only visible reports`() {
      deleteOrganizationUser(organizationId = organizationId)
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val configId = insertProjectReportConfig()
      val reportId = insertReport()

      val secondProjectId = insertProject()
      val secondConfigId = insertProjectReportConfig()
      val secondReportId = insertReport()

      insertOrganization()
      val otherProjectId = insertProject()
      val otherConfigId = insertProjectReportConfig()
      val otherReportId = insertReport()

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
          )

      val secondReportModel =
          reportModel.copy(
              id = secondReportId,
              configId = secondConfigId,
              projectId = secondProjectId,
          )

      val otherReportModel =
          reportModel.copy(
              id = otherReportId,
              configId = otherConfigId,
              projectId = otherProjectId,
          )

      assertEquals(
          emptyList<ReportModel>(),
          store.fetch(),
          "User not in organizations cannot see the reports")

      insertOrganizationUser(organizationId = organizationId, role = Role.Contributor)
      assertEquals(emptyList<ReportModel>(), store.fetch(), "Contributor cannot see the reports")

      insertOrganizationUser(organizationId = organizationId, role = Role.Manager)
      assertEquals(
          setOf(reportModel, secondReportModel),
          store.fetch().toSet(),
          "Manager can see project reports within the organization")

      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      assertEquals(
          setOf(reportModel, secondReportModel, otherReportModel),
          store.fetch().toSet(),
          "Read-only admin user can see all project reports")
    }
  }

  @Nested
  inner class ReviewReport {
    @Test
    fun `throws Access Denied Exception for non-TFExpert users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.ReadOnly)

      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.Submitted)

      assertThrows<AccessDeniedException> {
        store.reviewReport(
            reportId = reportId,
            status = ReportStatus.Approved,
            feedback = "feedback",
            internalComment = "internal comment",
        )
      }

      deleteUserGlobalRole(role = GlobalRole.ReadOnly)
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      assertDoesNotThrow {
        store.reviewReport(
            reportId = reportId,
            status = ReportStatus.Approved,
            feedback = "feedback",
            internalComment = "internal comment",
        )
      }
    }

    @Test
    fun `throws Illegal State Exception if updating status of NotSubmitted or NotNeeded Reports`() {
      insertProjectReportConfig()
      val notSubmittedReportId = insertReport(status = ReportStatus.NotSubmitted)
      val notNeededReportId = insertReport(status = ReportStatus.NotNeeded)

      assertThrows<IllegalStateException> {
        store.reviewReport(
            reportId = notSubmittedReportId,
            status = ReportStatus.Approved,
            feedback = "feedback",
            internalComment = "internal comment",
        )
      }
      assertThrows<IllegalStateException> {
        store.reviewReport(
            reportId = notNeededReportId,
            status = ReportStatus.Approved,
            feedback = "feedback",
            internalComment = "internal comment",
        )
      }
      assertDoesNotThrow {
        store.reviewReport(
            reportId = notSubmittedReportId,
            status = ReportStatus.NotSubmitted,
            feedback = "feedback",
            internalComment = "internal comment",
        )
        store.reviewReport(
            reportId = notNeededReportId,
            status = ReportStatus.NotNeeded,
            feedback = "feedback",
            internalComment = "internal comment",
        )
      }
    }

    @Test
    fun `updates relevant columns`() {
      val otherUserId = insertUser()

      insertProjectReportConfig()
      val reportId =
          insertReport(
              status = ReportStatus.Submitted,
              modifiedBy = otherUserId,
              modifiedTime = Instant.ofEpochSecond(3000),
          )

      val existingReport = reportsDao.fetchOneById(reportId)!!

      clock.instant = Instant.ofEpochSecond(20000)

      store.reviewReport(
          reportId = reportId,
          status = ReportStatus.NeedsUpdate,
          feedback = "feedback",
          internalComment = "internal comment",
      )

      val updatedReport =
          existingReport.copy(
              statusId = ReportStatus.NeedsUpdate,
              feedback = "feedback",
              internalComment = "internal comment",
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )

      assertTableEquals(ReportsRecord(updatedReport))
    }
  }

  @Nested
  inner class ReviewReportMetrics {
    @Test
    fun `throws exception for non-TFExpert users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.ReadOnly)

      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.Submitted)

      assertThrows<AccessDeniedException> { store.reviewReportMetrics(reportId = reportId) }

      deleteUserGlobalRole(role = GlobalRole.ReadOnly)
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      assertDoesNotThrow { store.reviewReportMetrics(reportId = reportId) }
    }

    @Test
    fun `upserts values and internalComment for existing and non-existing report metric rows`() {
      val otherUserId = insertUser()

      val standardMetricId1 =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Climate standard metric description",
              name = "Climate Standard Metric",
              reference = "2.1",
              type = MetricType.Activity,
          )

      val standardMetricId2 =
          insertStandardMetric(
              component = MetricComponent.Community,
              description = "Community metric description",
              name = "Community Metric",
              reference = "10.0",
              type = MetricType.Outcome,
          )

      val standardMetricId3 =
          insertStandardMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project objectives metric description",
              name = "Project Objectives Metric",
              reference = "2.0",
              type = MetricType.Impact,
          )

      // This has no entry and will not have any updates
      insertStandardMetric(
          component = MetricComponent.Biodiversity,
          description = "Biodiversity metric description",
          name = "Biodiversity Metric",
          reference = "7.0",
          type = MetricType.Impact,
      )

      val projectMetricId =
          insertProjectMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project Metric description",
              name = "Project Metric Name",
              reference = "2.0",
              type = MetricType.Activity,
          )

      val configId = insertProjectReportConfig()
      val reportId =
          insertReport(
              status = ReportStatus.Submitted,
              createdBy = otherUserId,
              createdTime = Instant.ofEpochSecond(1500),
              submittedBy = otherUserId,
              submittedTime = Instant.ofEpochSecond(3000),
          )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          target = 55,
          value = 45,
          notes = "Existing metric 1 notes",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = otherUserId,
      )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
          target = 30,
          value = null,
          notes = "Existing metric 2 notes",
          internalComment = "Existing metric 2 internal comments",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      // At this point, the report has entries for metric 1 and 2, no entry for metric 3 and 4
      clock.instant = Instant.ofEpochSecond(9000)

      // We add new entries for metric 2 and 3. Metric 1 and 4 are not modified
      store.reviewReportMetrics(
          reportId = reportId,
          standardMetricEntries =
              mapOf(
                  standardMetricId2 to
                      ReportMetricEntryModel(
                          target = 99,
                          value = 88,
                          notes = "New metric 2 notes",
                          internalComment = "New metric 2 internal comment",

                          // These fields are ignored
                          modifiedTime = Instant.EPOCH,
                          modifiedBy = UserId(99),
                      ),
                  standardMetricId3 to
                      ReportMetricEntryModel(
                          target = 50,
                          value = 45,
                          notes = "New metric 3 notes",
                          internalComment = "New metric 3 internal comment",
                      ),
              ),
          projectMetricEntries =
              mapOf(
                  projectMetricId to
                      ReportMetricEntryModel(
                          target = 100,
                          value = 50,
                          notes = "Project metric notes",
                          internalComment = "Project metric internal comment",
                      ),
              ),
      )

      assertTableEquals(
          listOf(
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId1,
                  target = 55,
                  value = 45,
                  notes = "Existing metric 1 notes",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = otherUserId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId2,
                  target = 99,
                  value = 88,
                  notes = "New metric 2 notes",
                  internalComment = "New metric 2 internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId3,
                  target = 50,
                  value = 45,
                  notes = "New metric 3 notes",
                  internalComment = "New metric 3 internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              // Standard metric 4 is not inserted since there was no updates
          ),
          "Reports standard metrics table")

      assertTableEquals(
          listOf(
              ReportProjectMetricsRecord(
                  reportId = reportId,
                  projectMetricId = projectMetricId,
                  target = 100,
                  value = 50,
                  notes = "Project metric notes",
                  internalComment = "Project metric internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              )))

      assertTableEquals(
          ReportsRecord(
              id = reportId,
              configId = configId,
              projectId = projectId,
              statusId = ReportStatus.Submitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = otherUserId,
              createdTime = Instant.ofEpochSecond(1500),
              submittedBy = otherUserId,
              submittedTime = Instant.ofEpochSecond(3000),
              // Modified time and modified by are updated
              modifiedBy = user.userId,
              modifiedTime = Instant.ofEpochSecond(9000),
          ),
          "Reports table")
    }
  }

  @Nested
  inner class UpdateReportMetrics {
    @Test
    fun `throws exception for non-organization users`() {
      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted)
      deleteOrganizationUser()
      assertThrows<AccessDeniedException> { store.updateReportMetrics(reportId) }
    }

    @Test
    fun `throws exception for project metrics not part of the project`() {
      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted)

      val otherProjectId = insertProject()
      val metricId = insertProjectMetric(projectId = otherProjectId)

      assertThrows<IllegalArgumentException> {
        store.updateReportMetrics(
            reportId = reportId,
            projectMetricEntries = mapOf(metricId to ReportMetricEntryModel(target = 50)),
        )
      }
    }

    @Test
    fun `throws exception for reports not in NotSubmitted`() {
      insertProjectReportConfig()
      val notNeededReportId = insertReport(status = ReportStatus.NotNeeded)
      val submittedReportId = insertReport(status = ReportStatus.Submitted)
      val needsUpdateReportId = insertReport(status = ReportStatus.NeedsUpdate)
      val approvedReportId = insertReport(status = ReportStatus.Approved)

      assertThrows<IllegalStateException> { store.updateReportMetrics(notNeededReportId) }
      assertThrows<IllegalStateException> { store.updateReportMetrics(submittedReportId) }
      assertThrows<IllegalStateException> { store.updateReportMetrics(needsUpdateReportId) }
      assertThrows<IllegalStateException> { store.updateReportMetrics(approvedReportId) }
    }

    @Test
    fun `upserts values and targets for existing and non-existing report metric rows`() {
      val otherUserId = insertUser()

      val standardMetricId1 =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Climate standard metric description",
              name = "Climate Standard Metric",
              reference = "2.1",
              type = MetricType.Activity,
          )

      val standardMetricId2 =
          insertStandardMetric(
              component = MetricComponent.Community,
              description = "Community metric description",
              name = "Community Metric",
              reference = "10.0",
              type = MetricType.Outcome,
          )

      val standardMetricId3 =
          insertStandardMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project objectives metric description",
              name = "Project Objectives Metric",
              reference = "2.0",
              type = MetricType.Impact,
          )

      // This has no entry and will not have any updates
      insertStandardMetric(
          component = MetricComponent.Biodiversity,
          description = "Biodiversity metric description",
          name = "Biodiversity Metric",
          reference = "7.0",
          type = MetricType.Impact,
      )

      val projectMetricId =
          insertProjectMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project Metric description",
              name = "Project Metric Name",
              reference = "2.0",
              type = MetricType.Activity,
          )

      val configId = insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted, createdBy = otherUserId)

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          target = 55,
          value = 45,
          notes = "Existing metric 1 notes",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = otherUserId,
      )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
          target = 30,
          value = null,
          notes = "Existing metric 2 notes",
          internalComment = "Existing metric 2 internal comments",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )
      // At this point, the report has entries for standard metric 1 and 2, and no entry for
      // standard metric 3 and 4

      clock.instant = Instant.ofEpochSecond(9000)

      // We add new entries for standard metric 2 and 3. Standard metric 1 and 4 are not modified.
      // We also add a new entry for project metric
      store.updateReportMetrics(
          reportId = reportId,
          standardMetricEntries =
              mapOf(
                  standardMetricId2 to
                      ReportMetricEntryModel(
                          target = 99,
                          value = 88,
                          notes = "New metric 2 notes",

                          // These fields are ignored
                          internalComment = "Not permitted to write internal comment",
                          modifiedTime = Instant.EPOCH,
                          modifiedBy = UserId(99),
                      ),
                  standardMetricId3 to
                      ReportMetricEntryModel(
                          target = 50,
                          value = null,
                          notes = "New metric 3 notes",
                      ),
              ),
          projectMetricEntries =
              mapOf(
                  projectMetricId to
                      ReportMetricEntryModel(
                          target = 100,
                          value = 50,
                          notes = "Project metric notes",
                      ),
              ),
      )

      assertTableEquals(
          listOf(
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId1,
                  target = 55,
                  value = 45,
                  notes = "Existing metric 1 notes",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = otherUserId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId2,
                  target = 99,
                  value = 88,
                  notes = "New metric 2 notes",
                  internalComment = "Existing metric 2 internal comments",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId3,
                  target = 50,
                  notes = "New metric 3 notes",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              // Standard metric 4 is not inserted since there was no updates
          ),
          "Reports standard metrics table")

      assertTableEquals(
          listOf(
              ReportProjectMetricsRecord(
                  reportId = reportId,
                  projectMetricId = projectMetricId,
                  target = 100,
                  value = 50,
                  notes = "Project metric notes",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              )))

      assertTableEquals(
          ReportsRecord(
              id = reportId,
              configId = configId,
              projectId = projectId,
              statusId = ReportStatus.NotSubmitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = otherUserId,
              createdTime = Instant.EPOCH,
              // Modified time and modified by are updated
              modifiedBy = user.userId,
              modifiedTime = Instant.ofEpochSecond(9000),
          ),
          "Reports table")
    }
  }

  @Nested
  inner class SubmitReport {
    @Test
    fun `throws exception for non-organization users`() {
      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted)
      deleteOrganizationUser()
      assertThrows<AccessDeniedException> { store.submitReport(reportId) }
    }

    @Test
    fun `throws exception for reports not in NotSubmitted`() {
      insertProjectReportConfig()
      val notNeededReportId = insertReport(status = ReportStatus.NotNeeded)
      val submittedReportId = insertReport(status = ReportStatus.Submitted)
      val needsUpdateReportId = insertReport(status = ReportStatus.NeedsUpdate)
      val approvedReportId = insertReport(status = ReportStatus.Approved)

      assertThrows<IllegalStateException> { store.submitReport(notNeededReportId) }
      assertThrows<IllegalStateException> { store.submitReport(submittedReportId) }
      assertThrows<IllegalStateException> { store.submitReport(needsUpdateReportId) }
      assertThrows<IllegalStateException> { store.submitReport(approvedReportId) }
    }

    @Test
    fun `throws exception for reports missing metric values or targets`() {
      val metricId = insertStandardMetric()

      insertProjectReportConfig()
      val missingBothReportId = insertReport()
      val missingTargetReportId = insertReport()
      val missingValueReportId = insertReport()

      insertReportStandardMetric(
          reportId = missingTargetReportId,
          metricId = metricId,
          value = 25,
      )

      insertReportStandardMetric(
          reportId = missingValueReportId,
          metricId = metricId,
          target = 25,
      )

      assertThrows<IllegalStateException> { store.submitReport(missingBothReportId) }
      assertThrows<IllegalStateException> { store.submitReport(missingTargetReportId) }
      assertThrows<IllegalStateException> { store.submitReport(missingValueReportId) }
    }

    @Test
    fun `sets report to submitted status and publishes event`() {
      val configId = insertProjectReportConfig()
      val otherUserId = insertUser()
      val reportId =
          insertReport(
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.of(2025, Month.JANUARY, 1),
              endDate = LocalDate.of(2025, Month.DECEMBER, 31),
              createdBy = otherUserId,
              modifiedBy = otherUserId)

      clock.instant = Instant.ofEpochSecond(6000)

      store.submitReport(reportId)

      assertTableEquals(
          ReportsRecord(
              id = reportId,
              configId = configId,
              projectId = projectId,
              statusId = ReportStatus.Submitted,
              startDate = LocalDate.of(2025, Month.JANUARY, 1),
              endDate = LocalDate.of(2025, Month.DECEMBER, 31),
              createdBy = otherUserId,
              createdTime = Instant.EPOCH,
              modifiedBy = otherUserId,
              modifiedTime = Instant.EPOCH,
              submittedBy = currentUser().userId,
              submittedTime = clock.instant,
          ),
      )

      eventPublisher.assertEventPublished(ReportSubmittedEvent(reportId))
    }
  }

  @Nested
  inner class FetchProjectReportConfigs {
    @Test
    fun `throws exception for non accelerator admin users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      assertThrows<AccessDeniedException> { store.fetchProjectReportConfigs() }
    }

    @Test
    fun `queries by project`() {
      val otherProjectId = insertProject()

      val projectConfigId1 =
          insertProjectReportConfig(
              projectId = projectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2028, Month.MARCH, 2),
          )

      val projectConfigId2 =
          insertProjectReportConfig(
              projectId = projectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.JANUARY, 7),
              reportingEndDate = LocalDate.of(2031, Month.MAY, 9),
          )

      val otherProjectConfigId1 =
          insertProjectReportConfig(
              projectId = otherProjectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2027, Month.FEBRUARY, 6),
              reportingEndDate = LocalDate.of(2031, Month.JULY, 9),
          )

      val otherProjectConfigId2 =
          insertProjectReportConfig(
              projectId = otherProjectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2031, Month.JANUARY, 18),
              reportingEndDate = LocalDate.of(2039, Month.MAY, 31),
          )

      val projectConfigModel1 =
          ExistingProjectReportConfigModel(
              id = projectConfigId1,
              projectId = projectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2028, Month.MARCH, 2),
          )

      val projectConfigModel2 =
          ExistingProjectReportConfigModel(
              id = projectConfigId2,
              projectId = projectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.JANUARY, 7),
              reportingEndDate = LocalDate.of(2031, Month.MAY, 9),
          )

      val otherProjectConfigModel1 =
          ExistingProjectReportConfigModel(
              id = otherProjectConfigId1,
              projectId = otherProjectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2027, Month.FEBRUARY, 6),
              reportingEndDate = LocalDate.of(2031, Month.JULY, 9),
          )

      val otherProjectConfigModel2 =
          ExistingProjectReportConfigModel(
              id = otherProjectConfigId2,
              projectId = otherProjectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2031, Month.JANUARY, 18),
              reportingEndDate = LocalDate.of(2039, Month.MAY, 31),
          )

      assertEquals(
          setOf(projectConfigModel1, projectConfigModel2),
          store.fetchProjectReportConfigs(projectId = projectId).toSet(),
          "fetches by projectId")

      assertEquals(
          setOf(
              projectConfigModel1,
              projectConfigModel2,
              otherProjectConfigModel1,
              otherProjectConfigModel2),
          store.fetchProjectReportConfigs().toSet(),
          "fetches all project configs")
    }
  }

  @Nested
  inner class InsertProjectReportConfig {
    @Test
    fun `throws exception for non accelerator admin users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val config =
          NewProjectReportConfigModel(
              id = null,
              projectId = projectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2028, Month.MARCH, 2),
          )

      assertThrows<AccessDeniedException> { store.insertProjectReportConfig(config) }
    }

    @Test
    fun `inserts config record and creates reports for annual frequency`() {
      clock.instant = Instant.ofEpochSecond(9000)

      val config =
          NewProjectReportConfigModel(
              id = null,
              projectId = projectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2028, Month.MARCH, 2),
          )

      store.insertProjectReportConfig(config)

      val configId = projectReportConfigsDao.fetchByProjectId(projectId).single().id

      assertTableEquals(
          ProjectReportConfigsRecord(
              id = configId,
              projectId = projectId,
              reportFrequencyId = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2028, Month.MARCH, 2),
          ),
          "Project report config tables")

      assertTableEquals(
          listOf(
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2025, Month.MAY, 5),
                  endDate = LocalDate.of(2025, Month.DECEMBER, 31),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2026, Month.JANUARY, 1),
                  endDate = LocalDate.of(2026, Month.DECEMBER, 31),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2027, Month.JANUARY, 1),
                  endDate = LocalDate.of(2027, Month.DECEMBER, 31),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2028, Month.JANUARY, 1),
                  endDate = LocalDate.of(2028, Month.MARCH, 2),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              )),
          "Reports table",
      )
    }

    @Test
    fun `inserts config record and creates reports for quarterly frequency`() {
      clock.instant = Instant.ofEpochSecond(9000)

      val config =
          NewProjectReportConfigModel(
              id = null,
              projectId = projectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2026, Month.MARCH, 29),
          )

      store.insertProjectReportConfig(config)

      val configId = projectReportConfigsDao.fetchByProjectId(projectId).single().id

      assertTableEquals(
          ProjectReportConfigsRecord(
              id = configId,
              projectId = projectId,
              reportFrequencyId = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2026, Month.MARCH, 29),
          ),
          "Project report config tables")

      assertTableEquals(
          listOf(
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2025, Month.MAY, 5),
                  endDate = LocalDate.of(2025, Month.JUNE, 30),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2025, Month.JULY, 1),
                  endDate = LocalDate.of(2025, Month.SEPTEMBER, 30),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2025, Month.OCTOBER, 1),
                  endDate = LocalDate.of(2025, Month.DECEMBER, 31),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2026, Month.JANUARY, 1),
                  endDate = LocalDate.of(2026, Month.MARCH, 29),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              )),
          "Reports table",
      )
    }
  }
}
