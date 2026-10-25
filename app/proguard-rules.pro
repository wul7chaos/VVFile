# 首版不混淆；接入 minify 后保留 Compose 与反射入口
-keepattributes *Annotation*
-keep class com.vvfile.app.** { *; }

# ---------------------------------------------------------------- 终端 PTY
# 原生方法按 Java_com_termux_terminal_JNI_xxx 显式命名绑定，
# 类名/方法名一旦被 R8 改写就找不到符号，必须整体保留。
-keep class com.termux.terminal.JNI { *; }
-keepclasseswithmembernames class com.termux.terminal.JNI {
    native <methods>;
}

# TerminalView 通过反射拿 FileDescriptor 的私有 descriptor 字段
-keepclassmembers class java.io.FileDescriptor {
    private int descriptor;
    private int fd;
}
