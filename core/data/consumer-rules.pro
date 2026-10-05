# GTFS-Realtime bindings use full protobuf-java reflection for field access.
-keep class com.google.transit.realtime.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessage { *; }
-dontwarn com.google.protobuf.**
