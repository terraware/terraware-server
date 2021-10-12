package com.terraformation.backend.device.api

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.TimeseriesId
import com.terraformation.backend.db.tables.pojos.TimeseriesRow
import com.terraformation.backend.device.db.TimeseriesStore
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException

internal class TimeseriesControllerTest : RunsAsUser {
  override val user: UserModel = mockk()
  private val store: TimeseriesStore = mockk()

  private val controller = TimeseriesController(store)

  private val deviceId1 = DeviceId(1)
  private val deviceId2 = DeviceId(2)
  private val tsId1 = TimeseriesId(1)
  private val tsId2 = TimeseriesId(2)

  @BeforeEach
  fun setUp() {
    every { user.canCreateTimeseries(any()) } returns true
    every { user.canReadTimeseries(any()) } returns true
    every { user.canUpdateTimeseries(any()) } returns true
  }

  private fun valuePayload(value: String) =
      TimeseriesValuePayload(Instant.ofEpochMilli(value.hashCode().toLong()), value)

  private fun valuePayloads(vararg values: String) = values.map { valuePayload(it) }

  @Test
  fun `recordTimeseriesValues groups similar errors`() {

    every { store.fetchOneByName(deviceId1, "ts1") } returns TimeseriesRow(id = tsId1)
    every { store.fetchOneByName(deviceId1, "ts2") } returns TimeseriesRow(id = tsId2)
    every { store.fetchOneByName(deviceId2, "ts1") } returns null

    every { store.insertValue(deviceId1, tsId1, "v10", any()) } just Runs
    every { store.insertValue(deviceId1, tsId1, "v11", any()) } throws DuplicateKeyException("dup")
    every { store.insertValue(deviceId1, tsId1, "v12", any()) } throws DuplicateKeyException("dup")
    every { store.insertValue(deviceId1, tsId2, "v20", any()) } throws
        IllegalArgumentException("failed")
    every { store.insertValue(deviceId1, tsId2, "v21", any()) } throws DuplicateKeyException("dup")

    val actual =
        controller.recordTimeseriesValues(
            RecordTimeseriesValuesRequestPayload(
                listOf(
                    TimeseriesValuesPayload(deviceId1, "ts1", valuePayloads("v10", "v11", "v12")),
                    TimeseriesValuesPayload(deviceId1, "ts2", valuePayloads("v20", "v21")),
                    TimeseriesValuesPayload(deviceId2, "ts1", valuePayloads("v30", "v31")),
                )))

    val expected =
        RecordTimeseriesValuesResponsePayload(
            listOf(
                TimeseriesValuesErrorPayload(
                    deviceId1,
                    "ts1",
                    valuePayloads("v11", "v12"),
                    "Already have a value with this timestamp"),
                TimeseriesValuesErrorPayload(
                    deviceId1, "ts2", valuePayloads("v20"), "Unexpected error while saving value"),
                TimeseriesValuesErrorPayload(
                    deviceId1,
                    "ts2",
                    valuePayloads("v21"),
                    "Already have a value with this timestamp"),
                TimeseriesValuesErrorPayload(
                    deviceId2, "ts1", valuePayloads("v30", "v31"), "Timeseries not found")))

    assertEquals(expected, actual)
  }

  @Test
  fun `recordTimeseriesValues does not include failures list if nothing failed`() {
    every { store.fetchOneByName(deviceId1, "ts1") } returns TimeseriesRow(id = tsId1)
    every { store.insertValue(any(), any(), any(), any()) } just Runs

    val response =
        controller.recordTimeseriesValues(
            RecordTimeseriesValuesRequestPayload(
                listOf(TimeseriesValuesPayload(deviceId1, "ts1", valuePayloads("1", "2", "3")))))

    assertNull(response.failures, "Failures list")
  }
}
