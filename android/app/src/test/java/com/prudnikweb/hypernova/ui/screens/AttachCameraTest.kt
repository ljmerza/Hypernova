package com.prudnikweb.hypernova.ui.screens

import android.Manifest
import android.app.Application
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import com.prudnikweb.hypernova.ui.session.AttachPickers
import com.prudnikweb.hypernova.ui.session.rememberAttach
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Taking a photo for an attachment. Android refuses the camera app with a SecurityException to an app
 * that declares the camera permission without holding it, and Scan makes this app declare it: the
 * owner's "trying to take a picture crashes the app" (2026-11-03). So it asks first.
 */
@RunWith(RobolectricTestRunner::class)
class AttachCameraTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val launched = mutableListOf<ActivityResultContract<*, *>>()

    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launched += contract
            }
        }
    }

    private fun pickers(): AttachPickers {
        lateinit var out: AttachPickers
        rule.setContent {
            Harness(spy) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    out = rememberAttach("k", FakeHub()).second
                }
            }
        }
        rule.waitForIdle()
        return out
    }

    private fun camera(allowed: Boolean) {
        val app = shadowOf(RuntimeEnvironment.getApplication() as Application)
        if (allowed) app.grantPermissions(Manifest.permission.CAMERA) else app.denyPermissions(Manifest.permission.CAMERA)
    }

    @Test fun withoutThePermissionItAsksAndDoesntOpenTheCameraApp() {
        camera(allowed = false)
        val p = pickers()
        rule.runOnIdle { p.camera() }
        assertEquals(1, launched.size)
        assertEquals(ActivityResultContracts.RequestPermission::class, launched.single()::class)
    }

    @Test fun withThePermissionItOpensTheCameraApp() {
        camera(allowed = true)
        val p = pickers()
        rule.runOnIdle { p.camera() }
        assertEquals(1, launched.size)
        assertEquals(ActivityResultContracts.TakePicture::class, launched.single()::class)
    }
}
