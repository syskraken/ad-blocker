# R8 keeps manifest-declared components automatically, but this app has exactly
# two entry points and both are reached only by the framework — being explicit
# costs nothing and removes a whole class of "works in debug, not in release".
-keep class dev.franklin.adblocker.AdVpnService { *; }
-keep class dev.franklin.adblocker.MainActivity { *; }

# Line numbers make a release-build crash report readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
