# Benchmark build type ProGuard rules
# Keep all rules from release, plus ensure benchmark classes aren't stripped.
-keep class com.callx.benchmark.** { *; }
-dontwarn com.callx.benchmark.**
