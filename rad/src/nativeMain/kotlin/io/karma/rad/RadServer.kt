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
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

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

    private val logger: Logger = Logger(this::class)

    private val coroutineScope: CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, error ->
            logger.error(error) { "Coroutine could not finish" }
        })

    private lateinit var commandJob: Job
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

    private fun Application.configure() {
        install(ContentNegotiation) {
            json(json)
        }
        // TODO: use ktor-error-pages once available
        install(StatusPages)
        routing {
            statusRouting()
            mavenRouting()
            binariesRouting()
        }
    }

    @OptIn(NativeRuntimeApi::class)
    private fun Routing.statusRouting() {
        get("/status") {
            val projectList = index.projects.value.joinToString(
                "\n"
            ) {
                val latestRelease = index.releases.value[it.path]?.firstOrNull()?.name
                """
                    <div class="list-entry">
                        <b>${it.name}</b>: ${it.links.self}<br>
                        Latest release: ${latestRelease ?: "n/a"}
                    </div>
                """
            }
            call.respondHtml(
                """
                    <html lang='en'>
                        <head>
                            <meta charset='UTF-8'>
                            <title>RAD Status</title>
                            <style>
                                html {
                                    background: #121212;
                                    color: #EEEEEE;
                                }
                                h1 {
                                    color: #1288FF
                                }
                                h2 {
                                    color: #AAAAAA;
                                }
                                .list-entry {
                                    padding: 8px;
                                    margin: auto auto 16px auto;
                                    border: 1px solid #AAAAAA;
                                    border-radius: 6px;
                                    background: #222222;
                                }
                            </style>
                        </head>
                        <body>
                            <h1>RAD STATUS</h1>
                            Version: n/a<br>
                            GitLab Instance: ${config.instance}<br>
                            Groups: ${config.groups.size}<br>
                            Projects: ${index.projects.value.size}<br>
                            Releases: ${index.releases.value.values.flatten().size}<br>
                            Object Heap: ${GC.targetHeapBytes / 1024 / 1024}MB
                            
                            <h2>SERVING PROJECTS</h2>
                            $projectList
                        </body>
                    </html>
                """
            )
        }
    }

    private suspend inline fun findMavenRoutingTarget(
        path: String, userAgent: String
    ): HttpResponse? {
        val projects = index.projects.value
        if (projects.isEmpty()) return null
        return projects.filter { it.path in path }.map {
            coroutineScope.async {
                val targetPath = "${it.links.self}/packages$path"
                logger.debug { "Attempting to resolve maven target at $targetPath" }
                client.client.head(targetPath) {
                    headers {
                        append("user-agent", userAgent)
                        append("accept", "*/*")
                    }
                }
            }
        }.run {
            val result = awaitAll().find { it.status == HttpStatusCode.OK }
            forEach { it.cancelAndJoin() }
            result
        }
    }

    @OptIn(InternalAPI::class)
    private suspend fun RoutingContext.handleProxyRequest(
        routeFinder: suspend (String, String) -> HttpResponse?
    ) {
        val request = call.request
        val path = request.path()
        logger.debug { "Got GET request: $path" }
        val userAgent = request.header("user-agent") ?: DEFAULT_USER_AGENT
        val proxiedResponse = routeFinder(path, userAgent)
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
        call.respondRedirect(proxiedResponse.request.url, true)
    }

    private fun Routing.mavenRouting() {
        head("/maven/{...}") {
            handleProxyRequest(::findMavenRoutingTarget)
        }
        get("/maven/{...}") {
            handleProxyRequest(::findMavenRoutingTarget)
        }
    }

    private suspend fun findBinaryRoutingTarget(
        project: Project, path: String, userAgent: String
    ): HttpResponse {
        val targetPath = "${project.links.self}/packages$path"
        logger.debug { "Attempting to resolve binary target at $targetPath" }
        return client.client.head(targetPath) {
            headers {
                append("user-agent", userAgent)
                append("accept", "*/*")
            }
        }
    }

    private fun Parameters.findProject(): Project? {
        val projects = index.projects.value
        if (projects.isEmpty()) return null
        val project = this["project"]
        logger.debug { "Looking for project '$project' to fetch binaries from" }
        return projects.find { it.path == project }
    }

    private suspend fun RoutingContext.binariesRouting() {
        val variables = call.request.pathVariables

        val params = variables.getAll("params")
        if (params == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val project = variables.findProject()
        if (project == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        // Handle requests for latest binaries through index
        if (params[0] == "latest") {
            val releases = index.releases.value
            if (releases.isEmpty()) {
                call.respond(HttpStatusCode.NotFound)
                return
            }

            val projectName = variables["project"]
            logger.debug { "Resolving latest version for $projectName" }

            val latestVersion = index.releases.value[projectName]?.firstOrNull()?.tagName
            if (latestVersion == null) {
                call.respond(HttpStatusCode.NotFound)
                return
            }

            val resolvedPath = "$latestVersion/${params.slice(1..<params.size).joinToString("/")}"
            logger.debug { "Resolved path for latest version: $resolvedPath" }
            handleProxyRequest { _, agent ->
                findBinaryRoutingTarget(project, "/generic/build/$resolvedPath", agent)
            }
            return
        }
        handleProxyRequest { _, agent ->
            findBinaryRoutingTarget(project, "/generic/build/${params.joinToString("/")}", agent)
        }
    }

    private fun Routing.binariesRouting() {
        head("/binaries/{project}/{params...}") {
            binariesRouting()
        }
        get("/binaries/{project}/{params...}") {
            binariesRouting()
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
        runBlocking {
            commandJob.cancelAndJoin()
        }
        coroutineScope.cancel()
    }

    private fun acceptCommands() {
        commandJob = coroutineScope.launch {
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