package com.terraformation.backend.db

import javax.sql.DataSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean

/**
 * Indicates that a class shouldn't be instantiated by Spring if there's no database configured.
 *
 * Use this to stop database-dependent initialization code from breaking `./gradlew
 * generateOpenApiDocs`.
 */
@ConditionalOnBean(DataSource::class)
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
annotation class DisableIfNoDatabase
