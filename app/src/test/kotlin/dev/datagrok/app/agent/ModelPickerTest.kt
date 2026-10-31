package dev.datagrok.app.agent

import dev.datagrok.core.agent.AgentModel
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelPickerTest {
    private val models = listOf(AgentModel("claude-opus-5-5-high", "Claude Opus 5.5 (High)"), AgentModel("gemini-4-argon-low", "Gemini 4 Argon (Low)"))

    @Test
    fun `nothing typed offers the default then every model`() {
        assertEquals(listOf(ModelChoice.Default) + models.map { ModelChoice.Offered(it) }, modelChoices(models, " "))
    }

    @Test
    fun `typing narrows by id or name and can always be used as is`() {
        assertEquals(listOf(ModelChoice.Typed("opus high"), ModelChoice.Offered(models[0])), modelChoices(models, "opus high"))
        assertEquals(listOf(ModelChoice.Typed("argon"), ModelChoice.Offered(models[1])), modelChoices(models, "argon"))
    }

    @Test
    fun `an exact id isn't offered twice`() {
        assertEquals(listOf(ModelChoice.Offered(models[1])), modelChoices(models, "gemini-4-argon-low"))
    }

    @Test
    fun `an agent that lists nothing still takes a typed model`() {
        assertEquals(listOf(ModelChoice.Typed("gemini-2.5-pro")), modelChoices(emptyList(), "gemini-2.5-pro"))
        assertEquals(listOf(ModelChoice.Default), modelChoices(emptyList(), ""))
    }
}
