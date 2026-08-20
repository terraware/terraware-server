package com.terraformation.backend.db

import org.springframework.boot.test.context.filter.annotation.AnnotationCustomizableTypeExcludeFilter
import org.springframework.context.annotation.ComponentScan

/**
 * Replacement for Spring Boot's standard type exclude filter. The default filter rescans the
 * classpath in [getDefaultIncludes]. That's called on each hashCode/equals call while the test
 * context cache's lock is held, which is a bottleneck on a parallel test run that adds about 30% to
 * the test suite's run time. We don't use include/exclude configurations, so we can just return an
 * empty list.
 */
class DatabaseTestTypeExcludeFilter(testClass: Class<*>) :
    AnnotationCustomizableTypeExcludeFilter() {
  override fun getComponentIncludes(): Set<Class<*>> = emptySet()

  override fun getDefaultIncludes(): Set<Class<*>> = emptySet()

  override fun getFilters(type: FilterType): Array<ComponentScan.Filter> = emptyArray()

  override fun hasAnnotation(): Boolean = true

  override fun isUseDefaultFilters(): Boolean = true
}
