package com.schoolhelp.app.data;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName="records")
public class StoredRecord {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String kind = "";
    @NonNull public String payload = "{}";
    public long sequence;
    public StoredRecord() {}
    @androidx.room.Ignore
    public StoredRecord(String id, String kind, String payload, long sequence) {
        this.id=id; this.kind=kind; this.payload=payload; this.sequence=sequence;
    }
}
