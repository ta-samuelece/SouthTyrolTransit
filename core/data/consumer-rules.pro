# GTFS-Realtime bindings use full protobuf-java reflection: accessors are looked up by name on the
# message, builder and enum classes. Members only, so message types the app never reaches are removed.
-keepclassmembers class com.google.transit.realtime.** { <fields>; <methods>; }
-dontwarn com.google.protobuf.**
