# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.homebudget.**$$serializer { *; }
-keepclassmembers class com.homebudget.** { *** Companion; }
-keepclasseswithmembers class com.homebudget.** { kotlinx.serialization.KSerializer serializer(...); }
# Credential Manager
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
