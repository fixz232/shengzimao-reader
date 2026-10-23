# 圣子猫阅读 · 手机版 ProGuard 规则

# ---------- 应用自身 ----------
-keep class com.shengzimao.reader.mobile.MainActivity { *; }
-keep class com.shengzimao.reader.mobile.ReaderApp { *; }

# ViewModel 由反射实例化
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }

# ---------- 在线引擎桥 ----------
# 引擎 dex 是预编译的，Kotlin 侧只通过反射按名字调用，必须保留签名。
-keep interface org.shengzimao.reader.AndroidEngine$JsBridge { *; }
-keep class org.shengzimao.reader.** { *; }
-dontwarn org.shengzimao.reader.**

# Rhino
-dontwarn org.mozilla.javascript.**
-keep class org.mozilla.javascript.** { *; }

# OkHttp / okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-keepclasseswithmembers class * { public static okhttp3.internal.platform.Platform buildIfSupported(); }

# 组织 JSON
-dontwarn org.json.**
-keep class org.json.** { *; }

# 数据类（JSON 序列化按字段名写入/读取）
-keep class com.shengzimao.reader.mobile.data.** { *; }
