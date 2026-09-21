# Keep JNA's classes and their native loading machinery.
#
# The generated UniFFI bindings reach the Rust library through JNA's direct
# mapping, which is reflective: R8 cannot see the call graph and will happily
# strip or rename the classes it needs. The failure mode is an
# UnsatisfiedLinkError or a NoSuchMethodError at run time, in a release build
# only, which is an unpleasant way to find out.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn java.awt.**

# The generated bindings themselves. `uniffi.*` is where they live.
-keep class uniffi.** { *; }
