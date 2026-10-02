package za.co.cttrains.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "stations")
data class StationEntity(@PrimaryKey val name: String, val latitude: Double?, val longitude: Double?)
@Entity(tableName = "local_values")
data class LocalValue(@PrimaryKey val key: String, val encryptedValue: String)
@Entity(tableName = "schedule_cache")
data class CacheEntity(@PrimaryKey val key: String, val fetchedAt: Long, val encryptedValue: String)
@Entity(tableName = "scheduled_journeys", indices = [Index("departureAt"), Index(value = ["identity"], unique = true)])
data class JourneyEntity(@PrimaryKey val id: String, val identity: String, val departureAt: Long, val encryptedValue: String)

@Dao
interface TransitDao {
    @Query("SELECT * FROM stations ORDER BY name") fun stations(): Flow<List<StationEntity>>
    @Upsert suspend fun stations(rows: List<StationEntity>)
    @Query("SELECT * FROM local_values WHERE `key` = :key") suspend fun value(key: String): LocalValue?
    @Query("SELECT * FROM local_values WHERE `key` = :key") fun observeValue(key: String): Flow<LocalValue?>
    @Upsert suspend fun value(row: LocalValue)
    @Query("SELECT * FROM local_values") fun observeValues(): Flow<List<LocalValue>>
    @Query("SELECT * FROM local_values") suspend fun allValues(): List<LocalValue>
    @Query("SELECT * FROM scheduled_journeys ORDER BY departureAt") suspend fun allJourneys(): List<JourneyEntity>
    @Query("SELECT * FROM schedule_cache ORDER BY fetchedAt DESC") suspend fun caches(): List<CacheEntity>
    @Query("SELECT * FROM schedule_cache ORDER BY fetchedAt DESC") fun observeCaches(): Flow<List<CacheEntity>>
    @Query("SELECT * FROM schedule_cache WHERE `key` = :key") suspend fun cache(key: String): CacheEntity?
    @Upsert suspend fun cache(row: CacheEntity)
    @Query("DELETE FROM schedule_cache WHERE fetchedAt < :cutoff OR fetchedAt > :now OR `key` NOT IN (SELECT `key` FROM schedule_cache ORDER BY fetchedAt DESC LIMIT 12)") suspend fun pruneCache(cutoff: Long, now: Long)
    @Query("DELETE FROM schedule_cache") suspend fun clearCache()
    @Query("SELECT * FROM scheduled_journeys ORDER BY departureAt") fun journeys(): Flow<List<JourneyEntity>>
    @Query("SELECT * FROM scheduled_journeys WHERE id = :id") suspend fun journey(id: String): JourneyEntity?
    @Query("SELECT * FROM scheduled_journeys WHERE identity = :identity") suspend fun byIdentity(identity: String): JourneyEntity?
    @Upsert suspend fun journey(row: JourneyEntity)
    @Query("DELETE FROM local_values WHERE key = :key") suspend fun deleteValue(key: String)
    @Query("DELETE FROM scheduled_journeys WHERE id = :id") suspend fun deleteJourney(id: String)
    @Query("DELETE FROM scheduled_journeys WHERE departureAt < :cutoff") suspend fun pruneJourneys(cutoff: Long)
    @Query("DELETE FROM scheduled_journeys") suspend fun clearJourneys()
    @Query("DELETE FROM local_values") suspend fun clearValues()
}

@Database(entities = [StationEntity::class, LocalValue::class, CacheEntity::class, JourneyEntity::class], version = 1, exportSchema = true)
abstract class TransitDatabase : RoomDatabase() {
    abstract fun dao(): TransitDao
    companion object {
        @Volatile private var instance: TransitDatabase? = null
        fun get(context: Context): TransitDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TransitDatabase::class.java, "transit.db")
                .build().also { instance = it }
        }
    }
}
