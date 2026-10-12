package com.schoolhelp.app.data;
import android.content.Context;
import androidx.room.*;
@Database(entities={StoredRecord.class}, version=1, exportSchema=false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract RecordDao records();
    private static volatile AppDatabase instance;
    public static synchronized AppDatabase get(Context context) {
        if (instance == null) instance = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "schoolhelp.db")
            .allowMainThreadQueries().build();
        return instance;
    }
}
