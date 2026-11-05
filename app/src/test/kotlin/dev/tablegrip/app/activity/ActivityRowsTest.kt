package dev.tablegrip.app.activity

import dev.tablegrip.core.activity.SessionActivity
import dev.tablegrip.core.activity.SessionState
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityRowsTest {
    private fun session(id: String, state: SessionState, blockedBy: List<String> = emptyList(), app: String? = "psql", own: Boolean = false) =
        SessionActivity(id, "u", "db", "local", app, state, null, "select $id", null, null, blockedBy, own)

    private fun List<ActivityRow>.shape() = map { "${"  ".repeat(it.depth)}${it.session.id}${if (it.blocking > 0) "*${it.blocking}" else ""}" }

    @Test
    fun blockedSessionsNestUnderTheirBlocker() {
        val sessions =
            listOf(
                session("1", SessionState.Blocked, listOf("2")),
                session("2", SessionState.IdleInTransaction),
                session("3", SessionState.Blocked, listOf("1")),
                session("4", SessionState.Active),
                session("5", SessionState.Idle),
            )
        assertEquals(listOf("2*1", "  1*1", "    3", "4"), activityRows(sessions, ActivityFilter.Running, "", hideTableGrip = true).shape())
        assertEquals(listOf("2*1", "  1*1", "    3", "4", "5"), activityRows(sessions, ActivityFilter.All, "", hideTableGrip = true).shape())
    }

    @Test
    fun blockersStayVisibleWhateverTheFilter() {
        val sessions = listOf(session("1", SessionState.Blocked, listOf("2")), session("2", SessionState.Idle, app = "TableGrip"), session("3", SessionState.Active))
        assertEquals(listOf("2*1", "  1"), activityRows(sessions, ActivityFilter.Blocked, "", hideTableGrip = true).shape())
        assertEquals(listOf("2*1"), activityRows(sessions, ActivityFilter.Running, "nothing matches", hideTableGrip = true).shape())
    }

    @Test
    fun hidesTableGripAndSearches() {
        val sessions =
            listOf(session("1", SessionState.Active, app = "TableGrip"), session("2", SessionState.Active, own = true), session("3", SessionState.Active))
        assertEquals(listOf("3"), activityRows(sessions, ActivityFilter.All, "", hideTableGrip = true).shape())
        assertEquals(listOf("1", "2", "3"), activityRows(sessions, ActivityFilter.All, "", hideTableGrip = false).shape())
        assertEquals(listOf("3"), activityRows(sessions, ActivityFilter.All, "SELECT 3", hideTableGrip = false).shape())
    }

    @Test
    fun deadlockCycleStillListsEverySession() {
        val sessions = listOf(session("1", SessionState.Blocked, listOf("2")), session("2", SessionState.Blocked, listOf("1")))
        assertEquals(listOf("1*1", "  2*1"), activityRows(sessions, ActivityFilter.All, "", hideTableGrip = true).shape())
    }

    @Test
    fun durations() {
        assertEquals("850 ms", formatDuration(Duration.ofMillis(850)))
        assertEquals("12 s", formatDuration(Duration.ofSeconds(12)))
        assertEquals("3 min 05 s", formatDuration(Duration.ofSeconds(185)))
        assertEquals("2 h 10 min", formatDuration(Duration.ofMinutes(130)))
    }
}
