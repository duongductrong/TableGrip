package dev.tablegrip.app.connections

import dev.tablegrip.core.connection.DriverId
import kotlin.test.Test
import kotlin.test.assertEquals

class SslOptionsTest {
    private val pg = SslSpecs.getValue(DriverId.PostgreSQL)

    @Test
    fun `TLS properties are taken out of the rest, and written back in the driver's own names`() {
        val (ssl, rest) = SslSettings.extract(pg, mapOf("sslmode" to "verify-full", "sslrootcert" to "/ca.pem", "currentSchema" to "app"))
        assertEquals(SslSettings(SslMode.VerifyFull, ca = "/ca.pem"), ssl)
        assertEquals(mapOf("currentSchema" to "app"), rest)
        assertEquals(mapOf("sslmode" to "verify-full", "sslrootcert" to "/ca.pem"), ssl.toProperties(pg))
    }

    @Test
    fun `a mode the driver spells differently maps across drivers, and unknown values stay as properties`() {
        assertEquals(mapOf("sslMode" to "VERIFY_IDENTITY"), SslSettings(SslMode.VerifyFull).toProperties(SslSpecs.getValue(DriverId.MySQL)))
        // MariaDB has no "prefer": the mode is dropped rather than sent as something it doesn't know.
        assertEquals(emptyMap(), SslSettings(SslMode.Prefer).toProperties(SslSpecs.getValue(DriverId.MariaDB)))
        val (ssl, rest) = SslSettings.extract(pg, mapOf("sslmode" to "bogus"))
        assertEquals(null, ssl.mode)
        assertEquals(mapOf("sslmode" to "bogus"), rest)
    }
}
