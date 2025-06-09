package com.winlator.Download;

import com.google.android.material.color.DynamicColors;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build; // Needed for version check
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.Download.adapter.UploadMonitorAdapter;
import com.winlator.Download.db.UploadRepository;
import com.winlator.Download.model.UploadStatus;
import com.winlator.Download.service.UploadService;
import androidx.core.content.ContextCompat; // Added for ContextCompat

import java.util.ArrayList;
import java.util.List;

public class UploadMonitorActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private UploadMonitorAdapter adapter;
    private List<UploadStatus> uploadsList;
    private BroadcastReceiver uploadReceiver;
    private UploadRepository uploadRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivityIfAvailable(this);
        setContentView(R.layout.activity_upload_monitor);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Monitor de Uploads");
        }

        uploadRepository = new UploadRepository(this);
        initViews();
        loadUploads();
        setupBroadcastReceiver();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recycler_view_uploads);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        uploadsList = new ArrayList<>();
        adapter = new UploadMonitorAdapter(uploadsList);
        recyclerView.setAdapter(adapter);

        adapter.setOnItemClickListener(upload -> {
            if (upload.getStatus() == UploadStatus.Status.UPLOADING) {
                // Pause upload
                upload.setStatus(UploadStatus.Status.PAUSED);
                uploadRepository.updateUpload(upload);
                adapter.notifyItemChanged(uploadsList.indexOf(upload));
                // TODO: Send broadcast to UploadService to actually pause the ongoing upload
                Toast.makeText(this, "Upload de " + upload.getGameName() + " pausado.", Toast.LENGTH_SHORT).show();
            } else if (upload.getStatus() == UploadStatus.Status.PAUSED || upload.getStatus() == UploadStatus.Status.ERROR) {
                // Resume upload
                Intent serviceIntent = new Intent(this, UploadService.class);
                serviceIntent.putExtra("upload_id", upload.getId());
                serviceIntent.putExtra("game_name", upload.getGameName());
                serviceIntent.putExtra("file_name", upload.getFileName());
                serviceIntent.putExtra("file_size", upload.getFileSize());
                serviceIntent.putExtra("access_key", upload.getAccessKey());
                serviceIntent.putExtra("secret_key", upload.getSecretKey());
                serviceIntent.putExtra("item_identifier", upload.getItemIdentifier());
                serviceIntent.putExtra("file_uri", upload.getFileUri());
                serviceIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                Toast.makeText(this, "Retomando upload de " + upload.getGameName() + ".", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadUploads() {
        uploadsList.clear();
        uploadsList.addAll(uploadRepository.getAllUploads());
        adapter.notifyDataSetChanged();
        Log.d("UploadMonitorActivity", "Loaded " + uploadsList.size() + " uploads from database.");
    }

    private void setupBroadcastReceiver() {
        uploadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    int uploadId = intent.getIntExtra("upload_id", -1);
                    String gameName = intent.getStringExtra("game_name");
                    String fileName = intent.getStringExtra("file_name");
                    long fileSize = intent.getLongExtra("file_size", 0);
                    int progress = intent.getIntExtra("progress", 0);
                    String error = intent.getStringExtra("error");
                    long uploadedBytes = intent.getLongExtra("uploaded_bytes", 0);

                    UploadStatus currentUpload = findUploadById(uploadId);

                    switch (action) {
                        case UploadService.ACTION_UPLOAD_STARTED:
                            if (currentUpload == null) {
                                // This should ideally not happen if started from DB, but for new uploads
                                UploadStatus newUpload = new UploadStatus(uploadId, gameName, fileName, fileSize, UploadStatus.Status.UPLOADING, progress, null, System.currentTimeMillis(), uploadedBytes, intent.getStringExtra("access_key"), intent.getStringExtra("secret_key"), intent.getStringExtra("item_identifier"), intent.getStringExtra("file_uri"));
                                uploadsList.add(0, newUpload);
                                adapter.notifyItemInserted(0);
                                recyclerView.scrollToPosition(0);
                                Log.d("UploadMonitorActivity", "Upload STARTED (new): id=" + uploadId + ", game=" + gameName);
                            } else {
                                currentUpload.setStatus(UploadStatus.Status.UPLOADING);
                                currentUpload.setProgress(progress);
                                currentUpload.setUploadedBytes(uploadedBytes);
                                adapter.notifyItemChanged(uploadsList.indexOf(currentUpload));
                                Log.d("UploadMonitorActivity", "Upload STARTED (resumed): id=" + uploadId + ", game=" + gameName);
                            }
                            break;
                        case UploadService.ACTION_UPLOAD_PROGRESS:
                            if (currentUpload != null) {
                                currentUpload.setProgress(progress);
                                currentUpload.setUploadedBytes(uploadedBytes);
                                currentUpload.setStatus(UploadStatus.Status.UPLOADING);
                                adapter.notifyItemChanged(uploadsList.indexOf(currentUpload));
                                Log.d("UploadMonitorActivity", "Upload PROGRESS: id=" + uploadId + ", game=" + gameName + ", progress=" + progress + ", uploadedBytes=" + uploadedBytes);
                            }
                            break;
                        case UploadService.ACTION_UPLOAD_COMPLETED:
                            if (currentUpload != null) {
                                currentUpload.setStatus(UploadStatus.Status.COMPLETED);
                                currentUpload.setProgress(100);
                                currentUpload.setUploadedBytes(fileSize);
                                adapter.notifyItemChanged(uploadsList.indexOf(currentUpload));
                                Log.d("UploadMonitorActivity", "Upload COMPLETED: id=" + uploadId + ", game=" + gameName);
                            }
                            break;
                        case UploadService.ACTION_UPLOAD_ERROR:
                            if (currentUpload != null) {
                                currentUpload.setStatus(UploadStatus.Status.ERROR);
                                currentUpload.setErrorMessage(error);
                                adapter.notifyItemChanged(uploadsList.indexOf(currentUpload));
                                Log.d("UploadMonitorActivity", "Upload ERROR: id=" + uploadId + ", game=" + gameName + ", error=" + error);
                            }
                            break;
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(UploadService.ACTION_UPLOAD_STARTED);
        filter.addAction(UploadService.ACTION_UPLOAD_PROGRESS);
        filter.addAction(UploadService.ACTION_UPLOAD_COMPLETED);
        filter.addAction(UploadService.ACTION_UPLOAD_ERROR);
        registerReceiver(uploadReceiver, filter);
    }

    private UploadStatus findUploadById(int id) {
        for (UploadStatus upload : uploadsList) {
            if (upload.getId() == id) {
                return upload;
            }
        }
        return null;
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

