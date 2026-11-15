package com.rw3.directsync.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.rw3.directsync.R

/**
 * Shared native transition and reduced-motion helper enforcing consistent
 * durations (240ms tab crossfade, 300ms detail enter, 220ms back exit)
 * with smooth deceleration and zero bounce.
 */
object UiTransitions {

    const val TAB_CROSSFADE_MS = 240L
    const val DETAIL_OPEN_MS = 300L
    const val DETAIL_CLOSE_MS = 220L

    private val decelerate = DecelerateInterpolator(1.6f)

    fun areAnimationsEnabled(context: Context): Boolean {
        val scale = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
        } catch (_: Exception) {
            1.0f
        }
        return scale > 0.0f
    }

    fun startDetailActivity(activity: Activity, intent: Intent) {
        activity.startActivity(intent)
        if (areAnimationsEnabled(activity)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                activity.overrideActivityTransition(
                    Activity.OVERRIDE_TRANSITION_OPEN,
                    R.anim.screen_enter_open,
                    R.anim.screen_exit_open
                )
            } else {
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(R.anim.screen_enter_open, R.anim.screen_exit_open)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(0, 0)
            }
        }
    }

    fun finishWithCloseTransition(activity: Activity) {
        activity.finish()
        applyCloseTransition(activity)
    }

    fun applyCloseTransition(activity: Activity) {
        if (areAnimationsEnabled(activity)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                activity.overrideActivityTransition(
                    Activity.OVERRIDE_TRANSITION_CLOSE,
                    R.anim.screen_enter_close,
                    R.anim.screen_exit_close
                )
            } else {
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(R.anim.screen_enter_close, R.anim.screen_exit_close)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(0, 0)
            }
        }
    }

    fun registerCloseTransitionIfSupported(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (areAnimationsEnabled(activity)) {
                activity.overrideActivityTransition(
                    Activity.OVERRIDE_TRANSITION_CLOSE,
                    R.anim.screen_enter_close,
                    R.anim.screen_exit_close
                )
            } else {
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            }
        }
    }

    fun crossfadeTo(target: View, others: List<View>) {
        others.forEach { view ->
            view.animate().cancel()
            if (view !== target && view.visibility == View.VISIBLE) {
                view.visibility = View.GONE
                view.alpha = 1f
            }
        }
        target.animate().cancel()
        if (!areAnimationsEnabled(target.context)) {
            target.alpha = 1f
            target.visibility = View.VISIBLE
            return
        }
        target.alpha = 0f
        target.visibility = View.VISIBLE
        target.animate()
            .alpha(1f)
            .setDuration(TAB_CROSSFADE_MS)
            .setInterpolator(decelerate)
            .start()
    }
}

