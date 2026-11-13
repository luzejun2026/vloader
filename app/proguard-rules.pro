# 业务代码无反射调用，R8 可安全混淆。
# 保留 Activity 构造器供清单实例化（AGP 默认规则通常已覆盖，这里显式声明更稳妥）。
-keep class com.vloader.app.MainActivity { public <init>(); }
-dontwarn javax.crypto.**
