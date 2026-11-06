package com.moonlight.freeform

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // Take the package name from BuildConfig: after the applicationId changes,
        // a hardcoded string here would make the assertion fail forever.
        assertEquals(com.moonlight.freeform.BuildConfig.APPLICATION_ID, appContext.packageName)
    }
}
