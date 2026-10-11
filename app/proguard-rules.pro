# Room: the database implementation is created reflectively by class name.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep class com.cashflow.app.data.** { *; }

# Enums are looked up by name (Room converters, TxKind.valueOf(...) from saved UI state).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# The update status receiver and FileProvider are declared in the manifest (kept by AAPT2 rules);
# keep the receiver's name stable for the PackageInstaller PendingIntent.
-keep class com.cashflow.app.update.UpdateStatusReceiver { *; }
-keep class androidx.core.content.FileProvider { *; }

# Version info is parsed with regexes (no org.json / reflection), so no model keeps are needed there.

# Readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
