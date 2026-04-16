-keep class org.jivesoftware.smack.** { *; }
-keep class org.jivesoftware.smackx.** { *; }
-keep class org.jxmpp.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# jsoup 1.19+ optionally delegates to the google/re2j regex engine when it's
# on the classpath. We don't ship re2j — jsoup's built-in java.util.regex
# path handles everything we need for OpenGraph parsing — so tell R8 those
# references are genuinely optional.
-dontwarn com.google.re2j.**
