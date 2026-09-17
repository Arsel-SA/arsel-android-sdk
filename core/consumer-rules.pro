# Keep the public SDK surface + manifest-referenced components (referenced reflectively / by manifest).
-keep class sa.arsel.core.Arsel { *; }
-keep class sa.arsel.core.Arsel$* { *; }
-keep class sa.arsel.core.ArselConfig { *; }
-keep class sa.arsel.core.ArselConfig$Builder { *; }
-keep class sa.arsel.core.notification.NotificationTapActivity { *; }
-keep class sa.arsel.core.notification.NotificationPermission { *; }
-keep enum sa.arsel.core.model.** { *; }
# WorkManager instantiates the worker reflectively.
-keep class sa.arsel.core.net.PushSyncWorker { *; }
# WorkManager 2.9.x and its Room 2.5 keep these classes but not their no-arg constructors, which R8
# full mode (AGP 9) then strips: every worker fails to start, or the app crashes at launch.
-keepclassmembers class * extends androidx.work.InputMerger { void <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { void <init>(); }
