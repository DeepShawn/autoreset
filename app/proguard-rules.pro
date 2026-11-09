# 保留行号信息，便于线上崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp / 协程标准规则由各自库内 AAR 提供；本项目无反射模型类，
# 数据一律用 org.json 手工解析，默认混淆即可。
-dontwarn org.json.**
