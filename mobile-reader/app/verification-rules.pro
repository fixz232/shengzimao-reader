# Instrumentation lives in another APK. App shrinking cannot see its calls to
# Kotlin test helpers; retain those helpers only in the independent QA variant.
# The production release keeps its existing R8 rules.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.BuildersKt { *; }
-keep class kotlinx.coroutines.BuildersKt__BuildersKt { *; }
