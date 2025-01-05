-dontwarn **

-keep class io.netty.** { *; }
-dontwarn io.netty.**

-keep class kotlin.reflect.jvm.internal.** { *; }
-keep class kotlin.jvm.internal.** { *; }
-keep class kotlinx.coroutines.internal.** { *; }

-dontwarn androidx.compose.desktop.DesktopTheme_jvmKt
-dontwarn androidx.compose.desktop.DesktopTheme_jvmKt$DesktopMaterialTheme$2
-dontwarn org.graalvm.nativeimage.Platform$MACOS
