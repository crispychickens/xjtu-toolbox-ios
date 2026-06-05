package com.xjtu.toolbox.shared

import com.xjtu.toolbox.shared.auth.LoginOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PreviewServicesTest {
    @Test
    fun appServicesProvideSharedAuthAndFeaturePreview() = runTest {
        val services = XjtuToolboxPreviewFactory.appServices()

        val login = services.authManager.login("3124000000", "password")
        assertIs<LoginOutcome.Success>(login)

        val dashboard = services.coreFeatureService.dashboard(dayOfWeek = 3, noticePageSize = 2)
        assertEquals(2, dashboard.todayCourses.size)
        assertEquals(2, dashboard.notices.size)
        assertNotNull(dashboard.campusCard)

        val grades = services.coreFeatureService.grades()
        assertEquals(2, grades.grades.size)
        assertEquals(5.0, grades.totalCredits)
        assertNotNull(grades.weightedGpa)
    }
}
