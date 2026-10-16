# The app calls a few hidden android.bluetooth methods by name through reflection
# (HiddenBluetooth.kt). They live in the framework, not in the APK, so R8 never renames them;
# nothing in the app itself needs to be kept.

# Keep line numbers so stack traces in bug reports are useful.
-keepattributes SourceFile,LineNumberTable
