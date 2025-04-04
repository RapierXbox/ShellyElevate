package me.rapierxbox.shellyelevatev2.screensavers

object ScreenSaverManagerHolder {
    private var instance: ScreenSaverManager? = null

    @JvmStatic
    fun initialize(): ScreenSaverManager {
        if (instance == null) {
            instance = ScreenSaverManager()
        }
        return instance!!
    }

    //Currently the two methods are identical, but
    //in the future they may be different. For example
    //init is probably going to have a context as parameter
    @JvmStatic
    fun getInstance(): ScreenSaverManager {
        if (instance == null) {
            instance = ScreenSaverManager()
        }
        return instance!!
    }
}