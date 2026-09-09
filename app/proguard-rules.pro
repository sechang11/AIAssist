# Jackson (pulled in by anthropic-java) is reflection-heavy.
-keep class com.anthropic.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-dontwarn com.fasterxml.jackson.**
-dontwarn org.slf4j.**
-dontwarn java.beans.**

# kotlinx.serialization keeps its own generated serializers.
-keepclassmembers class com.aitextassistant.** {
    *** Companion;
}
-keepclasseswithmembers class com.aitextassistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}
