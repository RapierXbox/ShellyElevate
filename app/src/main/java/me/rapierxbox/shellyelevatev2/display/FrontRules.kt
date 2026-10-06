package me.rapierxbox.shellyelevatev2.display

// what is in front as far as our own process can tell without privileged help
enum class Front {
    // the display host activity is resumed
    HOST,

    // settings the switcher or a screensaver activity of ours is resumed
    OWN_UI,

    // none of ours is resumed on a lit and unlocked screen so another app is in front
    EXTERNAL,

    // the screen is asleep or locked so nothing is resumed and nothing can be told
    UNKNOWN;

    companion object {
        fun of(resumedClass: String?, hostClass: String, screenUsable: Boolean): Front = when {
            resumedClass == hostClass -> HOST
            resumedClass != null -> OWN_UI
            screenUsable -> EXTERNAL
            else -> UNKNOWN
        }
    }
}

// what the end of a screensaver does about the display module. kept free of android so it can be tested
object ScreenSaverReturn {
    enum class Action { DONE, WAIT, BRING_BACK }

    // a woken panel resumes the activity in front well within this and activity switches leave short gaps too
    const val SETTLE_MS = 500L

    // our own screen was in front when the saver started so nothing else should be there now
    // and a slow resume after wake is waited for longer before anything is moved
    const val OWN_SETTLE_MS = 3_000L

    // a screen that stays asleep or locked this long is left alone
    const val MAX_WAIT_MS = 10_000L

    // resumedClass is our resumed activity now and startClass the one when the saver started
    // externalForMs is how long nothing of ours has been resumed on a usable screen
    // moduleInFront is only asked for an external front and answers null when nothing can tell
    fun decide(
        resumedClass: String?,
        startClass: String?,
        hostClass: String,
        saverClasses: Set<String>,
        switcherClass: String,
        screenUsable: Boolean,
        externalForMs: Long,
        waitedMs: Long,
        keepsInFront: Boolean,
        moduleInFront: () -> Boolean?,
    ): Action {
        if (!keepsInFront) return Action.DONE
        val action = when (Front.of(resumedClass, hostClass, screenUsable)) {
            // a visible host shows the module itself
            Front.HOST -> Action.DONE
            Front.OWN_UI -> when (resumedClass) {
                // the saver activity is still finishing
                in saverClasses -> Action.WAIT
                // settings the user was in before the saver or a switcher opened after wake stay
                startClass, switcherClass -> Action.DONE
                // a clock saver brought our task back with settings the user had left for another app
                else -> Action.BRING_BACK
            }
            Front.UNKNOWN -> Action.WAIT
            Front.EXTERNAL -> {
                val settle = if (startClass != null) OWN_SETTLE_MS else SETTLE_MS
                when {
                    externalForMs < settle -> Action.WAIT
                    // an unknown front is brought back anyway since relaunching a module in front is harmless
                    moduleInFront() == true -> Action.DONE
                    else -> Action.BRING_BACK
                }
            }
        }
        return if (action == Action.WAIT && waitedMs >= MAX_WAIT_MS) Action.DONE else action
    }
}
