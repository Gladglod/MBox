package com.github.tvbox.osc.cache;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * 离线缓存任务 DAO。
 *
 * @author MBox
 */
@Dao
public interface DownloadRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(DownloadRecord record);

    @Update
    int update(DownloadRecord record);

    @Delete
    int delete(DownloadRecord record);

    @Query("select * from downloadRecord order by createTime desc, id desc")
    List<DownloadRecord> getAll();

    @Query("select * from downloadRecord where `id`=:id")
    DownloadRecord get(int id);

    @Query("select * from downloadRecord where `sourceKey`=:sourceKey and `vodId`=:vodId and `playFlag`=:playFlag and `playIndex`=:playIndex limit 1")
    DownloadRecord get(String sourceKey, String vodId, String playFlag, int playIndex);

    @Query("select count(*) from downloadRecord where `state`=:state")
    int getCountByState(int state);

    @Query("DELETE FROM downloadRecord")
    void deleteAll();
}
