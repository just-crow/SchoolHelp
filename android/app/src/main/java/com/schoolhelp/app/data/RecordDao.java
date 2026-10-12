package com.schoolhelp.app.data;
import androidx.room.*;
import java.util.List;
@Dao public interface RecordDao {
    @Query("SELECT * FROM records WHERE id = :id") StoredRecord get(String id);
    @Query("SELECT * FROM records WHERE kind = :kind ORDER BY sequence ASC, id ASC") List<StoredRecord> list(String kind);
    @Insert(onConflict=OnConflictStrategy.REPLACE) void put(StoredRecord record);
    @Query("DELETE FROM records WHERE id = :id") void delete(String id);
    @Query("DELETE FROM records WHERE kind = :kind") void clear(String kind);
}
