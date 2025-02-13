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

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.int
import io.karma.skroll.LogFilter
import io.karma.skroll.LogLevel
import io.karma.skroll.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.io.decodeFromSource

private val cli: CLI = CLI()

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    cli.main(args)
}

class CLI : CliktCommand() {
    // @formatter:off
    val address: String by option()
        .default("127.0.0.1")
        .help("Specifies the address or domain of the Rad server")

    val port: Int by option()
        .int()
        .default(1814)
        .help("Specifies the port of the Rad server")

    val debug: Boolean by option()
        .boolean()
        .default(false)
        .help("Enable debug log output")

    val logFile: String? by option()
        .help("Specifies a file to write the log output to")

    val config: String by option()
        .default("rad.json")
        .help("Specifies the configuration file")
    // @formatter:on

    private lateinit var server: RadServer

    @OptIn(ExperimentalSerializationApi::class)
    override fun run() {
        Logger.setDefaultConfig {
            val commonFilter = if (debug) LogFilter.always else LogFilter.levelsExcept(LogLevel.DEBUG)
            consoleAppender( // @formatter:off
                pattern = "{{levelColor}}>>  {{levelSymbol}}\t{{datetime(hh:mm:ss.SSS)}} ({{name}} @ {{thread}}) {{message}}{{r}}",
                filter = commonFilter
            ) // @formatter:on
            logFile?.let {
                fileAppender( // @formatter:off
                    pattern = "[{{level}}][{{datetime(yyyy/MM/dd hh:mm:ss.SSS)}}] ({{name}} @ {{thread}}) {{message}}",
                    path = Path(it),
                    filter = commonFilter
                ) // @formatter:on
            }
        }
        val logger = Logger(this::class)
        val config = SystemFileSystem.source(Path(config)).buffered().use {
            json.decodeFromSource<RadConfig>(it)
        }
        logger.debug { "Config: $config" }
        server = RadServer( // @formatter:off
            port = port,
            address = address,
            config = config
        ) // @formatter:on
        server.run()
    }
}