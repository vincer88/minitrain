package com.minitrain.app

import kotlin.test.Test
import kotlin.test.assertTrue

class JvmToolchainTest {
    @Test
    fun `uses java 21 runtime`() {
        val specVersion = System.getProperty("java.specification.version")
        val majorVersion = specVersion
            ?.substringBefore('.')
            ?.toIntOrNull()
        assertTrue(
            majorVersion != null && majorVersion >= 21,
            "Les tests doivent s'exécuter avec une JVM 21 ou supérieure, version actuelle: $specVersion"
        )
    }
}
