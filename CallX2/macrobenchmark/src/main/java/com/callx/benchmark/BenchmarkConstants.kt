package com.callx.benchmark

/**
 * v612: single shared definition. ChatScrollBenchmark, ChatMediaBenchmark, ChatStartupBenchmark,
 * CallsAndStatusBenchmark and AdvancedChatMetricsBenchmark all referenced TARGET_PACKAGE but it
 * only existed as a *file-private* top-level const in four other files — Kotlin does not share
 * those across files, so these five files had an unresolved reference.
 */
internal const val TARGET_PACKAGE = "com.callx.app"
