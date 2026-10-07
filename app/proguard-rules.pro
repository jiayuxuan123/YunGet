# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# 迅雷应用内验证 WebView：保留所有 @JavascriptInterface 方法（防止 release 混淆后页面调不到桥）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepnames class com.yunget.app.ui.login.XunleiVerifyWebViewScreen*
-keepnames class com.yunget.app.ui.login.XunleiLoginScreen*

# 【当前 release 未开 R8（isMinifyEnabled=false），这些规则现在是空转的】
# 保留它们是给将来开启 R8 时兜底：Room 靠反射读字段名，实体字段名即列名，
# 一旦被重命名就会出现「表存在但字段找不到」的运行时崩溃——而那种崩只在 release 版出现，
# 极难定位。上游 v1.2.9 开启 R8 时踩过，这里预先对齐。
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}
-keepclassmembers @androidx.room.Entity class * {
    <fields>;
}

# Gopeed（gomobile 桥接）：libgojni.so 内部用 FindClass 按「类名 + 方法名」反查这些类
# （go/Seq、go/Universe$proxyerror、com/gopeed/libgopeed/*），R8 改名或裁剪会让 .so
# dlopen 之后找不到类而崩溃。规则与 AAR 自带 proguard.txt 一致。
-keep class go.** { *; }
-keep class com.gopeed.** { *; }

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile