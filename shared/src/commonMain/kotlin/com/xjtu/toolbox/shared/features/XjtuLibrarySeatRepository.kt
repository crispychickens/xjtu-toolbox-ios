package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.CasHtmlParser
import com.xjtu.toolbox.shared.auth.HttpSiteSession
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteVerificationContext
import com.xjtu.toolbox.shared.auth.SiteVerificationRequiredException
import com.xjtu.toolbox.shared.auth.SiteSession
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.parsing.LibrarySeatAreas
import com.xjtu.toolbox.shared.parsing.LibrarySeatParser

class XjtuLibrarySeatRepository(
    private val baseUrl: String = "http://rg.lib.xjtu.edu.cn:8086",
) : LibrarySeatRepository {
    override suspend fun snapshot(session: SiteSession, areaCode: String?): LibrarySeatSnapshot {
        val httpSession = session.asLibraryHttpSession()
        val selectedAreaCode = areaCode
            ?.takeIf { it in LibrarySeatAreas.byCode }
            ?: LibrarySeatAreas.DEFAULT_AREA_CODE
        val seatResponse = httpSession.execute(libraryRequest("$baseUrl/qseat?sp=$selectedAreaCode", ajax = true))
        validateSeatResponse(seatResponse.finalUrl, seatResponse.bodyText)
        val bookingHtml = runCatching {
            val bookingResponse = httpSession.execute(libraryRequest("$baseUrl/my/"))
            if (bookingResponse.isSuccessful && !bookingResponse.isAuthPage()) bookingResponse.bodyText else null
        }.getOrNull()
        return LibrarySeatParser.parseSeatSnapshot(
            json = seatResponse.bodyText,
            selectedAreaCode = selectedAreaCode,
            bookingHtml = bookingHtml,
        )
    }

    override suspend fun bookSeat(
        session: SiteSession,
        seatId: String,
        areaCode: String,
        allowSwap: Boolean,
    ): LibrarySeatBookingResult {
        val httpSession = session.asLibraryHttpSession()
        val normalizedSeatId = seatId.trim().uppercase()
        val response = httpSession.execute(libraryRequest("$baseUrl/seat/?kid=$normalizedSeatId&sp=$areaCode"))
        if (response.isAuthPage()) throwAuthRequired(response.finalUrl, response.bodyText)
        val success = response.finalUrl.contains("/my/", ignoreCase = true) ||
            response.finalUrl.contains("/seat/my/", ignoreCase = true)
        if (success) {
            return LibrarySeatBookingResult(
                success = true,
                message = "座位 $normalizedSeatId 预约成功",
                finalUrl = response.finalUrl,
            )
        }
        if (allowSwap && response.bodyText.hasExistingBookingHint()) {
            return swapSeat(httpSession, normalizedSeatId, areaCode)
        }
        return LibrarySeatBookingResult(
            success = false,
            message = LibrarySeatParser.parseBookingFailure(response.bodyText),
            finalUrl = response.finalUrl,
        )
    }

    private suspend fun swapSeat(
        session: HttpSiteSession,
        seatId: String,
        areaCode: String,
    ): LibrarySeatBookingResult {
        val response = session.execute(libraryRequest("$baseUrl/updateseat/?kid=$seatId&sp=$areaCode"))
        if (response.isAuthPage()) throwAuthRequired(response.finalUrl, response.bodyText)
        val success = response.finalUrl.contains("/my/", ignoreCase = true) ||
            response.bodyText.contains("成功换座") ||
            response.bodyText.contains("成功")
        return if (success) {
            LibrarySeatBookingResult(
                success = true,
                message = "已换座到 $seatId",
                finalUrl = response.finalUrl,
            )
        } else {
            LibrarySeatBookingResult(
                success = false,
                message = "换座失败：${LibrarySeatParser.parseBookingFailure(response.bodyText)}",
                finalUrl = response.finalUrl,
            )
        }
    }

    private fun libraryRequest(url: String, ajax: Boolean = false): HttpRequest {
        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148",
            "Referer" to "$baseUrl/seat/",
        )
        if (ajax) {
            headers["X-Requested-With"] = "XMLHttpRequest"
            headers["Accept"] = "application/json, text/javascript, */*; q=0.01"
        }
        return HttpRequest(url = url, headers = headers)
    }

    private fun validateSeatResponse(finalUrl: String, bodyText: String) {
        if (bodyText.isBlank()) error("图书馆座位系统返回空数据")
        if (finalUrl.isAuthUrl() || bodyText.isAuthBody()) throwAuthRequired(finalUrl, bodyText)
        if (!bodyText.trimStart().startsWith("{")) error("图书馆座位数据格式异常")
    }

    private fun com.xjtu.toolbox.shared.network.HttpResponse.isAuthPage(): Boolean =
        finalUrl.isAuthUrl() || bodyText.isAuthBody()

    private fun String.isAuthUrl(): Boolean =
        contains("login.xjtu.edu.cn", ignoreCase = true) ||
            contains("/cas/login", ignoreCase = true)

    private fun String.isAuthBody(): Boolean =
        contains("id=\"loginForm\"", ignoreCase = true) ||
            contains("name=\"execution\"", ignoreCase = true) ||
            contains("cas/login", ignoreCase = true) ||
            contains("login.xjtu.edu.cn", ignoreCase = true) ||
            CasHtmlParser.isSafetyVerifyPage(this)

    private fun throwAuthRequired(finalUrl: String, bodyText: String): Nothing =
        throw SiteVerificationRequiredException(
            site = SiteKey.LIBRARY,
            message = "图书馆座位需要补授权",
            verificationContext = SiteVerificationContext(finalUrl = finalUrl, bodyText = bodyText),
        )

    private fun String.hasExistingBookingHint(): Boolean =
        contains("已有预约") ||
            contains("已预约") ||
            contains("已经预约") ||
            contains("存在预约") ||
            contains("换座")

    private fun SiteSession.asLibraryHttpSession(): HttpSiteSession =
        (this as? HttpSiteSession)
            ?.also { require(it.site == SiteKey.LIBRARY) { "图书馆座位 repository requires LIBRARY session" } }
            ?: error("图书馆座位 repository requires an HttpSiteSession")
}
