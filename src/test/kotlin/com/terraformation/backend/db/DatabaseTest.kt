package com.terraformation.backend.db

import org.springframework.boot.test.autoconfigure.jooq.JooqTest

/** Superclass for tests that require database access but not the entire set of Spring beans. */
@JooqTest abstract class DatabaseTest : DatabaseBackedTest()
