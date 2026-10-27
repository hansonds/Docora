# Docora R8 rules
# Libraries that use JNI / reflection: keep them whole
-keep class org.opencv.** { *; }
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**

# Our enums are saved in settings by name
-keepclassmembers enum com.hanson.scansign.** { *; }

# Readable crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
