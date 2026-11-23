-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.jna.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn org.slf4j.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.google.errorprone.annotations.**
-keep class org.bouncycastle.** { *; }
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keepclassmembers class * { @kotlinx.serialization.Serializable *; }
# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn javax.security.auth.login.LoginContext