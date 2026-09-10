# Jackson (pulled in by anthropic-java) is reflection-heavy.
-keep class com.anthropic.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-dontwarn com.fasterxml.jackson.**
-dontwarn org.slf4j.**
-dontwarn java.beans.**

# anthropic-java bundles victools' JSON schema generator, which derives schemas
# from Java types using java.lang.reflect.AnnotatedType. Android has no such
# class, at any API level, so R8 fails the release build without these.
#
# Suppressing is safe only because nothing here touches that code path: this app
# calls messages().create() with a plain system and user message. If you ever
# reach for the SDK's schema-derived structured outputs, the class-based
# outputConfig(SomeClass.class) overload, it will not merely warn, it will throw
# at runtime on a phone. Send the schema as JSON, or keep parsing the text.
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn com.github.victools.**

# kotlinx.serialization keeps its own generated serializers.
-keepclassmembers class com.aitextassistant.** {
    *** Companion;
}
-keepclasseswithmembers class com.aitextassistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}
