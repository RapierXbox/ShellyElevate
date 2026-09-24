# minify is off in build.gradle.kts so these only matter once it gets turned on

# the webview calls the bridge by name so its methods must survive shrinking
-keepclassmembers class me.rapierxbox.shellyelevatev2.ShellyElevateJavascriptInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# jni binds native methods by name
-keepclasseswithmembernames class * {
    native <methods>;
}

# native code looks up the key callback by name
-keepclassmembers class * implements me.rapierxbox.shellyelevatev2.helper.InputMonitor$KeyCallback {
    void onHardwareKey(int, int, int);
}

# readable stack traces in the crash log
-keepattributes SourceFile,LineNumberTable
