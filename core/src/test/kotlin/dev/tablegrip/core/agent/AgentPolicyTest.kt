package dev.tablegrip.core.agent

import dev.tablegrip.core.connection.AgentWrites
import dev.tablegrip.core.connection.AiAccess
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.Environment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentPolicyTest {
    private val on = AgentPolicy(enabled = true, defaultAccess = AiAccess.SchemaOnly)

    private fun config(
        access: AiAccess? = null,
        writes: AgentWrites = AgentWrites.None,
        readOnly: Boolean = false,
        environment: Environment = Environment.None,
        type: DatabaseType = DatabaseType.PostgreSQL,
    ) = ConnectionConfig("id-1", "shop", type, aiAccess = access, agentWrites = writes, readOnly = readOnly, environment = environment)

    private fun allowed(policy: AgentPolicy, capability: AgentCapability, config: ConnectionConfig) = policy.decide(capability, config) == AgentDecision.Allowed

    @Test
    fun `nothing is served while MCP is off`() {
        val off = on.copy(enabled = false)
        val everything = config(AiAccess.SchemaAndSamples, AgentWrites.DataAndSchema)
        assertFalse(off.visible(everything))
        AgentCapability.entries.forEach { assertEquals(AgentDecision.Denied(AgentPolicy.Disabled), off.decide(it, everything)) }
    }

    @Test
    fun `access levels follow the connection's AI setting`() {
        assertFalse(on.visible(config(AiAccess.Off)))
        assertIs<AgentDecision.Denied>(on.decide(AgentCapability.Schema, config(AiAccess.Off)))

        val schemaOnly = config()
        assertTrue(on.visible(schemaOnly))
        assertTrue(allowed(on, AgentCapability.Schema, schemaOnly))
        assertFalse(allowed(on, AgentCapability.Data, schemaOnly))
        assertFalse(allowed(on, AgentCapability.WriteData, schemaOnly.copy(agentWrites = AgentWrites.Data)))

        val samples = config(AiAccess.SchemaAndSamples)
        assertTrue(allowed(on, AgentCapability.Data, samples))
        assertFalse(allowed(on, AgentCapability.WriteData, samples))
    }

    @Test
    fun `the app-wide default applies, but never shares production data`() {
        val generous = on.copy(defaultAccess = AiAccess.SchemaAndSamples)
        assertTrue(allowed(generous, AgentCapability.Data, config()))
        assertFalse(allowed(generous, AgentCapability.Data, config(environment = Environment.Production)))
        assertTrue(allowed(generous, AgentCapability.Data, config(AiAccess.SchemaAndSamples, environment = Environment.Production)))
    }

    @Test
    fun `writes need their own opt-in and never reach read-only connections`() {
        val data = config(AiAccess.SchemaAndSamples, AgentWrites.Data)
        assertTrue(allowed(on, AgentCapability.WriteData, data))
        assertFalse(allowed(on, AgentCapability.WriteSchema, data))
        val schema = data.copy(agentWrites = AgentWrites.DataAndSchema)
        assertTrue(allowed(on, AgentCapability.WriteSchema, schema))

        assertFalse(allowed(on, AgentCapability.WriteData, schema.copy(readOnly = true)))
        assertEquals(AgentWrites.None, on.writes(schema.copy(readOnly = true)))
        assertFalse(allowed(on.copy(forceReadOnly = true), AgentCapability.WriteData, schema))
        assertEquals(AgentWrites.None, on.copy(forceReadOnly = true).writes(schema))
        assertFalse(allowed(on, AgentCapability.WriteData, schema.copy(type = DatabaseType.CsvFile)))
    }

    @Test
    fun `the connections flag narrows by id or name`() {
        assertTrue(on.copy(only = setOf("shop")).visible(config()))
        assertTrue(on.copy(only = setOf("id-1")).visible(config()))
        assertFalse(on.copy(only = setOf("other")).visible(config()))
        assertIs<AgentDecision.Denied>(on.copy(only = setOf("other")).decide(AgentCapability.Schema, config()))
    }
}
