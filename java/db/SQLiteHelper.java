
package com.winlator.Download.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class SQLiteHelper extends SQLiteOpenHelper {
    private static final String TAG = "SQLiteHelper";

    // If you change the database schema, you must increment the database version.
    public static final int DATABASE_VERSION = 1;
    public static final String DATABASE_NAME = "WinlatorDownloads.db";

    public SQLiteHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.d(TAG, "Creating database table: " + DownloadContract.SQL_CREATE_ENTRIES);
        db.execSQL(DownloadContract.SQL_CREATE_ENTRIES);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // This database is only a cache for download data, so its upgrade policy is
        // to simply to discard the data and start over
        Log.w(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion + ", which will destroy all old data");
        db.execSQL(DownloadContract.SQL_DELETE_ENTRIES);
        onCreate(db);
        // TODO: Implement a more robust migration strategy if needed in the future.
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onUpgrade(db, oldVersion, newVersion);
    }

    // --- CRUD Operations (to be added or managed elsewhere, e.g., a Repository class) ---
    // It's generally better practice to have a separate class (like a DAO or Repository)
    // handle the actual database interactions (insert, update, query, delete)
    // rather than putting them directly in the SQLiteOpenHelper.

    // Example (can be moved later):
    /*
    public long insertDownload(String url, String fileName, int status) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_URL, url);
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_FILE_NAME, fileName);
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_STATUS, status);
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_TIMESTAMP, System.currentTimeMillis());
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_DOWNLOADED_BYTES, 0);
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_TOTAL_BYTES, -1); // Unknown initially

        long newRowId = db.insert(DownloadContract.DownloadEntry.TABLE_NAME, null, values);
        db.close(); // Close db connection
        return newRowId;
    }

    public int updateDownloadProgress(String url, long downloadedBytes, long totalBytes) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_DOWNLOADED_BYTES, downloadedBytes);
        if (totalBytes > 0) { // Update total bytes if known
             values.put(DownloadContract.DownloadEntry.COLUMN_NAME_TOTAL_BYTES, totalBytes);
        }
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_STATUS, DownloadContract.STATUS_DOWNLOADING);

        String selection = DownloadContract.DownloadEntry.COLUMN_NAME_URL + " = ?";
        String[] selectionArgs = { url };

        int count = db.update(
            DownloadContract.DownloadEntry.TABLE_NAME,
            values,
            selection,
            selectionArgs);
        db.close();
        return count;
    }

     public int updateDownloadStatus(String url, int status, String localPath) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DownloadContract.DownloadEntry.COLUMN_NAME_STATUS, status);
        if (localPath != null) {
             values.put(DownloadContract.DownloadEntry.COLUMN_NAME_LOCAL_PATH, localPath);
        }

        String selection = DownloadContract.DownloadEntry.COLUMN_NAME_URL + " = ?";
        String[] selectionArgs = { url };

        int count = db.update(
            DownloadContract.DownloadEntry.TABLE_NAME,
            values,
            selection,
            selectionArgs);
        db.close();
        return count;
    }
    */
}

