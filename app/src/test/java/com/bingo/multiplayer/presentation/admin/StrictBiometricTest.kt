package com.bingo.multiplayer.presentation.admin

import android.app.Activity
import android.app.Application
import com.bingo.multiplayer.core.security.BiometricAuthManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StrictBiometricTest {
    @Test fun cleanupChangesFailClosedWithoutEnrolledBiometrics() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var changed = false
        var error: String? = null
        BiometricAuthManager.authenticate(activity, requireBiometric = true,
            onSuccess = { changed = true }, onError = { error = it })
        assertFalse(changed)
        assertNotNull(error)
    }

    @Test fun existingNonStrictBiometricCallersKeepTheirPreviousBehavior() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var opened = false
        BiometricAuthManager.authenticate(activity, onSuccess = { opened = true }, onError = { fail(it) })
        assertTrue(opened)
    }
}
