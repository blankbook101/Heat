# Heat uses no reflection, except the JavaScript bridge used by Heat Web.
-keepclassmembers class com.heat.app.WebHeatActivity$HeatBridge {
    @android.webkit.JavascriptInterface <methods>;
}
