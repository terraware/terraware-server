package com.terraformation.backend.accelerator.variables

import com.terraformation.backend.db.default_schema.LandUseModelType

/** Variables that are used for accelerator related functions. */
const val STABLE_ID_COUNTRY = "1"
const val STABLE_ID_APPLICATION_RESTORABLE_LAND = "2"
const val STABLE_ID_PROJECT_TYPE = "3"
const val STABLE_ID_LAND_USE_MODEL_TYPES = "4"

const val STABLE_ID_NUM_SPECIES = "22"
const val STABLE_ID_TOTAL_EXPANSION_POTENTIAL = "24"
const val STABLE_ID_CONTACT_NAME = "25"
const val STABLE_ID_CONTACT_EMAIL = "26"
const val STABLE_ID_WEBSITE = "27"

const val STABLE_ID_TF_RESTORABLE_LAND = "429"
const val STABLE_ID_PER_HECTARE_ESTIMATED_BUDGET = "430"
const val STABLE_ID_MIN_CARBON_ACCUMULATION = "431"
const val STABLE_ID_MAX_CARBON_ACCUMULATION = "432"
const val STABLE_ID_CARBON_CAPACITY = "433"
const val STABLE_ID_ANNUAL_CARBON = "434"
const val STABLE_ID_TOTAL_CARBON = "435"
const val STABLE_ID_DEAL_DESCRIPTION = "436"
const val STABLE_ID_INVESTMENT_THESIS = "437"
const val STABLE_ID_FAILURE_RISK = "438"
const val STABLE_ID_WHAT_NEEDS_TO_BE_TRUE = "439"

val stableIdsByLandUseModelType =
    mapOf(
        LandUseModelType.Agroforestry to "15",
        LandUseModelType.Mangroves to "13",
        LandUseModelType.Monoculture to "7",
        LandUseModelType.NativeForest to "5",
        LandUseModelType.OtherLandUseModel to "19",
        LandUseModelType.OtherTimber to "11",
        LandUseModelType.Silvopasture to "17",
        LandUseModelType.SustainableTimber to "9",
    )
