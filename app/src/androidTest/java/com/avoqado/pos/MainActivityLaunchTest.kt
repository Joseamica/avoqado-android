package com.avoqado.pos

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {

    @Test
    fun mainActivityStaysResumedWhenCustomerDisplayIsAttached() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun navigationBarIsHiddenInMainActivity() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                assertNotNull(insets)
                assertFalse(insets!!.isVisible(WindowInsetsCompat.Type.navigationBars()))
            }
        }
    }
}
