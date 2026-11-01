# Compose / Kotlin 常用保留规则由 AGP 自带规则处理；
# 这里只保留会被反射访问的入口与序列化结构。
-keep class com.shrinkcam.app.MainViewModel { *; }
-keep class com.shrinkcam.app.VideoInfo { *; }
-keep class com.shrinkcam.app.CompressPlan { *; }
-keep class com.shrinkcam.app.Quality { *; }
-dontwarn org.jetbrains.annotations.**
