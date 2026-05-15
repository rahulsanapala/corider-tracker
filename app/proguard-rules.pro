# Keep osmdroid classes referenced reflectively.
-keep class org.osmdroid.** { *; }

# Keep Firebase model access used by Realtime Database snapshots.
-keepattributes Signature
-keepclassmembers class * {
    @com.google.firebase.database.PropertyName <fields>;
    @com.google.firebase.database.PropertyName <methods>;
}
