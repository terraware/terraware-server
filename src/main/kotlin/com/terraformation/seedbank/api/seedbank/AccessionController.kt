package com.terraformation.seedbank.api.seedbank

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import com.terraformation.seedbank.api.NotFoundException
import com.terraformation.seedbank.api.SuccessResponsePayload
import com.terraformation.seedbank.api.annotation.ApiResponse404
import com.terraformation.seedbank.api.annotation.SeedBankAppEndpoint
import com.terraformation.seedbank.db.AccessionNotFoundException
import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.AccessionStore
import com.terraformation.seedbank.db.GerminationSeedType
import com.terraformation.seedbank.db.GerminationSubstrate
import com.terraformation.seedbank.db.GerminationTestType
import com.terraformation.seedbank.db.GerminationTreatment
import com.terraformation.seedbank.db.ProcessingMethod
import com.terraformation.seedbank.db.SeedQuantityUnits
import com.terraformation.seedbank.db.SourcePlantOrigin
import com.terraformation.seedbank.db.SpeciesEndangeredType
import com.terraformation.seedbank.db.SpeciesRareType
import com.terraformation.seedbank.db.StorageCondition
import com.terraformation.seedbank.db.WithdrawalPurpose
import com.terraformation.seedbank.model.AccessionActive
import com.terraformation.seedbank.model.AccessionFields
import com.terraformation.seedbank.model.AccessionModel
import com.terraformation.seedbank.model.AccessionSource
import com.terraformation.seedbank.model.AppDeviceFields
import com.terraformation.seedbank.model.Geolocation
import com.terraformation.seedbank.model.GerminationFields
import com.terraformation.seedbank.model.GerminationTestFields
import com.terraformation.seedbank.model.GerminationTestWithdrawal
import com.terraformation.seedbank.model.SeedQuantityModel
import com.terraformation.seedbank.model.WithdrawalFields
import com.terraformation.seedbank.services.perClassLogger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import javax.validation.Valid
import javax.validation.constraints.PositiveOrZero
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/accession")
@RestController
@SeedBankAppEndpoint
class AccessionController(private val accessionStore: AccessionStore, private val clock: Clock) {
  @ApiResponse(
      responseCode = "200",
      description =
          "The accession was created successfully. Response includes fields populated by the " +
              "server, including the accession number.")
  @Operation(summary = "Create a new accession.")
  @PostMapping
  fun create(@RequestBody payload: CreateAccessionRequestPayload): CreateAccessionResponsePayload {
    val updatedPayload = accessionStore.create(payload)
    return CreateAccessionResponsePayload(AccessionPayload(updatedPayload, clock))
  }

  @ApiResponse(
      responseCode = "200",
      description =
          "The accession was updated successfully. Response includes fields populated or " +
              "modified by the server as a result of the update.")
  @ApiResponse404(description = "The specified accession doesn't exist.")
  @Operation(summary = "Update an existing accession.")
  @PutMapping("/{accessionNumber}")
  fun update(
      @RequestBody payload: UpdateAccessionRequestPayload,
      @PathVariable accessionNumber: String,
      @RequestParam
      @Schema(
          description =
              "If true, do not actually save the accession; just return the result that would " +
                  "have been returned if it had been saved.")
      simulate: Boolean?
  ): UpdateAccessionResponsePayload {
    perClassLogger().debug("Payload $payload")
    try {
      val updatedModel =
          if (simulate == true) {
            accessionStore.dryRun(payload, accessionNumber)
          } else {
            accessionStore.updateAndFetch(payload, accessionNumber)
          }
      return UpdateAccessionResponsePayload(AccessionPayload(updatedModel, clock))
    } catch (e: AccessionNotFoundException) {
      throw NotFoundException()
    }
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @GetMapping("/{accessionNumber}")
  @Operation(summary = "Retrieve an existing accession.")
  fun read(@PathVariable accessionNumber: String): GetAccessionResponsePayload {
    val accession =
        accessionStore.fetchByNumber(accessionNumber)
            ?: throw NotFoundException("The specified accession doesn't exist.")
    return GetAccessionResponsePayload(AccessionPayload(accession, clock))
  }
}

// Ignore properties that are defined on AccessionFields but not accepted as input (CU-px8k25)
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE)
data class CreateAccessionRequestPayload(
    override val bagNumbers: Set<String>? = null,
    override val collectedDate: LocalDate? = null,
    override val deviceInfo: DeviceInfoPayload? = null,
    override val endangered: SpeciesEndangeredType? = null,
    override val environmentalNotes: String? = null,
    override val family: String? = null,
    override val fieldNotes: String? = null,
    override val founderId: String? = null,
    override val geolocations: Set<Geolocation>? = null,
    override val germinationTestTypes: Set<GerminationTestType>? = null,
    override val landowner: String? = null,
    override val numberOfTrees: Int? = null,
    override val primaryCollector: String? = null,
    override val rare: SpeciesRareType? = null,
    override val receivedDate: LocalDate? = null,
    override val secondaryCollectors: Set<String>? = null,
    override val siteLocation: String? = null,
    override val sourcePlantOrigin: SourcePlantOrigin? = null,
    override val species: String? = null,
) : AccessionFields

