# TrustedRouter response and OAuth wire models are decoded reflectively by Gson.
# Keep their field names and members in minified Android release builds.
-keepclassmembers,allowoptimization class com.trustedrouter.models.** {
    <fields>;
}
-keepclassmembers,allowoptimization class com.trustedrouter.oauth.OAuthToken {
    <fields>;
}
