package com.yunget.app.data.analytics

import com.yunget.app.BuildConfig

internal object GoogleAnalyticsConfig {
    val measurementId: String = BuildConfig.GA_MEASUREMENT_ID
    val apiSecret: String = BuildConfig.GA_API_SECRET
    val enabled: Boolean
        get() = measurementId.isNotBlank() && apiSecret.isNotBlank()
}
