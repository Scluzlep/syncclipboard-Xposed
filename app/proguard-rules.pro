# libxposed API 102 & stability-first R8 rules
-dontobfuscate
-dontoptimize
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-dontwarn io.github.libxposed.**
-dontwarn okhttp3.**
-dontwarn okio.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep class com.scluzlep.syncclipboard.** { *; }
-keep class androidx.core.content.FileProvider { *; }
