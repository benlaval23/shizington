# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Tink (security-crypto)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Sport feed is saved to disk with Gson, which reads these classes' fields by reflection.
-keep class com.shizington.tv.data.sports.** { <fields>; }
-keep class com.shizington.tv.data.model.Stream { <fields>; }
-keep class com.shizington.tv.data.model.ChannelGroup { <fields>; }
-keep class com.shizington.tv.data.model.EpgEntry { <fields>; }
-keep enum com.shizington.tv.data.model.ContentType { *; }
-keep enum com.shizington.tv.data.sports.SportTopic { *; }
