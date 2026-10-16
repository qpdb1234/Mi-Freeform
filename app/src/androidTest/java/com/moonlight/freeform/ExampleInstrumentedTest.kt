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
        // 包名取 BuildConfig：applicationId 改过之后，
        // 这里写死的字符串会让断言一直失败。
        assertEquals(com.moonlight.freeform.BuildConfig.APPLICATION_ID, appContext.packageName)
    }
}
