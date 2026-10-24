# 首版不混淆；接入 minify 后保留 Compose 与反射入口
-keepattributes *Annotation*
-keep class com.vvfile.app.** { *; }
