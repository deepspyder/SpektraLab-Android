# 保持 JNI 桥接类的 native 方法名不被混淆。
# external fun 默认不会被混淆掉符号，但为安全起见显式保留包路径。
-keep class com.spektralab.android.engine.** { *; }
