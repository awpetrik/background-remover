package id.eraserremake.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Satu baris per proyek. Skema sesuai design doc bagian 7.5. */
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val originalPath: String,
    val workPath: String?,
    val thumbPath: String,
    val width: Int,
    val height: Int,
)

/** Satu baris per model AI terpasang (design doc 7.5). File-nya di filesDir/models/. */
@Entity(tableName = "installed_models")
data class InstalledModel(
    @PrimaryKey val id: String,
    val name: String,
    /** Nama enum ModelKind di Rust: SalientMask, InstanceSeg, PointSam, Selfie. */
    val kind: String,
    val version: String,
    val sizeBytes: Long,
    val inputWidth: Int,
    val inputHeight: Int,
    /** Nama file relatif terhadap filesDir/models/. */
    val fileName: String,
    val bundled: Boolean,
    val isDefault: Boolean,
    val sha256: String,
)

@Dao
interface ModelDao {
    @Query("SELECT * FROM installed_models ORDER BY name ASC")
    fun observeAll(): Flow<List<InstalledModel>>

    @Query("SELECT * FROM installed_models WHERE kind = :kind ORDER BY name ASC")
    fun observeByKind(kind: String): Flow<List<InstalledModel>>

    @Query("SELECT * FROM installed_models WHERE id = :id")
    suspend fun getById(id: String): InstalledModel?

    @Query("SELECT * FROM installed_models WHERE kind = :kind")
    suspend fun allByKind(kind: String): List<InstalledModel>

    @Query("SELECT * FROM installed_models")
    suspend fun all(): List<InstalledModel>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(model: InstalledModel)

    @Query("DELETE FROM installed_models WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): Project?

    @Insert
    suspend fun insert(project: Project): Long

    @Update
    suspend fun update(project: Project)

    @Delete
    suspend fun delete(project: Project)
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS installed_models (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                kind TEXT NOT NULL,
                version TEXT NOT NULL,
                sizeBytes INTEGER NOT NULL,
                inputWidth INTEGER NOT NULL,
                inputHeight INTEGER NOT NULL,
                fileName TEXT NOT NULL,
                bundled INTEGER NOT NULL,
                isDefault INTEGER NOT NULL,
                sha256 TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }
}

@Database(entities = [Project::class, InstalledModel::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun modelDao(): ModelDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDb::class.java,
                "eraser.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
