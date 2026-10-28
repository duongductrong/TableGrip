package dev.datagrok.core.driver

/**
 * Runs [block] with [loader] as the thread's context class loader. Client libraries (Netty, Lettuce, the MongoDB driver)
 * look classes up through it; an adapter calls into its client inside this so those lookups find the client's own
 * classes, not the app's. Threads the client starts meanwhile inherit it.
 */
inline fun <T> withContextClassLoader(loader: ClassLoader, block: () -> T): T {
    val thread = Thread.currentThread()
    val previous = thread.contextClassLoader
    thread.contextClassLoader = loader
    try {
        return block()
    } finally {
        thread.contextClassLoader = previous
    }
}
