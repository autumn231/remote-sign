# 图书馆随签 R8 规则（v1.7 起开启代码压缩 + 资源压缩）
# OkHttp 的可选平台依赖（Conscrypt/BouncyCastle/OpenJSSE 等）不在包内，忽略其引用警告
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn android.util.Log
