package com.terraformation.backend.device.balena

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.default_schema.tables.pojos.DeviceManagersRow
import com.terraformation.backend.db.default_schema.tables.references.DEVICE_MANAGERS
import com.terraformation.backend.device.db.DeviceManagerStore
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Inject
import jakarta.inject.Named
import java.time.Clock
import java.time.Instant
import org.jobrunr.scheduling.JobScheduler
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class BalenaPoller(
    private val balenaClient: BalenaClient,
    private val clock: Clock,
    private val deviceManagerStore: DeviceManagerStore,
    private val dslContext: DSLContext,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  @Inject
  @Suppress("unused")
  fun schedule(config: TerrawareServerConfig, scheduler: JobScheduler) {
    val jobId = javaClass.simpleName

    if (config.balena.enabled) {
      scheduler.scheduleRecurrently<BalenaPoller>(jobId, config.balena.pollInterval) {
        updateBalenaDevices()
      }
    } else {
      scheduler.deleteRecurringJob(jobId)
    }
  }

  fun updateBalenaDevices() {
    try {
      systemUser.run {
        val mostRecentModifiedTime =
            dslContext
                .select(DSL.max(DEVICE_MANAGERS.BALENA_MODIFIED_TIME))
                .from(DEVICE_MANAGERS)
                .fetchOne()
                ?.value1() ?: Instant.EPOCH

        val modifiedDevices = balenaClient.listModifiedDevices(mostRecentModifiedTime)

        dslContext.transaction { _ ->
          modifiedDevices.forEach { device ->
            val existingRow = deviceManagerStore.fetchOneByBalenaId(device.id)
            if (existingRow == null) {
              val sensorKitId = balenaClient.getSensorKitIdForBalenaId(device.id)

              if (sensorKitId == null) {
                log.warn("No sensor kit ID found for Balena device ${device.id}")
              } else {
                val newRow =
                    DeviceManagersRow(
                        balenaId = device.id,
                        balenaModifiedTime = device.modifiedAt,
                        balenaUuid = device.uuid,
                        createdTime = clock.instant(),
                        deviceName = device.deviceName,
                        isOnline = device.isOnline,
                        lastConnectivityEvent = device.lastConnectivityEvent,
                        refreshedTime = clock.instant(),
                        sensorKitId = sensorKitId,
                        updateProgress = device.overallProgress,
                    )

                deviceManagerStore.insert(newRow)

                log.info(
                    "Added device manager ${newRow.id} for Balena device ${device.id} with " +
                        "sensor kit ID $sensorKitId"
                )
              }
            } else {
              existingRow.apply {
                balenaModifiedTime = device.modifiedAt
                isOnline = device.isOnline
                lastConnectivityEvent = device.lastConnectivityEvent
                refreshedTime = clock.instant()
                updateProgress = device.overallProgress
              }

              deviceManagerStore.update(existingRow)

              log.info(
                  "Updated information for device manager ${existingRow.id} from Balena " +
                      "device ${device.id}"
              )
            }
          }
        }
      }
    } catch (e: Exception) {
      log.warn("Unable to process Balena device updates", e)
    }
  }
}