// Ignore properties that are defined on AccessionFields but not accepted as input (CU-px8k25)
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE)
data class UpdateAccessionRequestPayload(
    override val bagNumbers: Set<String>? = null,
    override val collectedDate: LocalDate? = null,
    override val cutTestSeedsCompromised: Int? = null,
    override val cutTestSeedsEmpty: Int? = null,
    override val cutTestSeedsFilled: Int? = null,
    override val dryingEndDate: LocalDate? = null,
    override val dryingMoveDate: LocalDate? = null,
    override val dryingStartDate: LocalDate? = null,
    override val endangered: SpeciesEndangeredType? = null,
    override val environmentalNotes: String? = null,
    override val family: String? = null,
    override val fieldNotes: String? = null,
    override val founderId: String? = null,
    override val geolocations: Set<Geolocation>? = null,
    override val germinationTestTypes: Set<GerminationTestType>? = null,
    @Valid override val germinationTests: List<GerminationTestPayload>? = null,
    @Schema(
        description =
            "Initial size of accession. The units of this value must match the measurement type " +
                "in \"processingMethod\".")
    val initialQuantity: SeedQuantityPayload? = null,
    override val landowner: String? = null,
    override val numberOfTrees: Int? = null,
    override val nurseryStartDate: LocalDate? = null,
    override val primaryCollector: String? = null,
    override val processingMethod: ProcessingMethod? = null,
    override val processingNotes: String? = null,
    override val processingStaffResponsible: String? = null,
    override val processingStartDate: LocalDate? = null,
    override val rare: SpeciesRareType? = null,
    override val receivedDate: LocalDate? = null,
    override val secondaryCollectors: Set<String>? = null,
    override val siteLocation: String? = null,
    override val sourcePlantOrigin: SourcePlantOrigin? = null,
    override val species: String? = null,
    override val storageLocation: String? = null,
    override val storageNotes: String? = null,
    override val storagePackets: Int? = null,
    override val storageStaffResponsible: String? = null,
    override val storageStartDate: LocalDate? = null,
    override val subsetCount: Int? = null,
    @Schema(
        description =
            "Weight of subset of seeds. Units must be a weight measurement, not \"Seeds\".")
    private val subsetWeight: SeedQuantityPayload? = null,
    override val targetStorageCondition: StorageCondition? = null,
    @Valid override val withdrawals: List<WithdrawalPayload>? = null,
) : AccessionFields {
  @get:JsonIgnore
  override val subsetWeightQuantity: SeedQuantityModel?
    get() = subsetWeight?.toModel()
  @get:JsonIgnore
  override val total: SeedQuantityModel?
    get() = initialQuantity?.toModel()
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema
data class AccessionPayload(
    @Schema(description = "Server-generated unique identifier for the accession.")
    val accessionNumber: String,
    @Schema(
        description = "Server-calculated active indicator. This is based on the accession's state.")
    val active: AccessionActive,
    val bagNumbers: Set<String>? = null,
    val collectedDate: LocalDate? = null,
    val deviceInfo: DeviceInfoPayload? = null,
    val cutTestSeedsCompromised: Int? = null,
    val cutTestSeedsEmpty: Int? = null,
    val cutTestSeedsFilled: Int? = null,
    val dryingEndDate: LocalDate? = null,
    val dryingMoveDate: LocalDate? = null,
    val dryingStartDate: LocalDate? = null,
    val endangered: SpeciesEndangeredType? = null,
    val environmentalNotes: String? = null,
    val estimatedSeedCount: Int? = null,
    val family: String? = null,
    val fieldNotes: String? = null,
    val founderId: String? = null,
    val geolocations: Set<Geolocation>? = null,
    val germinationTests: List<GerminationTestPayload>? = null,
    val germinationTestTypes: Set<GerminationTestType>? = null,
    @Schema(
        description =
            "Initial size of accession. The units of this value must match the measurement type " +
                "in \"processingMethod\".")
    val initialQuantity: SeedQuantityPayload? = null,
    val landowner: String? = null,
    val latestGerminationTestDate: LocalDate? = null,
    val latestViabilityPercent: Int? = null,
    val numberOfTrees: Int? = null,
    val nurseryStartDate: LocalDate? = null,
    val photoFilenames: List<String>? = null,
    val primaryCollector: String? = null,
    val processingMethod: ProcessingMethod? = null,
    val processingNotes: String? = null,
    val processingStaffResponsible: String? = null,
    val processingStartDate: LocalDate? = null,
    val rare: SpeciesRareType? = null,
    val receivedDate: LocalDate? = null,
    @Schema(
        description =
            "Number or weight of seeds remaining for withdrawal and testing. Calculated by the " +
                "server when the accession's total size is known.")
    val remainingQuantity: SeedQuantityPayload? = null,
    val secondaryCollectors: Set<String>? = null,
    val siteLocation: String? = null,
    @Schema(
        description =
            "Which application this accession originally came from. This is currently based on " +
                "the presence of the deviceInfo field.")
    val source: AccessionSource,
    val sourcePlantOrigin: SourcePlantOrigin? = null,
    val species: String? = null,
    @Schema(description = "Server-generated unique ID of the species.") val speciesId: Long? = null,
    @Schema(
        description =
            "Server-calculated accession state. Can change due to modifications to accession data " +
                "or based on passage of time.")
    val state: AccessionState,
    val storageCondition: StorageCondition? = null,
    val storageLocation: String? = null,
    val storagePackets: Int? = null,
    val storageNotes: String? = null,
    val storageStaffResponsible: String? = null,
    val storageStartDate: LocalDate? = null,
    val subsetCount: Int? = null,
    @Schema(
        description =
            "Weight of subset of seeds. Units must be a weight measurement, not \"Seeds\".")
    val subsetWeight: SeedQuantityPayload? = null,
    val targetStorageCondition: StorageCondition? = null,
    @Schema(description = "Total quantity of all past withdrawals, including germination tests.")
    val totalPastWithdrawalQuantity: SeedQuantityPayload? = null,
    @Schema(
        description = "Total quantity of scheduled withdrawals, not counting germination tests.")
    val totalScheduledNonTestQuantity: SeedQuantityPayload? = null,
    @Schema(description = "Total quantity of scheduled withdrawals for germination tests.")
    val totalScheduledTestQuantity: SeedQuantityPayload? = null,
    @Schema(description = "Total quantity of scheduled withdrawals, including germination tests.")
    val totalScheduledWithdrawalQuantity: SeedQuantityPayload? = null,
    val totalViabilityPercent: Int? = null,
    @Schema(
        description =
            "Total quantity of all past and scheduled withdrawals, including germination tests.")
    val totalWithdrawalQuantity: SeedQuantityPayload? = null,
    val withdrawals: List<WithdrawalPayload>? = null,
) {
  constructor(
      model: AccessionModel,
      clock: Clock
  ) : this(
      model.accessionNumber,
      model.active,
      model.bagNumbers,
      model.collectedDate,
      model.deviceInfo?.let { DeviceInfoPayload(it) },
      model.cutTestSeedsCompromised,
      model.cutTestSeedsEmpty,
      model.cutTestSeedsFilled,
      model.dryingEndDate,
      model.dryingMoveDate,
      model.dryingStartDate,
      model.endangered,
      model.environmentalNotes,
      model.estimatedSeedCount,
      model.family,
      model.fieldNotes,
      model.founderId,
      model.geolocations,
      model.germinationTests?.map { GerminationTestPayload(it) },
      model.germinationTestTypes,
      model.total?.toPayload(),
      model.landowner,
      model.latestGerminationTestDate,
      model.latestViabilityPercent,
      model.numberOfTrees,
      model.nurseryStartDate,
      model.photoFilenames,
      model.primaryCollector,
      model.processingMethod,
      model.processingNotes,
      model.processingStaffResponsible,
      model.processingStartDate,
      model.rare,
      model.receivedDate,
      model.remaining?.toPayload(),
      model.secondaryCollectors,
      model.siteLocation,
      model.source,
      model.sourcePlantOrigin,
      model.species,
      model.speciesId,
      model.state,
      model.storageCondition,
      model.storageLocation,
      model.storagePackets,
      model.storageNotes,
      model.storageStaffResponsible,
      model.storageStartDate,
      model.subsetCount,
      model.subsetWeightQuantity?.toPayload(),
      model.targetStorageCondition,
      model.calculateTotalPastWithdrawalQuantity(clock)?.toPayload(),
      model.calculateTotalScheduledNonTestQuantity(clock)?.toPayload(),
      model.calculateTotalScheduledTestQuantity(clock)?.toPayload(),
      model.calculateTotalScheduledWithdrawalQuantity(clock)?.toPayload(),
      model.totalViabilityPercent,
      model.calculateTotalWithdrawalQuantity(clock)?.toPayload(),
      model.withdrawals?.map { WithdrawalPayload(it) },
  )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
    description =
        "Represents a quantity of seeds, measured either in individual seeds or by weight.")
data class SeedQuantityPayload(
    @Schema(
        description =
            "Number of units of seeds. If \"units\" is \"Seeds\", this is the number of seeds " +
                "and must be an integer. Otherwise it is a measurement in the weight units " +
                "specified in the \"units\" field, and may have a fractional part.")
    @PositiveOrZero
    val quantity: BigDecimal,
    val units: SeedQuantityUnits,
    @Schema(
        description =
            "If this quantity is a weight measurement, the weight in grams. This is not set if " +
                "the \"units\" field is \"Seeds\". This is always calculated on the server side " +
                "and is ignored on input.",
        readOnly = true)
    val grams: BigDecimal? = null
) {
  constructor(model: SeedQuantityModel) : this(model.quantity, model.units, model.grams)

  fun toModel() = SeedQuantityModel(quantity, units)
}

private fun SeedQuantityModel.toPayload() = SeedQuantityPayload(this)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GerminationTestPayload(
    @Schema(
        description =
            "Server-assigned unique ID of this germination test. Null when creating a new test.",
        type = "string")
    @JsonSerialize(using = ToStringSerializer::class)
    override val id: Long? = null,
    @Schema(
        description = "Which type of test is described. At most one of each test type is allowed.")
    override val testType: GerminationTestType,
    override val startDate: LocalDate? = null,
    override val endDate: LocalDate? = null,
    override val seedType: GerminationSeedType? = null,
    override val substrate: GerminationSubstrate? = null,
    override val treatment: GerminationTreatment? = null,
    override val notes: String? = null,
    @Schema(
        description =
            "Quantity of seeds remaining. For weight-based accessions, this is user input and " +
                "is required. For count-based accessions, it is calculated by the server and " +
                "ignored on input.")
    val remainingQuantity: SeedQuantityPayload? = null,
    override val staffResponsible: String? = null,
    override val seedsSown: Int? = null,
    override val totalPercentGerminated: Int? = null,
    override val totalSeedsGerminated: Int? = null,
    @Valid override val germinations: List<GerminationPayload>? = null
) : GerminationTestFields {
  constructor(
      model: GerminationTestFields
  ) : this(
      model.id,
      model.testType,
      model.startDate,
      model.endDate,
      model.seedType,
      model.substrate,
      model.treatment,
      model.notes,
      model.remaining?.toPayload(),
      model.staffResponsible,
      model.seedsSown,
      model.totalPercentGerminated,
      model.totalSeedsGerminated,
      model.germinations?.map { GerminationPayload(it) })

  @get:JsonIgnore
  override val remaining: SeedQuantityModel?
    get() = remainingQuantity?.toModel()

  override fun withId(value: Long) = copy(id = value)
  override fun withRemaining(value: SeedQuantityModel) =
      copy(remainingQuantity = SeedQuantityPayload(value))
}

data class GerminationPayload(
    override val recordingDate: LocalDate,
    @JsonProperty(required = true) override val seedsGerminated: Int
) : GerminationFields {
  constructor(model: GerminationFields) : this(model.recordingDate, model.seedsGerminated)
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WithdrawalPayload(
    @Schema(
        description =
            "Server-assigned unique ID of this withdrawal, its ID. Omit when creating a new " +
                "withdrawal.")
    override val id: Long? = null,
    override val date: LocalDate,
    override val purpose: WithdrawalPurpose,
    override val destination: String? = null,
    override val notes: String? = null,
    @Schema(
        description =
            "Quantity of seeds remaining. For weight-based accessions, this is user input and " +
                "is required. For count-based accessions, it is calculated by the server and " +
                "ignored on input.")
    val remainingQuantity: SeedQuantityPayload? = null,
    override val staffResponsible: String? = null,
    @Schema(
        description =
            "If this withdrawal is of type \"Germination Testing\", the ID of the test it is " +
                "associated with. This is always set by the server and cannot be modified.")
    override val germinationTestId: Long? = null,
    @JsonProperty("weightDifference")
    @Schema(
        name = "weightDifference",
        description =
            "For weight-based accessions, the difference between the weight remaining before " +
                "this withdrawal and the weight remaining after it. This is a server-calculated " +
                "value and is ignored on input.",
        readOnly = true)
    val weightDifferencePayload: SeedQuantityPayload? = null,
    @Schema(
        description =
            "Quantity of seeds withdrawn. For germination testing withdrawals, this is always " +
                "the same as the test's \"seedsSown\" value, if that value is present. " +
                "Otherwise, it is a user-supplied value. For count-based accessions, the units " +
                "must always be \"Seeds\". For weight-based accessions, the units may either be " +
                "a weight measurement or \"Seeds\".")
    val withdrawnQuantity: SeedQuantityPayload? = null,
    @Schema(
        description =
            "The best estimate of the number of seeds withdrawn. This is the same as " +
                "\"withdrawnQuantity\" if that is present, or else the same as " +
                "\"weightDifference\" if this is a weight-based accession. If this is a " +
                "count-based accession and \"withdrawnQuantity\" does not have a value, " +
                "this field will not be present. This is a server-calculated value and is " +
                "ignored on input.",
        readOnly = true)
    val estimatedQuantity: SeedQuantityPayload? = null,
) : WithdrawalFields {
  constructor(
      model: WithdrawalFields
  ) : this(
      model.id,
      model.date,
      model.purpose,
      model.destination,
      model.notes,
      model.remaining?.toPayload(),
      model.staffResponsible,
      model.germinationTestId,
      model.weightDifference?.toPayload(),
      model.withdrawn?.toPayload(),
      model.calculateEstimatedQuantity()?.toPayload(),
  )

  @get:JsonIgnore
  override val remaining: SeedQuantityModel?
    get() = remainingQuantity?.toModel()
  @get:JsonIgnore
  override val weightDifference: SeedQuantityModel?
    get() = weightDifferencePayload?.toModel()
  @get:JsonIgnore
  override val withdrawn: SeedQuantityModel?
    get() = withdrawnQuantity?.toModel()

  override fun withDate(value: LocalDate) = copy(date = value)

  override fun withGerminationTest(value: GerminationTestFields): WithdrawalFields {
    return GerminationTestWithdrawal(
        id = id,
        accessionId = null,
        date = date,
        destination = destination,
        germinationTest = value,
        germinationTestId = value.id,
        notes = notes,
        staffResponsible = staffResponsible,
        remaining = remaining,
        withdrawn = withdrawn,
    )
  }

  override fun withRemaining(value: SeedQuantityModel) =
      copy(remainingQuantity = SeedQuantityPayload(value))

  override fun withWeightDifference(value: SeedQuantityModel) =
      copy(weightDifferencePayload = value.toPayload())
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
    description =
        "Details about the device and the application that created the accession. All these " +
            "values are optional and most of them are platform-dependent.")
data class DeviceInfoPayload(
    @Schema(
        description =
            "Build number of application that is submitting the accession, e.g., from React Native getBuildId()")
    override val appBuild: String? = null,
    @Schema(description = "Name of application", example = "Seed Collector")
    override val appName: String? = null,
    @Schema(
        description = "Brand of device, e.g., from React Native getBrand().", example = "Samsung")
    override val brand: String? = null,
    @Schema(description = "Model of device hardware, e.g., from React Native getDeviceId().")
    override val model: String? = null,
    @Schema(
        description =
            "Name the user has assigned to the device, e.g., from React Native getDeviceName().",
        example = "Carlos's iPhone")
    override val name: String? = null,
    @Schema(
        description = "Type of operating system, e.g., from React Native getSystemName().",
        example = "Android")
    override val osType: String? = null,
    @Schema(
        description = "Version of operating system, e.g., from React Native getSystemVersion().",
        example = "7.1.1")
    override val osVersion: String? = null,
    @Schema(
        description =
            "Unique identifier of the hardware device, e.g., from React Native getUniqueId().")
    override val uniqueId: String? = null,
) : AppDeviceFields {
  constructor(
      model: AppDeviceFields
  ) : this(
      model.appBuild,
      model.appName,
      model.brand,
      model.model,
      model.name,
      model.osType,
      model.osVersion,
      model.uniqueId)
}

data class CreateAccessionResponsePayload(val accession: AccessionPayload) : SuccessResponsePayload

data class UpdateAccessionResponsePayload(val accession: AccessionPayload) : SuccessResponsePayload

data class GetAccessionResponsePayload(val accession: AccessionPayload) : SuccessResponsePayload
