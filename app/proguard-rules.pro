# Aturan R8 untuk eraser-remake.

# UniFFI + JNA: binding memanggil fungsi native lewat JNA dan memetakan
# struktur data berdasarkan nama kelas/field; jangan diubah namanya.
-keep class uniffi.** { *; }
-keep class com.sun.jna.** { *; }
# JNA jar umum mereferensikan API AWT desktop yang tidak ada di Android;
# jalur itu tidak pernah dipakai di Android.
-dontwarn java.awt.**
-keepclassmembers class * extends com.sun.jna.Structure {
    public *;
}
-keepclassmembers class * extends com.sun.jna.Union {
    public *;
}

# Room: implementasi DAO dibuat saat kompilasi; entitas dibaca lewat refleksi
# skema oleh runtime Room.
-keep class id.eraserremake.app.data.*Database { *; }
-keep class id.eraserremake.app.data.Project { *; }
-keep class id.eraserremake.app.data.InstalledModel { *; }
-dontwarn androidx.room.paging.**

# WorkManager: worker dibuat lewat refleksi dari nama kelasnya.
-keep class id.eraserremake.app.data.ModelDownloadWorker { *; }

# Enum UniFFI dan record data tidak boleh hilang walau belum semua jalur
# UI memakainya di setiap rilis.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
