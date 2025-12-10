package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.SubstratumId
import java.time.LocalDate

class CannotCreatePastPlantingSeasonException(val startDate: LocalDate, val endDate: LocalDate) :
    IllegalArgumentException("Cannot create planting season $startDate-$endDate in the past")

class CannotUpdatePastPlantingSeasonException(
    val plantingSeasonId: PlantingSeasonId,
    val endDate: LocalDate,
) :
    IllegalArgumentException(
        "Cannot update planting season $plantingSeasonId because it ended on $endDate"
    )

class PlantingSeasonsOverlapException(
    val startDate1: LocalDate,
    val endDate1: LocalDate,
    val startDate2: LocalDate,
    val endDate2: LocalDate,
) :
    MismatchedStateException(
        "Planting season $startDate1-$endDate1 overlaps with $startDate2-$endDate2"
    )

class PlantingSeasonTooFarInFutureException(val startDate: LocalDate) :
    IllegalArgumentException("Planting season start date $startDate is too far in the future")

class PlantingSeasonTooLongException(val startDate: LocalDate, val endDate: LocalDate) :
    IllegalArgumentException("Planting season $startDate-$endDate is too long")

class PlantingSeasonTooShortException(val startDate: LocalDate, val endDate: LocalDate) :
    IllegalArgumentException("Planting season $startDate-$endDate is too short")

class SubstratumFullException(
    val substratumId: SubstratumId,
    val plotsNeeded: Int,
    val plotsRemaining: Int,
) :
    IllegalStateException(
        "Substratum $substratumId needs $plotsNeeded temporary plots but only " +
            "$plotsRemaining available"
    )
