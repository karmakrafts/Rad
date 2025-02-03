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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Project( // @formatter:off
    val id: Long,
    val name: String,
    val path: String,
    @SerialName("path_with_namespace") val pathWithNamespace: String,
    @SerialName("default_branch") val defaultBranch: String,
    @SerialName("_links") val links: ProjectLinks,
) // @formatter:on

@Serializable
data class ProjectLinks( // @formatter:off
    val self: String,
    val events: String
) // @formatter:on

@Serializable
data class Release( // @formatter:off
    val name: String,
    @SerialName("tag_name") val tagName: String,
    val description: String,
    @SerialName("upcoming_release") val upcomingRelease: Boolean,
    val commit: ReleaseCommit
) // @formatter:on

@Serializable
data class ReleaseCommit( // @formatter:off
    val id: String,
    @SerialName("short_id") val shortId: String,
    @SerialName("web_url") val webUrl: String
) // @formatter:on