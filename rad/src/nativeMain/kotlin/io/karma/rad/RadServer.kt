/*
 * Copyright 2025 Karma Krafts & associates
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.karma.rad

import io.karma.skroll.Logger
import io.karma.skroll.asKtorLogger
import io.karma.skroll.error
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.intellij.lang.annotations.Language

typealias CIOServer = EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>

internal class RadServer( // @formatter:off
    val port: Int,
    val address: String,
    val config: RadConfig
) { // @formatter:on
    companion object {
        private const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0"
    }

    private val logger: Logger = Logger.create(this::class)

    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        logger.error(error) { "Coroutine could not finish" }
    })

    private val client: RadClient = RadClient()
    private val index: RadIndex = RadIndex(config, coroutineScope, client.client)

    init {
        logger.info { "Starting embedded server" }
    }

    private val embeddedServer: CIOServer = embeddedServer( // @formatter:off
        factory = CIO,
        environment = applicationEnvironment {
            log = logger.asKtorLogger()
        },
        configure = {
            connectors += EngineConnectorBuilder().apply {
                port = this@RadServer.port
                host = this@RadServer.address
            }
        },
        module = { configure() }
    ) // @formatter:on

    private suspend fun RoutingCall.respondHtml(html: String) {
        respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
    }

    private fun ResponseHeaders.appendFrom(headers: Headers, vararg keys: String) {
        for (key in keys) {
            append(key, headers[key] ?: continue)
        }
    }

    private fun ResponseHeaders.appendCommonFrom(headers: Headers) {
        appendFrom(
            headers,
            "x-checksum-sha1",
            "x-checksum-sha512",
            "x-checksum-md5",
            "x-content-type-options",
            "x-frame-options",
            "x-gitlab-meta",
            "cf-cache-status"
        )
    }

    private suspend fun findRoutingTarget(path: String, userAgent: String): HttpResponse? {
        return index.projects.value.filter { it.path in path }.map {
            coroutineScope.async {
                val targetPath = "${it.links.self}/packages$path"
                logger.debug { "Attempting to resolve at $targetPath" }
                client.client.get(targetPath) {
                    headers {
                        append("user-agent", userAgent)
                        append("accept", "*/*")
                    }
                }
            }
        }.awaitAll().find { it.status == HttpStatusCode.OK }
    }

    private suspend fun RoutingContext.handleProxyRequest(sendData: Boolean = true) {
        val request = call.request
        val path = request.path()
        logger.debug { "Got GET request: $path" }
        val userAgent = request.header("user-agent") ?: DEFAULT_USER_AGENT
        val proxiedResponse = findRoutingTarget(path, userAgent)
        if (proxiedResponse == null) {
            logger.debug { "Couldn't find resource $path" }
            call.respond(HttpStatusCode.NotFound)
            return
        }
        logger.debug { "Found resource: $proxiedResponse" }
        call.response.apply {
            cacheControl(CacheControl.NoCache(null))
            headers.apply {
                append("user-agent", userAgent)
                append("vary", "Origin")
                appendCommonFrom(proxiedResponse.headers)
            }
        }
        if (sendData) {
            call.respondBytes(ContentType.Application.OctetStream, HttpStatusCode.OK, proxiedResponse::bodyAsBytes)
            return
        }
        call.respond(HttpStatusCode.OK) // For handling HEAD requests
    }

    private fun Application.configure() {
        install(ContentNegotiation) {
            json(json)
        }
        install(StatusPages)
        routing {
            get("/status") {
                val projectList = index.projects.value.joinToString(
                    "\n"
                ) { "<li><b>${it.name}</b>: ${it.links.self}</li>" }
                call.respondHtml(
                    """
                    <html lang='en'>
                        <head>
                            <meta charset='UTF-8'>
                            <title>Rad Status</title>
                        </head>
                        <body>
                            <h1>Serving projects</h1>
                            <ul>
                                $projectList
                            </ul>
                        </body>
                    </html>
                """.trimIndent()
                )
            }
            head("/maven/{...}") {
                handleProxyRequest(false)
            }
            get("/maven/{...}") {
                handleProxyRequest()
            }
        }
    }

    fun run() {
        acceptCommands()
        embeddedServer.start(wait = true)
    }

    private fun stop() {
        logger.info { "Stopping embedded server" }
        embeddedServer.stop()
        index.close()
        client.close()
        logger.info { "Cancelling coroutine scope" }
        coroutineScope.cancel()
    }

    private fun acceptCommands() {
        coroutineScope.launch {
            logger.info { "Starting command coroutine" }
            while (true) {
                when (readlnOrNull() ?: yield()) {
                    "exit", "stop", "quit" -> break
                }
            }
            logger.info { "Stopping command coroutine" }
            stop()
        }
    }
}