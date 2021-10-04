package com.terraformation.backend.device.api

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.TimeseriesType
import com.terraformation.backend.db.tables.daos.DevicesDao
import com.terraformation.backend.db.tables.daos.TimeseriesDao
import com.terraformation.backend.db.tables.pojos.DevicesRow
import com.terraformation.backend.db.tables.pojos.TimeseriesRow
import com.terraformation.backend.device.db.TimeseriesStore
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class TimeseriesControllerTest : RunsAsUser {
  override val user: UserModel = mockk()
  private val store: TimeseriesStore = mockk()

  private val controller = TimeseriesController(store)

  private val deviceId = DeviceId(1)

  @BeforeEach
  fun setUp() {
    every { user.canCreateTimeseries(any()) } returns true
    every { user.canReadTimeseries(any())} returns true
    every { user.canUpdateTimeseries(any())} returns true
  }

  @Test
  fun `recordTimeseriesValues groups similar errors`() {
    TODO()
  }

}
