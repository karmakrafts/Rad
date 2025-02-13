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
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class RadIndex(
    private val config: RadConfig, private val coroutineScope: CoroutineScope, private val client: HttpClient
) : AutoCloseable {
    private val logger: Logger = Logger(this::class)
    private val endpoint: String = "https://${config.instance}/api/v4"
    private val isRunning: MutableStateFlow<Boolean> = MutableStateFlow(true)
    internal val projects: MutableStateFlow<List<Project>> = MutableStateFlow(emptyList())
    internal val releases: MutableStateFlow<Map<String, List<Release>>> = MutableStateFlow(emptyMap())
    private val pollJob: Job

    init {
        logger.info { "Starting polling coroutine" }
        pollJob = coroutineScope.launch {
            while (isRunning.value) {
                fetchProjects()
                fetchReleases()
                delay(config.pollDelay)
            }
        }
    }

    override fun close() {
        logger.info { "Stopping polling coroutine" }
        isRunning.value = false
        runBlocking {
            pollJob.join()
        }
    }

    private suspend fun fetchProjects() {
        logger.debug { "Fetching project information.." }
        projects.emit(config.groups.map { group ->
            coroutineScope.async {
                val endpoint = "$endpoint/groups/${group.percentEncode()}/projects"
                logger.debug { "Sending request to endpoint $endpoint" }
                val response = client.get(endpoint)
                if (response.status != HttpStatusCode.OK) {
                    logger.error { "Could not make request to $endpoint: ${response.status.description}" }
                    return@async emptyList()
                }
                response.body<List<Project>>()
            }
        }.awaitAll().flatten())
    }

    private suspend fun fetchReleases() {
        releases.emit(projects.value.map { project ->
            coroutineScope.async {
                val endpoint = "$endpoint/projects/${project.pathWithNamespace.percentEncode()}/releases"
                logger.debug { "Sending request to endpoint $endpoint" }
                val response = client.get(endpoint)
                if (response.status != HttpStatusCode.OK) {
                    logger.error { "Could not make request to $endpoint: ${response.status.description}" }
                    return@async project.path to emptyList()
                }
                project.path to response.body<List<Release>>()
            }
        }.awaitAll().toMap())
    }
}