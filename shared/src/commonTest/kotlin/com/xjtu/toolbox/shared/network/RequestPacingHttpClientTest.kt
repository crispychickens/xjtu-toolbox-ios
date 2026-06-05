package com.xjtu.toolbox.shared.network

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RequestPacingHttpClientTest {
    @Test
    fun firstRequestDoesNotSleepAndFollowingRequestsRespectMinimumInterval() = runTest {
        val clock = FakePacingClock(now = 1_000)
        val sleeper = AdvancingSleeper(clock)
        val delegate = PacingRecordingHttpClient(clock)
        val client = RequestPacingHttpClient(
            delegate = delegate,
            minimumIntervalMillis = 750,
            clock = clock,
            sleeper = sleeper,
        )

        client.execute(HttpRequest("https://jwxt.xjtu.edu.cn/one"))
        client.execute(HttpRequest("https://jwxt.xjtu.edu.cn/two"))
        client.execute(HttpRequest("https://jwxt.xjtu.edu.cn/three"))

        assertEquals(listOf(1_000L, 1_750L, 2_500L), delegate.executionTimes)
        assertEquals(listOf(750L, 750L), sleeper.sleepCalls)
    }

    @Test
    fun concurrentRequestsAreSerializedThroughTheSamePacingWindow() = runTest {
        val clock = FakePacingClock(now = 10_000)
        val sleeper = AdvancingSleeper(clock)
        val delegate = PacingRecordingHttpClient(clock)
        val client = RequestPacingHttpClient(
            delegate = delegate,
            minimumIntervalMillis = 500,
            clock = clock,
            sleeper = sleeper,
        )

        val first = async { client.execute(HttpRequest("https://ncard.xjtu.edu.cn/one")) }
        val second = async { client.execute(HttpRequest("https://ncard.xjtu.edu.cn/two")) }
        val third = async { client.execute(HttpRequest("https://ncard.xjtu.edu.cn/three")) }
        first.await()
        second.await()
        third.await()

        assertEquals(listOf(10_000L, 10_500L, 11_000L), delegate.executionTimes.sorted())
        assertEquals(listOf(500L, 500L), sleeper.sleepCalls)
    }

    @Test
    fun factoryReturnsDelegateWhenNoPacingIsRequested() {
        val delegate = PacingRecordingHttpClient(FakePacingClock(now = 0))

        val wrapped = RequestPacingHttpClientFactory.wrap(delegate, minimumIntervalMillis = 0)

        assertSame(delegate, wrapped)
    }
}

private class FakePacingClock(now: Long) : RequestPacingClock {
    var now = now

    override fun nowMillis(): Long = now
}

private class AdvancingSleeper(
    private val clock: FakePacingClock,
) : RequestPacingSleeper {
    val sleepCalls = mutableListOf<Long>()

    override suspend fun sleep(millis: Long) {
        sleepCalls += millis
        clock.now += millis
    }
}

private class PacingRecordingHttpClient(
    private val clock: RequestPacingClock,
) : HttpClient {
    val executionTimes = mutableListOf<Long>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        executionTimes += clock.nowMillis()
        return HttpResponse(code = 200, finalUrl = request.url)
    }
}
