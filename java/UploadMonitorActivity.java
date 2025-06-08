package com.winlator.Download;

import com.google.android.material.color.DynamicColors;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.Download.adapter.UploadMonitorAdapter;
import com.winlator.Download.model.UploadStatus;

import java.util.ArrayList;
import java.util.List;

public class UploadMonitorActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private UploadMonitorAdapter adapter;
    private List<UploadStatus> uploadsList;
    private BroadcastReceiver uploadReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivityIfAvailable(this);
        setContentView(R.layout.activity_upload_monitor);

        // Configurar action bar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Monitor de Uploads");
        }

        initViews();
        setupBroadcastReceiver();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recycler_view_uploads);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        uploadsList = new ArrayList<>();
        adapter = new UploadMonitorAdapter(uploadsList);
        recyclerView.setAdapter(adapter);
    }

    private void setupBroadcastReceiver() {
        uploadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    switch (action) {
                        case "UPLOAD_STARTED":
                            handleUploadStarted(intent);
                            break;
                        case "UPLOAD_PROGRESS":
                            handleUploadProgress(intent);
                            break;
                        case "UPLOAD_COMPLETED":
                            handleUploadCompleted(intent);
                            break;
                        case "UPLOAD_ERROR":
                            handleUploadError(intent);
                            break;
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("UPLOAD_STARTED");
        filter.addAction("UPLOAD_PROGRESS");
        filter.addAction("UPLOAD_COMPLETED");
        filter.addAction("UPLOAD_ERROR");
        registerReceiver(uploadReceiver, filter);
    }

    private void handleUploadStarted(Intent intent) {
        String gameName = intent.getStringExtra("game_name");
        String fileName = intent.getStringExtra("file_name");
        long fileSize = intent.getLongExtra("file_size", 0);

        UploadStatus upload = new UploadStatus(gameName, fileName, fileSize);
        upload.setStatus(UploadStatus.Status.UPLOADING);
        upload.setProgress(0);

        uploadsList.add(0, upload);
        Log.d("UploadMonitorActivity", "Upload STARTED: game=" + gameName + ", file=" + fileName + ", size=" + fileSize + ". List size: " + uploadsList.size());
        adapter.notifyItemInserted(0);
        recyclerView.scrollToPosition(0);
    }

    private void handleUploadProgress(Intent intent) {
        String gameName = intent.getStringExtra("game_name");
        int progress = intent.getIntExtra("progress", 0);
        Log.d("UploadMonitorActivity", "Handling UPLOAD_PROGRESS: game=" + gameName + ", progress=" + progress);
        boolean found = false;

        for (int i = 0; i < uploadsList.size(); i++) {
            UploadStatus upload = uploadsList.get(i);
            if (upload.getGameName().equals(gameName) && upload.getStatus() == UploadStatus.Status.UPLOADING) {
                Log.d("UploadMonitorActivity", "Found matching upload for: " + gameName + ". Old progress: " + uploadsList.get(i).getProgress() + ", New progress: " + progress + ". Notifying item changed at index " + i);
                upload.setProgress(progress);
                adapter.notifyDataSetChanged();
                found = true;
                break;
            }
        }
        if (!found) {
            Log.w("UploadMonitorActivity", "No matching UPLOADING item found for game: " + gameName + " in handleUploadProgress. List size: " + uploadsList.size());
        }
    }

    private void handleUploadCompleted(Intent intent) {
        String gameName = intent.getStringExtra("game_name");

        for (int i = 0; i < uploadsList.size(); i++) {
            UploadStatus upload = uploadsList.get(i);
            if (upload.getGameName().equals(gameName)) {
                upload.setStatus(UploadStatus.Status.COMPLETED);
                upload.setProgress(100);
                adapter.notifyDataSetChanged();
                break;
            }
        }
    }

    private void handleUploadError(Intent intent) {
        String gameName = intent.getStringExtra("game_name");
        String error = intent.getStringExtra("error");

        for (int i = 0; i < uploadsList.size(); i++) {
            UploadStatus upload = uploadsList.get(i);
            if (upload.getGameName().equals(gameName)) {
                upload.setStatus(UploadStatus.Status.ERROR);
                upload.setErrorMessage(error);
                adapter.notifyDataSetChanged();
                break;
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (uploadReceiver != null) {
            unregisterReceiver(uploadReceiver);
        }
    }
}

