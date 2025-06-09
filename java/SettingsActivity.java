package com.winlator.Download;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.color.DynamicColors;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import com.winlator.Download.service.UploadService;

public class SettingsActivity extends AppCompatActivity {

// Removed SharedPreferences and EditText imports as they are no longer directly used here.
// import android.content.SharedPreferences;
// import android.widget.EditText;
import android.widget.Toast; // Kept for potential future use, though not used in this version.

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.color.DynamicColors;
// Removed unused File, FileOutputStream, InputStream imports
// import java.io.File;
// import java.io.FileOutputStream;
// import java.io.InputStream;
import com.winlator.Download.service.UploadService; // This might be removable if no direct interaction. For now, keep.

public class SettingsActivity extends AppCompatActivity {

    // private static final int PICK_FILE_REQUEST = 1; // Removed
    // private EditText etGameName; // Removed
    // private EditText etAccessKey; // Removed
    // private EditText etSecretKey; // Removed
    // private EditText etItemIdentifier; // Removed
    // private TextView tvSelectedFile; // Removed
    // private Button btnSelectFile; // Removed
    // private Button btnUpload; // Removed
    // private Button btnSaveSettings; // Removed
    private Button btnConfigureUploadApi; // Added
    // private Uri selectedFileUri; // Removed
    // private String selectedFileName; // Removed
    // private long selectedFileSize; // Removed

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivityIfAvailable(this);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Configurações");
            // getSupportActionBar().setDisplayHomeAsUpEnabled(true); // Optional
        }

        initViews();
        // loadSettings(); // Removed
        setupClickListeners();
    }

    private void initViews() {
        // etGameName = findViewById(R.id.et_game_name); // Removed
        // etAccessKey = findViewById(R.id.et_access_key); // Removed
        // etSecretKey = findViewById(R.id.et_secret_key); // Removed
        // etItemIdentifier = findViewById(R.id.et_item_identifier); // Removed
        // tvSelectedFile = findViewById(R.id.tv_selected_file); // Removed
        // btnSelectFile = findViewById(R.id.btn_select_file); // Removed
        // btnUpload = findViewById(R.id.btn_upload); // Removed
        // btnSaveSettings = findViewById(R.id.btn_save_settings); // Removed
        btnConfigureUploadApi = findViewById(R.id.btn_configure_upload_api); // Added
    }

    // private void loadSettings() { ... } // Removed

    private void setupClickListeners() {
        // btnSelectFile.setOnClickListener(v -> selectFile()); // Removed
        // btnUpload.setOnClickListener(v -> uploadGame());    // Removed
        // btnSaveSettings.setOnClickListener(v -> saveSettings()); // Removed
        btnConfigureUploadApi.setOnClickListener(v -> { // Added
            Intent intent = new Intent(SettingsActivity.this, UploadApiSettingsHostActivity.class);
            startActivity(intent);
        });
    }

    // Removed selectFile() method
    // Removed onActivityResult() method
    // Removed getFileInfo() method
    // Removed formatFileSize() method
    // Removed uploadGame() method
    // Removed saveSettings() method
}

