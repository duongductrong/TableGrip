package dev.tablegrip.core.connection

import dev.tablegrip.core.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class AiAccessTest {
    private fun config(environment: Environment = Environment.None, access: AiAccess? = null) =
        ConnectionConfig(id = "c", name = "C", type = DatabaseType.PostgreSQL, environment = environment, aiAccess = access)

    @Test
    fun `defaults share no data, and unknown names never share more`() {
        assertEquals(AiAccess.SchemaOnly, AiAccess.of(Settings().aiAccess))
        assertEquals(AiAccess.SchemaOnly, AiAccess.of("Everything"))
        assertEquals(AiAccess.SchemaOnly, Json { ignoreUnknownKeys = true }.decodeFromString<Settings>("{}").let { AiAccess.of(it.aiAccess) })
        assertNull(Json.decodeFromString<ConnectionConfig>("""{"id":"c","name":"C","type":"PostgreSQL"}""").aiAccess)
    }

    @Test
    fun `a connection's own choice wins, otherwise production never shares data`() {
        assertEquals(AiAccess.SchemaAndSamples, AiAccess.effective(AiAccess.SchemaAndSamples, config()))
        assertEquals(AiAccess.SchemaOnly, AiAccess.effective(AiAccess.SchemaAndSamples, config(Environment.Production)))
        assertEquals(AiAccess.Off, AiAccess.effective(AiAccess.Off, config(Environment.Production)))
        assertEquals(AiAccess.SchemaAndSamples, AiAccess.effective(AiAccess.SchemaOnly, config(Environment.Production, AiAccess.SchemaAndSamples)))
        assertEquals(AiAccess.Off, AiAccess.effective(AiAccess.SchemaAndSamples, config(access = AiAccess.Off)))
        assertEquals(AiAccess.SchemaOnly, AiAccess.effective(AiAccess.SchemaOnly, null))
    }
}
