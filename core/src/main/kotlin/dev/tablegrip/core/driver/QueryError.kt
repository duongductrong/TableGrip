package dev.tablegrip.core.driver

import java.sql.SQLException

/**
 * Why a statement failed, as the driver reported it: the message the user sees, plus what a fix needs to know.
 * [position] is a 0-based offset into the statement, where the engine says (PostgreSQL does).
 */
data class QueryError(
    val message: String,
    val sqlState: String? = null,
    val vendorCode: Int? = null,
    val position: Int? = null,
) {
    companion object {
        fun of(error: Throwable): QueryError {
            val sql = generateSequence(error) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
            return QueryError(
                message = error.message ?: error.toString(),
                sqlState = sql?.sqlState?.takeIf { it.isNotBlank() },
                vendorCode = sql?.errorCode?.takeIf { it != 0 },
                position = sql?.let(::positionOf),
            )
        }

        /**
         * pgjdbc's `PSQLException.getServerErrorMessage().getPosition()`, 1-based. The driver lives in its own class
         * loader, so it is reached by reflection; any other driver simply has no such method.
         */
        private fun positionOf(error: SQLException): Int? =
            runCatching {
                val server = error.javaClass.getMethod("getServerErrorMessage").invoke(error) ?: return null
                (server.javaClass.getMethod("getPosition").invoke(server) as? Int)?.takeIf { it > 0 }?.minus(1)
            }.getOrNull()
    }
}
