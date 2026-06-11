package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_WITHDRAWALS
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWALS
import com.terraformation.backend.db.tracking.PlantingDateRequestStatus
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_DATE_REQUESTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_DATE_REQUEST_SPECIES
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATES
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATE_SPECIES
import com.terraformation.backend.nursery.event.WithdrawalAssociatedWithPlantingDateRequestEvent
import com.terraformation.backend.plantingmanagement.event.PlantingDateRequestCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingDateRequestSpeciesCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingDateRequestSpeciesDeletedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingDateRequestSpeciesUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingDateRequestSpeciesUpdatedEventValues
import com.terraformation.backend.plantingmanagement.event.PlantingDateRequestUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingDateRequestUpdatedEventValues
import com.terraformation.backend.util.nullIfEquals
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.InstantSource
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@Named
class PlantingDateRequestsStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val seasonHelper: SeasonHelper,
) {

  fun create(
      scheduledPlantingDateId: ScheduledPlantingDateId,
      plantingSeasonId: PlantingSeasonId,
      notes: String? = null,
  ) {
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    seasonHelper.validateSeasonNotClosed(plantingSeasonId)

    val userId = currentUser().userId
    val now = clock.instant()
    val (plantingSiteId, organizationId) =
        seasonHelper.fetchPlantingSiteAndOrganization(plantingSeasonId)

    dslContext.transaction { _ ->
      val date =
          dslContext
              .select(SCHEDULED_PLANTING_DATES.DATE)
              .from(SCHEDULED_PLANTING_DATES)
              .where(SCHEDULED_PLANTING_DATES.ID.eq(scheduledPlantingDateId))
              .fetchOne(SCHEDULED_PLANTING_DATES.DATE)
              ?: throw PlantingSeasonScheduledDateNotFoundException(scheduledPlantingDateId)

      val rowsInserted =
          with(PLANTING_DATE_REQUESTS) {
            dslContext
                .insertInto(PLANTING_DATE_REQUESTS)
                .set(SCHEDULED_PLANTING_DATE_ID, scheduledPlantingDateId)
                .set(DATE, date)
                .set(NOTES, notes)
                .set(CREATED_BY, userId)
                .set(CREATED_TIME, now)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .set(STATUS_ID, PlantingDateRequestStatus.Pending)
                .onConflictDoNothing()
                .execute()
          }

      if (rowsInserted == 0) {
        throw PlantingSeasonDateRequestExistsException(scheduledPlantingDateId)
      }

      insertRequestSpecies(scheduledPlantingDateId)

      eventPublisher.publishEvent(
          PlantingDateRequestCreatedEvent(
              date = date,
              notes = notes,
              organizationId = organizationId,
              plantingSeasonId = plantingSeasonId,
              plantingSiteId = plantingSiteId,
              scheduledPlantingDateId = scheduledPlantingDateId,
              status = PlantingDateRequestStatus.Pending,
          )
      )

      publishSpeciesDiffEvents(
          emptyMap(),
          fetchRequestSpeciesQuantities(scheduledPlantingDateId),
          scheduledPlantingDateId,
          plantingSeasonId,
          plantingSiteId,
          organizationId,
      )
    }
  }

  fun update(
      scheduledPlantingDateId: ScheduledPlantingDateId,
      plantingSeasonId: PlantingSeasonId,
      notes: String? = null,
  ) {
    requirePermissions { updatePlantingSeason(plantingSeasonId) }

    seasonHelper.validateSeasonNotClosed(plantingSeasonId)

    val userId = currentUser().userId
    val now = clock.instant()
    val (plantingSiteId, organizationId) =
        seasonHelper.fetchPlantingSiteAndOrganization(plantingSeasonId)

    seasonHelper.withLockedPlantingSeason(plantingSeasonId) {
      val oldRequest =
          dslContext
              .select(PLANTING_DATE_REQUESTS.DATE, PLANTING_DATE_REQUESTS.NOTES)
              .from(PLANTING_DATE_REQUESTS)
              .where(PLANTING_DATE_REQUESTS.SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId))
              .fetchOne()
      val oldDate = oldRequest?.value1()
      val oldNotes = oldRequest?.value2()
      val oldSpecies = fetchRequestSpeciesQuantities(scheduledPlantingDateId)

      val updatedCount =
          with(PLANTING_DATE_REQUESTS) {
            dslContext
                .update(PLANTING_DATE_REQUESTS)
                .set(
                    DATE,
                    DSL.select(SCHEDULED_PLANTING_DATES.DATE)
                        .from(SCHEDULED_PLANTING_DATES)
                        .where(SCHEDULED_PLANTING_DATES.ID.eq(scheduledPlantingDateId)),
                )
                .set(NOTES, notes)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .where(SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId))
                .execute()
          }

      if (updatedCount == 0) {
        throw PlantingSeasonDateRequestNotFoundException(scheduledPlantingDateId)
      }

      dslContext
          .deleteFrom(PLANTING_DATE_REQUEST_SPECIES)
          .where(
              PLANTING_DATE_REQUEST_SPECIES.SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId)
          )
          .execute()

      insertRequestSpecies(scheduledPlantingDateId)

      val newDate =
          dslContext
              .select(PLANTING_DATE_REQUESTS.DATE)
              .from(PLANTING_DATE_REQUESTS)
              .where(PLANTING_DATE_REQUESTS.SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId))
              .fetchOne(PLANTING_DATE_REQUESTS.DATE)
      val newSpecies = fetchRequestSpeciesQuantities(scheduledPlantingDateId)

      if (oldDate != newDate || oldNotes != notes) {
        eventPublisher.publishEvent(
            PlantingDateRequestUpdatedEvent(
                changedFrom =
                    PlantingDateRequestUpdatedEventValues(
                        date = oldDate.nullIfEquals(newDate),
                        notes = oldNotes.nullIfEquals(notes),
                    ),
                changedTo =
                    PlantingDateRequestUpdatedEventValues(
                        date = newDate.nullIfEquals(oldDate),
                        notes = notes.nullIfEquals(oldNotes),
                    ),
                organizationId = organizationId,
                plantingSeasonId = plantingSeasonId,
                plantingSiteId = plantingSiteId,
                scheduledPlantingDateId = scheduledPlantingDateId,
            )
        )
      }

      publishSpeciesDiffEvents(
          oldSpecies,
          newSpecies,
          scheduledPlantingDateId,
          plantingSeasonId,
          plantingSiteId,
          organizationId,
      )
    }
  }

  @EventListener
  fun on(event: WithdrawalAssociatedWithPlantingDateRequestEvent) {
    updateRequestStatus(event.scheduledPlantingDateRequestId)
  }

  private fun insertRequestSpecies(scheduledPlantingDateId: ScheduledPlantingDateId) {
    with(SCHEDULED_PLANTING_DATE_SPECIES) {
      dslContext
          .insertInto(PLANTING_DATE_REQUEST_SPECIES)
          .select(
              DSL.select(
                      DSL.`val`(scheduledPlantingDateId),
                      SUBSTRATUM_ID,
                      SPECIES_ID,
                      QUANTITY,
                  )
                  .from(SCHEDULED_PLANTING_DATE_SPECIES)
                  .where(SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId))
          )
          .execute()
    }
  }

  private fun fetchRequestSpeciesQuantities(
      scheduledPlantingDateId: ScheduledPlantingDateId
  ): Map<Pair<SubstratumId, SpeciesId>, Int> =
      with(PLANTING_DATE_REQUEST_SPECIES) {
        dslContext
            .select(SUBSTRATUM_ID, SPECIES_ID, QUANTITY)
            .from(PLANTING_DATE_REQUEST_SPECIES)
            .where(SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId))
            .fetch()
            .associate { (it[SUBSTRATUM_ID]!! to it[SPECIES_ID]!!) to it[QUANTITY]!! }
      }

  private fun publishSpeciesDiffEvents(
      oldSpecies: Map<Pair<SubstratumId, SpeciesId>, Int>,
      newSpecies: Map<Pair<SubstratumId, SpeciesId>, Int>,
      scheduledPlantingDateId: ScheduledPlantingDateId,
      plantingSeasonId: PlantingSeasonId,
      plantingSiteId: PlantingSiteId,
      organizationId: OrganizationId,
  ) {
    val affectedSubstrata = (oldSpecies.keys + newSpecies.keys).map { it.first }.toSet()
    if (affectedSubstrata.isEmpty()) return

    val substrataInfo = seasonHelper.fetchSubstrataInfo(affectedSubstrata)

    (oldSpecies.keys + newSpecies.keys).forEach { key ->
      val (substratumId, speciesId) = key
      val info = substrataInfo[substratumId]!!
      val oldQuantity = oldSpecies[key]
      val newQuantity = newSpecies[key]

      when {
        oldQuantity == null && newQuantity != null ->
            eventPublisher.publishEvent(
                PlantingDateRequestSpeciesCreatedEvent(
                    organizationId = organizationId,
                    plantingSeasonId = plantingSeasonId,
                    plantingSiteId = plantingSiteId,
                    quantity = newQuantity,
                    scheduledPlantingDateId = scheduledPlantingDateId,
                    speciesId = speciesId,
                    stratumName = info.stratumName,
                    substratumHistoryId = info.substratumHistoryId,
                    substratumId = substratumId,
                    substratumName = info.substratumName,
                )
            )

        oldQuantity != null && newQuantity == null ->
            eventPublisher.publishEvent(
                PlantingDateRequestSpeciesDeletedEvent(
                    organizationId = organizationId,
                    plantingSeasonId = plantingSeasonId,
                    plantingSiteId = plantingSiteId,
                    scheduledPlantingDateId = scheduledPlantingDateId,
                    speciesId = speciesId,
                    stratumName = info.stratumName,
                    substratumHistoryId = info.substratumHistoryId,
                    substratumId = substratumId,
                    substratumName = info.substratumName,
                )
            )

        oldQuantity != null && newQuantity != null && oldQuantity != newQuantity ->
            eventPublisher.publishEvent(
                PlantingDateRequestSpeciesUpdatedEvent(
                    changedFrom =
                        PlantingDateRequestSpeciesUpdatedEventValues(quantity = oldQuantity),
                    changedTo =
                        PlantingDateRequestSpeciesUpdatedEventValues(quantity = newQuantity),
                    organizationId = organizationId,
                    plantingSeasonId = plantingSeasonId,
                    plantingSiteId = plantingSiteId,
                    scheduledPlantingDateId = scheduledPlantingDateId,
                    speciesId = speciesId,
                    stratumName = info.stratumName,
                    substratumHistoryId = info.substratumHistoryId,
                    substratumId = substratumId,
                    substratumName = info.substratumName,
                )
            )
      }
    }
  }

  private fun updateRequestStatus(scheduledPlantingDateId: ScheduledPlantingDateId) {
    seasonHelper.withLockedScheduledPlantingDate(scheduledPlantingDateId) {
      val requestedQuantities: Map<SpeciesId, BigDecimal> =
          with(PLANTING_DATE_REQUEST_SPECIES) {
            dslContext
                .select(SPECIES_ID, DSL.sum(QUANTITY))
                .from(PLANTING_DATE_REQUEST_SPECIES)
                .where(SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId))
                .groupBy(SPECIES_ID)
                .associate { it[SPECIES_ID]!! to it[DSL.sum(QUANTITY)]!! }
          }

      val withdrawnQuantities: Map<SpeciesId, BigDecimal> =
          dslContext
              .select(BATCHES.SPECIES_ID, DSL.sum(BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN))
              .from(BATCH_WITHDRAWALS)
              .join(BATCHES)
              .on(BATCHES.ID.eq(BATCH_WITHDRAWALS.BATCH_ID))
              .join(WITHDRAWALS)
              .on(WITHDRAWALS.ID.eq(BATCH_WITHDRAWALS.WITHDRAWAL_ID))
              .where(WITHDRAWALS.SCHEDULED_PLANTING_DATE_REQUEST_ID.eq(scheduledPlantingDateId))
              .groupBy(BATCHES.SPECIES_ID)
              .associate {
                it[BATCHES.SPECIES_ID]!! to
                    it[DSL.sum(BATCH_WITHDRAWALS.READY_QUANTITY_WITHDRAWN)]!!
              }

      val oldStatus =
          dslContext
              .select(PLANTING_DATE_REQUESTS.STATUS_ID)
              .from(PLANTING_DATE_REQUESTS)
              .where(PLANTING_DATE_REQUESTS.SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId))
              .fetchOne(PLANTING_DATE_REQUESTS.STATUS_ID)

      val newStatus =
          when {
            requestedQuantities.isEmpty() -> PlantingDateRequestStatus.Pending
            requestedQuantities.all { (speciesId, quantity) ->
              (withdrawnQuantities[speciesId] ?: BigDecimal.ZERO) >= quantity
            } -> PlantingDateRequestStatus.Fulfilled

            requestedQuantities.keys.any {
              (withdrawnQuantities[it] ?: BigDecimal.ZERO) > BigDecimal.ZERO
            } -> PlantingDateRequestStatus.Partial

            else -> PlantingDateRequestStatus.Pending
          }

      // This runs as an automated recompute triggered by a withdrawal event rather than a user
      // edit, so modified_by and modified_time are intentionally left unchanged.
      dslContext
          .update(PLANTING_DATE_REQUESTS)
          .set(PLANTING_DATE_REQUESTS.STATUS_ID, newStatus)
          .where(PLANTING_DATE_REQUESTS.SCHEDULED_PLANTING_DATE_ID.eq(scheduledPlantingDateId))
          .execute()

      if (oldStatus != newStatus) {
        val plantingSeasonId =
            dslContext
                .select(SCHEDULED_PLANTING_DATES.PLANTING_SEASON_ID)
                .from(SCHEDULED_PLANTING_DATES)
                .where(SCHEDULED_PLANTING_DATES.ID.eq(scheduledPlantingDateId))
                .fetchOne(SCHEDULED_PLANTING_DATES.PLANTING_SEASON_ID)!!
        val (plantingSiteId, organizationId) =
            seasonHelper.fetchPlantingSiteAndOrganization(plantingSeasonId)

        eventPublisher.publishEvent(
            PlantingDateRequestUpdatedEvent(
                changedFrom = PlantingDateRequestUpdatedEventValues(status = oldStatus),
                changedTo = PlantingDateRequestUpdatedEventValues(status = newStatus),
                organizationId = organizationId,
                plantingSeasonId = plantingSeasonId,
                plantingSiteId = plantingSiteId,
                scheduledPlantingDateId = scheduledPlantingDateId,
            )
        )
      }
    }
  }
}
