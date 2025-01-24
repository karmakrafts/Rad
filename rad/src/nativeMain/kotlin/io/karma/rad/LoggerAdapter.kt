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

import io.karma.skroll.LogLevel
import io.karma.skroll.Logger
import io.karma.skroll.debug
import io.karma.skroll.error
import io.karma.skroll.fatal
import io.karma.skroll.info
import io.karma.skroll.warn
import io.ktor.util.logging.LogLevel as KtorLogLevel
import io.ktor.util.logging.Logger as KtorLogger

private class LoggerAdapter(
    val logger: Logger
) : KtorLogger {
    override fun debug(message: String) = logger.debug { message }

    override fun debug(message: String, cause: Throwable) = logger.debug(cause) { message }

    override fun error(message: String) = logger.error { message }

    override fun error(message: String, cause: Throwable) = logger.error(cause) { message }

    override fun info(message: String) = logger.info { message }

    override fun info(message: String, cause: Throwable) = logger.info(cause) { message }

    override fun trace(message: String) = logger.fatal { message }

    override fun trace(message: String, cause: Throwable) = logger.fatal(cause) { message }

    override fun warn(message: String) = logger.warn { message }

    override fun warn(message: String, cause: Throwable) = logger.warn(cause) { message }

    override val level: KtorLogLevel
        get() = when (logger.level) {
            LogLevel.DEBUG -> KtorLogLevel.DEBUG
            LogLevel.INFO -> KtorLogLevel.INFO
            LogLevel.WARN -> KtorLogLevel.WARN
            LogLevel.ERROR -> KtorLogLevel.ERROR
            LogLevel.FATAL -> KtorLogLevel.TRACE
        }
}

internal fun Logger.asKtorLogger(): KtorLogger = LoggerAdapter(this)