package com.terraformation.backend.daily

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.event.FacilityTimeZoneChangedEvent
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.toModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.references.NOTIFICATIONS
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.mockUser
import com.terraformation.backend.time.ClockAdvancedEvent
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NotificationScannerTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock(Instant.EPOCH.plus(2, ChronoUnit.DAYS))
  private val config: TerrawareServerConfig = mockk()
  private val notifiers: MutableList<FacilityNotifier> = mutableListOf()

  private val eventPublisher = TestEventPublisher()

  private val facilityStore: FacilityStore by lazy {
    FacilityStore(
        clock,
        config,
        dslContext,
        eventPublisher,
        facilitiesDao,
        Messages(),
        organizationsDao,
        subLocationsDao,
    )
  }

  private val scanner: NotificationScanner by lazy {
    NotificationScanner(
        clock,
        config,
        eventPublisher,
        facilityStore,
        notifiers,
        SystemUser(usersDao),
    )
  }

  private lateinit var facilityId: FacilityId

  @BeforeEach
  fun setUp() {
    every { config.dailyTasks } returns TerrawareServerConfig.DailyTasksConfig()

    insertOrganization()
    facilityId =
        insertFacility(lastNotificationDate = LocalDate.EPOCH, nextNotificationTime = Instant.EPOCH)
  }

  @Test
  fun `advancing test clock causes newly-due jobs to be run`() {
    lateinit var notificationId: NotificationId
    val increment = Duration.ofDays(7)

    notifiers.add(FacilityNotifier { _, _ -> notificationId = insertNotification() })

    facilitiesDao.update(
        facilitiesDao
            .fetchOneById(facilityId)!!
            .copy(nextNotificationTime = Instant.EPOCH + increment)
    )

    scanner.sendNotifications()
    assertTableEmpty(NOTIFICATIONS, "Should not have inserted notification before clock advanced")

    clock.instant = Instant.EPOCH + increment

    scanner.on(ClockAdvancedEvent(Duration.ofDays(1)))

    assertEquals(listOf(notificationId), notificationsDao.findAll().map { it.id })
    assertIsEventListener<ClockAdvancedEvent>(scanner)
  }

  @Test
  fun `changing facility time zone causes its notifications to be processed`() {
    val facilities = mutableListOf<FacilityModel>()
    var calledAsUser: TerrawareUser? = null

    notifiers.add(FacilityNotifier { facility, _ -> facilities.add(facility) })
    notifiers.add(FacilityNotifier { _, _ -> calledAsUser = currentUser() })

    val facility = facilitiesDao.fetchOneById(facilityId)!!.toModel()

    scanner.on(FacilityTimeZoneChangedEvent(facility))

    assertEquals(listOf(facility), facilities)
    assertEquals(UserType.System, calledAsUser?.userType, "Should run as system user")
    assertIsEventListener<FacilityTimeZoneChangedEvent>(scanner)
  }

  @Test
  fun `sendNotifications runs as system user`() {
    var calledAsUser: TerrawareUser? = null

    notifiers.add(FacilityNotifier { _, _ -> calledAsUser = currentUser() })

    scanner.sendNotifications()

    assertEquals(UserType.System, calledAsUser?.userType)
  }

  @Test
  fun `rolls back changes from all notifiers if one notifier throws an exception`() {
    notifiers.add(FacilityNotifier { _, _ -> insertNotification() })
    notifiers.add(FacilityNotifier { _, _ -> throw Exception("boom") })

    scanner.sendNotifications()

    assertTableEmpty(NOTIFICATIONS)
  }

  @Test
  fun `does not send notifications for facilities without last notification dates`() {
    val notifiedFacilities = mutableSetOf<FacilityId>()
    notifiers.add(FacilityNotifier { facility, _ -> notifiedFacilities.add(facility.id) })

    val earlierTimeZone = ZoneId.of("America/New_York")
    facilitiesDao.update(
        facilitiesDao
            .fetchOneById(facilityId)!!
            .copy(lastNotificationDate = null, timeZone = earlierTimeZone)
    )

    scanner.sendNotifications()

    assertEquals(
        LocalDate.now(clock).minusDays(1),
        facilitiesDao.fetchOneById(facilityId)?.lastNotificationDate,
        "Should have set last notification date based on facility time zone",
    )
    assertSetEquals(
        emptySet<FacilityId>(),
        notifiedFacilities,
        "Should not have sent notifications",
    )
  }

  @Test
  fun `skips facilities whose notifications have already been generated for today`() {
    val notifiedFacilities = mutableSetOf<FacilityId>()
    notifiers.add(FacilityNotifier { facility, _ -> notifiedFacilities.add(facility.id) })

    // Already-inserted facility (`facilityId`) has a last notification date in the past.
    val notifiedFacilityId = inserted.facilityId

    // This facility's last notification date is yesterday from the server's (UTC) point of view,
    // but is today in the facility's time zone.
    val earlierTimeZone = ZoneId.of("America/New_York")
    insertFacility(
        lastNotificationDate = LocalDate.now(clock).minusDays(1),
        timeZone = earlierTimeZone,
    )

    val laterTimeZone = ZoneId.of("Europe/Athens")
    val laterFacilityId =
        insertFacility(
            lastNotificationDate = LocalDate.now(clock).minusDays(1),
            timeZone = laterTimeZone,
        )

    scanner.sendNotifications()

    assertSetEquals(setOf(notifiedFacilityId, laterFacilityId), notifiedFacilities)
  }
}
