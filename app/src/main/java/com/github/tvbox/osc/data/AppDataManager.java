package com.github.tvbox.osc.data;

import android.annotation.SuppressLint;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;

import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.FileUtils;

import java.io.File;
import java.io.IOException;


/**
 * 类描述:
 *
 * @author pj567
 * @since 2020/5/15
 */
public class AppDataManager {
    private static final int DB_FILE_VERSION = 3;
    private static final String DB_NAME = "tvbox";
    private static AppDataManager manager;
    private static AppDataBase dbInstance;

    private AppDataManager() {
    }

    public static void init() {
        if (manager == null) {
            synchronized (AppDataManager.class) {
                if (manager == null) {
                    manager = new AppDataManager();
                }
            }
        }
    }

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE sourceState ADD COLUMN tidSort TEXT");
            } catch (SQLiteException e) {
                e.printStackTrace();
            }
        }
    };

    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @SuppressLint("Range")
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `vodRecordTmp` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `vodId` TEXT, `updateTime` INTEGER NOT NULL, `sourceKey` TEXT, `data` BLOB, `dataJson` TEXT, `testMigration` INTEGER NOT NULL)");

            // Read every thing from the former Expense table
            Cursor cursor = database.query("SELECT * FROM vodRecord");

            int id;
            int vodId;
            long updateTime;
            String sourceKey;
            String dataJson;

            while (cursor.moveToNext()) {
                id = cursor.getInt(cursor.getColumnIndex("id"));
                vodId = cursor.getInt(cursor.getColumnIndex("vodId"));
                updateTime = cursor.getLong(cursor.getColumnIndex("updateTime"));
                sourceKey = cursor.getString(cursor.getColumnIndex("sourceKey"));
                dataJson = cursor.getString(cursor.getColumnIndex("dataJson"));
                database.execSQL("INSERT INTO vodRecordTmp (id, vodId, updateTime, sourceKey, dataJson, testMigration) VALUES" +
                        " ('" + id + "', '" + vodId + "', '" + updateTime + "', '" + sourceKey + "', '" + dataJson + "',0  )");
            }


            // Delete the former table
            database.execSQL("DROP TABLE vodRecord");
            // Rename the current table to the former table name so that all other code continues to work
            database.execSQL("ALTER TABLE vodRecordTmp RENAME TO vodRecord");
        }
    };

    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE vodRecord ADD COLUMN dataJson TEXT");
            } catch (SQLiteException e) {
                e.printStackTrace();
            }
        }
    };

    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            try {
                database.execSQL("ALTER TABLE localSource ADD COLUMN type INTEGER NOT NULL DEFAULT 0");
            } catch (SQLiteException e) {
                e.printStackTrace();
            }
        }
    };

    static String dbPath() {
        return DB_NAME + ".v" + DB_FILE_VERSION + ".db";
    }

    public static AppDataBase get() {
        if (manager == null) {
            throw new RuntimeException("AppDataManager is no init");
        }
        if (dbInstance == null)
            dbInstance = Room.databaseBuilder(App.getInstance(), AppDataBase.class, dbPath())
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    //.addMigrations(MIGRATION_1_2)
                    //.addMigrations(MIGRATION_2_3)
                    //.addMigrations(MIGRATION_3_4)
                    //.addMigrations(MIGRATION_4_5)
                    .addCallback(new RoomDatabase.Callback() {
                        @Override
                        public void onCreate(@NonNull SupportSQLiteDatabase db) {
                            super.onCreate(db);
//                        LOG.i("数据库第一次创建成功");
                        }

                        @Override
                        public void onOpen(@NonNull SupportSQLiteDatabase db) {
                            super.onOpen(db);
//                        LOG.i("数据库打开成功");
                        }
                    }).allowMainThreadQueries()//可以在主线程操作
                    .build();
        return dbInstance;
    }

    public static boolean backup(File path) throws IOException {
        closeInstance();
        File db = App.getInstance().getDatabasePath(dbPath());
        if (!db.exists() || db.length() <= 0) {
            return false;
        }
        FileUtils.copyFile(db, path);
        // rollback journal / wal 一般为空或无关紧要，但为避免极端情况把未落盘页落进备份，
        // 顺带拷一份备份（恢复时不还原，只清本地旧文件）
        try {
            File journal = new File(db.getAbsolutePath() + "-journal");
            if (journal.exists() && journal.length() > 0) {
                FileUtils.copyFile(journal, new File(path.getParentFile(), path.getName() + "-journal"));
            }
            File wal = new File(db.getAbsolutePath() + "-wal");
            if (wal.exists() && wal.length() > 0) {
                FileUtils.copyFile(wal, new File(path.getParentFile(), path.getName() + "-wal"));
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return true;
    }

    public static boolean restore(File path) throws IOException {
        closeInstance();
        File db = App.getInstance().getDatabasePath(dbPath());
        if (db.exists()) {
            db.delete();
        }
        // 清掉本机上可能残留的旧 journal / wal：它们对应的是被替换前的库，
        // 打开新文件时若仍存在，可能触发回滚破坏刚拷进去的数据
        File journal = new File(db.getAbsolutePath() + "-journal");
        if (journal.exists()) journal.delete();
        File wal = new File(db.getAbsolutePath() + "-wal");
        if (wal.exists()) wal.delete();
        File shm = new File(db.getAbsolutePath() + "-shm");
        if (shm.exists()) shm.delete();
        if (!db.getParentFile().exists())
            db.getParentFile().mkdirs();
        FileUtils.copyFile(path, db);
        if (db.length() <= 0) {
            return false;
        }
        // dbInstance 已置空，下一次 get() 会重新打开恢复后的文件
        return true;
    }

    /**
     * 关闭数据库连接并将实例引用置空：
     * 备份 / 恢复拷贝文件期间必须独占文件，且关闭后必须清理引用，
     * 否则 {@link #get()} 拿到的仍是已 关闭 的旧实例，
     * 之后的播放进度 / 历史记录 / 收藏写入会全部失效——表现为"备份把数据弄丢"。
     */
    private static void closeInstance() {
        try {
            if (dbInstance != null && dbInstance.isOpen()) {
                dbInstance.close();
            }
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            dbInstance = null;
        }
    }
}
