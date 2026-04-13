# LiteRT / MediaPipe
-keep class com.google.mediapipe.** { *; }
-keep class com.google.ai.edge.litert.** { *; }

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# ML Kit
-keep class com.google.mlkit.** { *; }
