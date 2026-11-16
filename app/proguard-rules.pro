# Reglas base para cuando se active R8 (ver AGENT.md, decisión sobre release).
-keepattributes *Annotation*, InnerClasses, Signature
-keep class org.mozilla.javascript.** { *; }
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn org.slf4j.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class app.ondara.** { kotlinx.serialization.KSerializer serializer(...); }
