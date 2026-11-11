package dev.tablegrip.mcp

import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A JSON-RPC error the peer receives as such: an unknown method, bad parameters. Tool failures are results instead. */
class RpcException(val code: Int, message: String) : Exception(message) {
    companion object {
        const val ParseError = -32700
        const val InvalidRequest = -32600
        const val MethodNotFound = -32601
        const val InvalidParams = -32602
        const val InternalError = -32603
    }
}

/**
 * JSON-RPC 2.0 over MCP's stdio transport: one message per line, UTF-8, nothing else on the output. Requests are handled
 * concurrently; `notifications/cancelled` cancels the one it names, which cancels its query on the server. The server
 * can also send requests of its own (elicitation) and wait for the answer.
 */
class JsonRpcPeer(input: InputStream, output: OutputStream, private val scope: CoroutineScope) {
    private val reader: BufferedReader = input.bufferedReader(Charsets.UTF_8)
    private val writer: Writer = output.bufferedWriter(Charsets.UTF_8)
    private val writeLock = Mutex()
    private val running = ConcurrentHashMap<String, Job>()
    private val awaiting = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val nextId = AtomicLong(1)

    /** Handles a request and returns its result; throw [RpcException] for a JSON-RPC error. */
    fun interface Handler {
        suspend fun handle(method: String, params: JsonObject): JsonElement
    }

    /** Reads until the input ends, which is how an MCP client stops its server. */
    suspend fun serve(handler: Handler, onNotification: suspend (String, JsonObject) -> Unit = { _, _ -> }) {
        while (true) {
            val line = runInterruptible(Dispatchers.IO) { reader.readLine() } ?: break
            if (line.isBlank()) continue
            val message =
                try {
                    Wire.parseToJsonElement(line)
                } catch (e: Exception) {
                    send(error(JsonNull, RpcException.ParseError, "Not JSON: ${e.message}"))
                    continue
                }
            val messages = if (message is JsonArray) message.toList() else listOf(message)
            for (each in messages) dispatch(each as? JsonObject ?: continue, handler, onNotification)
        }
        // The client is gone: nobody can answer our questions any more, but requests already received still finish
        // (a client may write its requests and close its end without waiting).
        awaiting.values.forEach { it.cancel() }
        running.values.toList().joinAll()
    }

    private suspend fun dispatch(message: JsonObject, handler: Handler, onNotification: suspend (String, JsonObject) -> Unit) {
        val method = (message["method"] as? JsonPrimitive)?.content
        val id = message["id"]?.takeIf { it !is JsonNull }
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        when {
            method == null && id != null -> awaiting.remove(key(id))?.complete(message)
            method == null -> Unit
            id == null ->
                if (method == "notifications/cancelled") {
                    params["requestId"]?.let { running[key(it)]?.cancel() }
                } else {
                    onNotification(method, params)
                }
            else -> {
                val job =
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                        try {
                            val result = handler.handle(method, params)
                            send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })
                        } catch (e: CancellationException) {
                            // A cancelled request gets no response.
                        } catch (e: RpcException) {
                            send(error(id, e.code, e.message ?: "Error"))
                        } catch (e: Throwable) {
                            send(error(id, RpcException.InternalError, e.message ?: e.toString()))
                        } finally {
                            running.remove(key(id))
                        }
                    }
                running[key(id)] = job
                job.start()
            }
        }
    }

    /** Sends a request to the client and waits for its result; throws [RpcException] if it answers with an error. */
    suspend fun request(method: String, params: JsonObject, timeoutMillis: Long = 10 * 60_000): JsonObject {
        val id = "tablegrip-${nextId.getAndIncrement()}"
        val answer = CompletableDeferred<JsonObject>()
        val awaitKey = key(JsonPrimitive(id))
        awaiting[awaitKey] = answer
        try {
            send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
            val response = withTimeout(timeoutMillis) { answer.await() }
            (response["error"] as? JsonObject)?.let { error ->
                throw RpcException(error["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: RpcException.InternalError, error["message"]?.jsonPrimitive?.content ?: "Error")
            }
            return response["result"] as? JsonObject ?: JsonObject(emptyMap())
        } finally {
            awaiting.remove(awaitKey)
        }
    }

    suspend fun notify(method: String, params: JsonObject) =
        send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) })

    private suspend fun send(message: JsonObject) =
        withContext(NonCancellable) {
            writeLock.withLock {
                withContext(Dispatchers.IO) {
                    writer.write(Wire.encodeToString(JsonObject.serializer(), message))
                    writer.write("\n")
                    writer.flush()
                }
            }
        }

    private fun error(id: JsonElement, code: Int, message: String) =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject { put("code", code); put("message", message) })
        }

    private fun key(id: JsonElement): String = if (id is JsonPrimitive) (if (id.isString) "s:${id.content}" else "n:${id.content}") else id.toString()

    companion object {
        val Wire = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

/** The object at [name], or an empty one. */
internal fun JsonObject.obj(name: String): JsonObject = this[name] as? JsonObject ?: JsonObject(emptyMap())

internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal val JsonElement.objectOrNull: JsonObject?
    get() = runCatching { jsonObject }.getOrNull()
