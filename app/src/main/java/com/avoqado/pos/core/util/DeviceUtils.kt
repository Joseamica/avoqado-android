package com.avoqado.pos.core.util

import java.util.UUID

object DeviceUtils {
    fun generateSerialNumber(): String {
        val hex = UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
        return "AVQD-$hex"
    }
}
