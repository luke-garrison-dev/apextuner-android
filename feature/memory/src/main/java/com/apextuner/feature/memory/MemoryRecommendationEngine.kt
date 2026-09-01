package com.apextuner.feature.memory

internal object MemoryRecommendationEngine {
    fun evaluate(lowMemory: Boolean, available: Long, threshold: Long, pressureAvg10: Double?): List<String> {
        val output = mutableListOf<String>()
        if (lowMemory || (threshold > 0 && available <= threshold)) {
            output += "Android reports a low-memory condition. Save work and let the system reclaim cached processes naturally."
        }
        if (pressureAvg10 != null && pressureAvg10 >= 10.0) {
            output += "Kernel memory pressure is elevated. Closing a genuinely heavy foreground workload may help responsiveness."
        }
        if (output.isEmpty()) output += "Android is not currently reporting low-memory pressure. Cached RAM is useful and does not need to be artificially emptied."
        output += "ApexTuner does not force-stop unrelated apps: modern Android manages cached processes itself, and unnecessary killing can increase restart cost."
        return output
    }
}
