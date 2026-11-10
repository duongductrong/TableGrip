package dev.tablegrip.core.driver

import dev.tablegrip.core.i18n.tr
import java.io.PrintWriter
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.Properties
import java.util.logging.Logger
import javax.sql.DataSource

/** Hands a pool connections from one [driver] instance, which may live in a class loader the pool can't see. */
internal class DriverDataSource(private val driver: Driver, private val url: String, private val properties: Properties) : DataSource {
    private var loginTimeout = 0

    override fun getConnection(): Connection =
        driver.connect(url, properties) ?: throw SQLException(tr("{0} doesn't accept the URL {1}", driver.javaClass.name, url))

    override fun getConnection(username: String?, password: String?): Connection {
        val props = Properties().apply { putAll(properties) }
        username?.let { props.setProperty("user", it) }
        password?.let { props.setProperty("password", it) }
        return driver.connect(url, props) ?: throw SQLException(tr("{0} doesn't accept the URL {1}", driver.javaClass.name, url))
    }

    override fun getLogWriter(): PrintWriter? = null

    override fun setLogWriter(out: PrintWriter?) = Unit

    override fun setLoginTimeout(seconds: Int) {
        loginTimeout = seconds
    }

    override fun getLoginTimeout(): Int = loginTimeout

    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()

    override fun <T> unwrap(iface: Class<T>): T = if (iface.isInstance(this)) iface.cast(this) else throw SQLException("Not a wrapper for $iface")

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}
