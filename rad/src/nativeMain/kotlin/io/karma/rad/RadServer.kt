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
import io.karma.skroll.error
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
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

typealias CIOServer = EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>

internal class RadServer( // @formatter:off
    val port: Int,
    val address: String,
    val config: RadConfig
) { // @formatter:on
    companion object {
        private val pathRegex: Regex = Regex("/.+")
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

    private fun Application.configure() {
        install(ContentNegotiation) {
            json(json)
        }
        routing {
            get("/maven/{...}") {
                val path = call.request.path()
                logger.debug { "Got maven request: $path" }
                // @formatter:off
                val response = index.projects.value
                    .filter { it.path in path } // Pre-filter by project slug
                    .map {
                        coroutineScope.async {
                            val targetPath = "${it.links.self}/packages$path"
                            logger.debug { "Attempting to resolve at $targetPath" }
                            client.client.get(targetPath)
                        }
                    }
                    .awaitAll()
                    .find { it.status == HttpStatusCode.OK }
                // @formatter:on
                if (response == null) {
                    logger.debug { "Couldn't find resource $path" }
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                logger.debug { "Found resource: $response" }
                call.respondBytes(ContentType.Application.OctetStream, HttpStatusCode.OK, response::bodyAsBytes)
            }
            get("/health") {
                call.respondText("Proxy is running", status = HttpStatusCode.OK)
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
                    "exit" -> break
                }
            }
            logger.info { "Stopping command coroutine" }
            stop()
        }
    }
}