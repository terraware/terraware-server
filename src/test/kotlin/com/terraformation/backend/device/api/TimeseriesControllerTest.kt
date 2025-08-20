package com.terraformation.backend.device.api

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.api.SuccessOrError
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.TimeseriesId
import com.terraformation.backend.db.default_schema.TimeseriesType
import com.terraformation.backend.device.db.TimeseriesStore
import com.terraformation.backend.device.model.TimeseriesModel
import com.terraformation.backend.mockUser
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import java.lang.RuntimeException
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.ResponseEntity

internal class TimeseriesControllerTest : RunsAsUser {
  override val user: TerrawareUser = mockUser()
  private val facilityStore: FacilityStore = mockk()
  private val parentStore: ParentStore = mockk()
  private val timeseriesStore: TimeseriesStore = mockk()

  private val controller = TimeseriesController(facilityStore, parentStore, timeseriesStore)

  private val deviceId1 = DeviceId(1)
  private val deviceId2 = DeviceId(2)
  private val tsId1 = TimeseriesId(1)
  private val tsId2 = TimeseriesId(2)
  private val tsId3 = TimeseriesId(3)
  private val tsId4 = TimeseriesId(4)

  @BeforeEach
  fun setUp() {
    every { facilityStore.updateLastTimeseriesTimes(any()) } just runs
    every { parentStore.getFacilityConnectionState(any()) } returns
        FacilityConnectionState.Configured
    every { user.canCreateTimeseries(any()) } returns true
    every { user.canReadTimeseries(any()) } returns true
    every { user.canUpdateTimeseries(any()) } returns true
  }

  @Test
  fun `recordTimeseriesValues updates facilities`() {
    every { timeseriesStore.checkExistingValues(any(), any()) } returns emptySet()
    every { timeseriesStore.fetchOneByName(deviceId1, "ts1") } returns timeseriesModel(tsId1)
    every { timeseriesStore.insertValue(any(), any(), any(), any()) } just Runs

    controller.recordTimeseriesValues(
        RecordTimeseriesValuesRequestPayload(
            listOf(TimeseriesValuesPayload(deviceId1, "ts1", valuePayloads("1", "2", "3")))
        )
    )

    verify { facilityStore.updateLastTimeseriesTimes(listOf(deviceId1)) }
  }

  @Test
  fun `recordTimeseriesValues groups similar errors`() {
    every { timeseriesStore.fetchOneByName(deviceId1, "ts1") } returns timeseriesModel(tsId1)
    every { timeseriesStore.fetchOneByName(deviceId1, "ts2") } returns timeseriesModel(tsId2)
    every { timeseriesStore.fetchOneByName(deviceId2, "ts1") } returns null

    val device1Timestamps =
        listOf("v10", "v11", "v12").map { valuePayload(it) }.map { it.timestamp }
    every { timeseriesStore.checkExistingValues(any(), any()) } returns emptySet()
    every { timeseriesStore.checkExistingValues(tsId1, device1Timestamps) } returns
        setOf(device1Timestamps[1])

    every { timeseriesStore.insertValue(deviceId1, tsId1, "v10", any()) } just Runs
    every { timeseriesStore.insertValue(deviceId1, tsId1, "v11", any()) } throws
        RuntimeException("should not have tried to insert this")
    every { timeseriesStore.insertValue(deviceId1, tsId1, "v12", any()) } throws
        DuplicateKeyException("dup")
    every { timeseriesStore.insertValue(deviceId1, tsId2, "v20", any()) } throws
        IllegalArgumentException("failed")
    every { timeseriesStore.insertValue(deviceId1, tsId2, "v21", any()) } throws
        DuplicateKeyException("dup")

    val actual =
        controller.recordTimeseriesValues(
            RecordTimeseriesValuesRequestPayload(
                listOf(
                    TimeseriesValuesPayload(deviceId1, "ts1", valuePayloads("v10", "v11", "v12")),
                    TimeseriesValuesPayload(deviceId1, "ts2", valuePayloads("v20", "v21")),
                    TimeseriesValuesPayload(deviceId2, "ts1", valuePayloads("v30", "v31")),
                )
            )
        )

    val expected =
        ResponseEntity.ok(
            RecordTimeseriesValuesResponsePayload(
                listOf(
                    TimeseriesValuesErrorPayload(
                        deviceId1,
                        "ts1",
                        valuePayloads("v11", "v12"),
                        "Already have a value with this timestamp",
                    ),
                    TimeseriesValuesErrorPayload(
                        deviceId1,
                        "ts2",
                        valuePayloads("v20"),
                        "Unexpected error while saving value",
                    ),
                    TimeseriesValuesErrorPayload(
                        deviceId1,
                        "ts2",
                        valuePayloads("v21"),
                        "Already have a value with this timestamp",
                    ),
                    TimeseriesValuesErrorPayload(
                        deviceId2,
                        "ts1",
                        valuePayloads("v30", "v31"),
                        "Timeseries not found",
                    ),
                )
            )
        )

    assertEquals(expected, actual)
  }

  @Test
  fun `recordTimeseriesValues does not include failures list if nothing failed`() {
    every { timeseriesStore.checkExistingValues(any(), any()) } returns emptySet()
    every { timeseriesStore.fetchOneByName(deviceId1, "ts1") } returns timeseriesModel(tsId1)
    every { timeseriesStore.insertValue(any(), any(), any(), any()) } just Runs

    val response =
        controller.recordTimeseriesValues(
            RecordTimeseriesValuesRequestPayload(
                listOf(TimeseriesValuesPayload(deviceId1, "ts1", valuePayloads("1", "2", "3")))
            )
        )

    assertNull(response.body!!.failures, "Failures list")
  }

  @Test
  fun `recordTimeseriesValues does not record values if facility is not configured`() {
    every { parentStore.getFacilityConnectionState(any()) } returns
        FacilityConnectionState.Connected

    val response =
        controller.recordTimeseriesValues(
            RecordTimeseriesValuesRequestPayload(
                listOf(TimeseriesValuesPayload(deviceId1, "ts1", valuePayloads("1", "2", "3")))
            )
        )

    val expected =
        ResponseEntity.accepted()
            .body(RecordTimeseriesValuesResponsePayload(null, SuccessOrError.Ok, null))

    assertEquals(expected, response)
  }

  private fun timeseriesModel(
      id: TimeseriesId,
      deviceId: DeviceId = DeviceId(id.value),
      name: String = "ts$id",
      type: TimeseriesType = TimeseriesType.Numeric,
      decimalPlaces: Int? = null,
      units: String? = null,
  ) = TimeseriesModel(id, deviceId, name, type, decimalPlaces, units)

  private fun valuePayload(value: String) =
      TimeseriesValuePayload(Instant.ofEpochMilli(value.hashCode().toLong()), value)

  private fun valuePayloads(vararg values: String) = values.map { valuePayload(it) }
}
