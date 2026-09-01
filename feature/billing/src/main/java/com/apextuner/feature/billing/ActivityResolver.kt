package com.apextuner.feature.billing

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

fun Context.findActivity(): Activity? {
    var current: Context = this
    val visited = mutableSetOf<Context>()
    while (visited.add(current)) {
        when (current) {
            is Activity -> return current
            is ContextWrapper -> current = current.baseContext
            else -> return null
        }
    }
    return null
}
