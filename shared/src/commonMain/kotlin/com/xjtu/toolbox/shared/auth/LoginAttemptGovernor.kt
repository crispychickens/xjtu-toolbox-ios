package com.xjtu.toolbox.shared.auth

interface AttemptClock {
    fun nowMillis(): Long
}

@OptIn(kotlin.time.ExperimentalTime::class)
object SystemAttemptClock : AttemptClock {
    override fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}

sealed interface LoginAttemptDecision {
    data object Allowed : LoginAttemptDecision
    data class Rejected(
        val retryAfterMillis: Long,
        val reason: String,
    ) : LoginAttemptDecision
}

data class LoginAttemptRecord(
    val transientFailureCount: Int = 0,
    val retryAtMillis: Long? = null,
)

interface LoginAttemptStore {
    fun load(key: String): LoginAttemptRecord?
    fun save(key: String, record: LoginAttemptRecord)
    fun remove(key: String)
    fun clear()
}

class InMemoryLoginAttemptStore : LoginAttemptStore {
    private val records = mutableMapOf<String, LoginAttemptRecord>()

    override fun load(key: String): LoginAttemptRecord? = records[key]

    override fun save(key: String, record: LoginAttemptRecord) {
        records[key] = record
    }

    override fun remove(key: String) {
        records.remove(key)
    }

    override fun clear() {
        records.clear()
    }
}

class LoginAttemptGovernor(
    private val clock: AttemptClock = SystemAttemptClock,
    private val initialBackoffMillis: Long = 2_000,
    private val maxBackoffMillis: Long = 2_000,
    private val store: LoginAttemptStore = InMemoryLoginAttemptStore(),
) {
    fun beforePasswordLogin(username: String): LoginAttemptDecision {
        return beforeAttempt(
            scope = passwordScope(username),
            rejectedReason = "登录请求过于频繁，请稍后再试",
        )
    }

    fun recordPasswordSuccess(username: String) {
        recordSuccess(passwordScope(username))
    }

    fun recordPasswordTransientFailure(username: String) {
        recordTransientFailure(passwordScope(username))
    }

    fun beforeChallengeResponse(challengeKey: String): LoginAttemptDecision {
        return beforeAttempt(
            scope = challengeScope(challengeKey),
            rejectedReason = "验证请求过于频繁，请稍后再试",
        )
    }

    fun recordChallengeSuccess(challengeKey: String) {
        recordSuccess(challengeScope(challengeKey))
    }

    fun recordChallengeFailure(challengeKey: String) {
        recordTransientFailure(challengeScope(challengeKey))
    }

    fun beforeAttempt(scope: String, rejectedReason: String): LoginAttemptDecision {
        val record = store.load(scope) ?: return LoginAttemptDecision.Allowed
        val now = clock.nowMillis()
        val retryAt = record.retryAtMillis
        return if (retryAt != null && now < retryAt) {
            LoginAttemptDecision.Rejected(
                retryAfterMillis = retryAt - now,
                reason = rejectedReason,
            )
        } else {
            LoginAttemptDecision.Allowed
        }
    }

    fun recordSuccess(scope: String) {
        store.remove(scope)
    }

    fun recordTransientFailure(scope: String) {
        val now = clock.nowMillis()
        val previous = store.load(scope) ?: LoginAttemptRecord()
        val transientFailureCount = previous.transientFailureCount + 1
        val multiplier = 1L shl (transientFailureCount - 1).coerceAtMost(8)
        val backoff = (initialBackoffMillis * multiplier).coerceAtMost(maxBackoffMillis)
        store.save(
            key = scope,
            record = LoginAttemptRecord(
                transientFailureCount = transientFailureCount,
                retryAtMillis = now + backoff,
            ),
        )
    }

    fun clear(username: String? = null) {
        if (username == null) store.clear() else store.remove(passwordScope(username))
    }

    private fun passwordScope(username: String): String = "password:$username"

    private fun challengeScope(challengeKey: String): String = "challenge:$challengeKey"
}

typealias InMemoryLoginAttemptGovernor = LoginAttemptGovernor
