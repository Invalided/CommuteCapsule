# R8 keep rules for the release build type.
#
# Manifest components (activity, services, receivers, the backup agent and the
# FileProvider) are kept by the rules AAPT2 generates from AndroidManifest.xml.
# AndroidX, Compose and kotlinx-coroutines ship their own consumer rules.
# org.json is part of the Android framework and is not processed by R8; all
# JSON is read and written with explicit string keys, so field and property
# names do not need to be preserved.

# Optional extension entry point (docs/DATA_SOURCE.md), loaded by class name
# via reflection: Transit loads com.busnotice.commute.extra.Extras and reads its
# INSTANCE field and getSources() method. The rule has no effect when the
# optional src/extra directory is absent.
-keep class com.busnotice.commute.extra.** { *; }

# Source interfaces implemented by extension classes.
-keep interface com.busnotice.commute.StaticSource { *; }
-keep interface com.busnotice.commute.RealtimeSource { *; }

# ZXing core is used for encoding and decoding recovery-code QR images only;
# it uses no reflection and needs no keep rules.

# Keep source file names and line numbers so that stack traces in exported
# diagnostics can be retraced with mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
