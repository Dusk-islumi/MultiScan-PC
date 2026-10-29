package com.example.multiscan.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本地数据库（v2，无迁移；`exportSchema = false`）。
 *
 * v2：`scan_items` 唯一索引由 `(code)` 改为 `(session_id, code)`，允许跨批次重复扫码；
 * 变种版尚未正式发布，升级时直接重建空库（旧调试安装的记录会被清空）。
 *
 * 单例由 [getInstance] 提供，AppContainer 持有同一实例；其余模块只依赖 DAO 接口。
 */
@Database(
    entities = [SessionEntity::class, ScanItemEntity::class, OutboxEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    abstract fun scanItemDao(): ScanItemDao

    abstract fun outboxDao(): OutboxDao

    companion object {
        private const val DB_NAME = "multiscan.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
