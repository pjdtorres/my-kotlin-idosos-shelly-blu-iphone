package com.example.idosos.shared

/** Regra de queda livre seguida de impacto, independente do sensor usado. */
class FallDetector {
    private var freeFallStart = 0L
    private var freeFallEnd = 0L
    private var hadFreeFall = false
    private var lastFall = 0L

    fun update(totalAcceleration: Float, nowMs: Long): Boolean {
        if (totalAcceleration < 3.0f) {
            if (freeFallStart == 0L) freeFallStart = nowMs
            if (nowMs - freeFallStart >= 80L) {
                hadFreeFall = true
                freeFallEnd = nowMs
            }
        } else {
            freeFallStart = 0L
        }

        if (hadFreeFall) {
            if (nowMs - freeFallEnd > 1000L) {
                hadFreeFall = false
            } else if (totalAcceleration > 20.0f) {
                hadFreeFall = false
                if (nowMs - lastFall > 2000L) {
                    lastFall = nowMs
                    return true
                }
            }
        }
        return false
    }
}
