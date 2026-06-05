package com.xjtu.toolbox.shared.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface RequestPacingClock {
    fun nowMillis(): Long
}

@OptIn(kotlin.time.ExperimentalTime::class)
object SystemRequestPacingClock : RequestPacingClock {
    override fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}

interface RequestPacingSleeper {
    suspend fun sleep(millis: Long)
}

object CoroutineRequestPacingSleeper : RequestPacingSleeper {
    override suspend fun sleep(millis: Long) {
        delay(millis)
    }
}

class RequestPacingHttpClient(
    private val delegate: HttpClient,
    private val minimumIntervalMillis: Long,
    private val clock: RequestPacingClock = SystemRequestPacingClock,
    private val sleeper: RequestPacingSleeper = CoroutineRequestPacingSleeper,
) : HttpClient {
    private val mutex = Mutex()
    private var nextAllowedAtMillis = 0L

    init {
        require(minimumIntervalMillis >= 0) { "minimumIntervalMillis must be >= 0" }
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        paceNextRequest()
        return delegate.execute(request)
    }

    private suspend fun paceNextRequest() {
        if (minimumIntervalMillis == 0L) return
        mutex.withLock {
            val now = clock.nowMillis()
            val waitMillis = nextAllowedAtMillis - now
            if (waitMillis > 0) {
                sleeper.sleep(waitMillis)
            }
            nextAllowedAtMillis = clock.nowMillis() + minimumIntervalMillis
        }
    }
}

object RequestPacingHttpClientFactory {
    fun wrap(delegate: HttpClient, minimumIntervalMillis: Long): HttpClient =
        if (minimumIntervalMillis <= 0) {
            delegate
        } else {
            RequestPacingHttpClient(
                delegate = delegate,
                minimumIntervalMillis = minimumIntervalMillis,
            )
        }
}
