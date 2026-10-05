# GTFS-Realtime bindings rely on protobuf reflection.
-keep class com.google.transit.realtime.** { *; }
-dontwarn com.google.protobuf.**
# MapLibre uses JNI callbacks into these packages.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
