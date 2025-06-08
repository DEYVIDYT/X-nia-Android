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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import com.winlator.Download.service.UploadService;

public class CommunityGamesActivity extends AppCompatActivity {

    private static final int PICK_FILE_REQUEST = 1;
    private EditText etGameName;
    private EditText etAccessKey;
    private EditText etSecretKey;
    private EditText etItemIdentifier;
    private TextView tvSelectedFile;
    private Button btnSelectFile;
    private Button btnUpload;
    private Button btnSaveSettings;
    private Uri selectedFileUri;
    private String selectedFileName;
    private long selectedFileSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_games);

        initViews();
        loadSettings();
        setupClickListeners();
    }

    private void initViews() {
        etGameName = findViewById(R.id.et_game_name);
        etAccessKey = findViewById(R.id.et_access_key);
        etSecretKey = findViewById(R.id.et_secret_key);
        etItemIdentifier = findViewById(R.id.et_item_identifier);
        tvSelectedFile = findViewById(R.id.tv_selected_file);
        btnSelectFile = findViewById(R.id.btn_select_file);
        btnUpload = findViewById(R.id.btn_upload);
        btnSaveSettings = findViewById(R.id.btn_save_settings);
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences("community_games", MODE_PRIVATE);
        etAccessKey.setText(prefs.getString("access_key", ""));
        etSecretKey.setText(prefs.getString("secret_key", ""));
        etItemIdentifier.setText(prefs.getString("item_identifier", ""));
    }

    private void setupClickListeners() {
        btnSelectFile.setOnClickListener(v -> selectFile());
        btnUpload.setOnClickListener(v -> uploadGame());
        btnSaveSettings.setOnClickListener(v -> saveSettings());
    }

    private void selectFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/zip");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == PICK_FILE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            selectedFileUri = data.getData();
            if (selectedFileUri != null) {
                getFileInfo(selectedFileUri);
            }
        }
    }

    private void getFileInfo(Uri uri) {
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
            
            selectedFileName = cursor.getString(nameIndex);
            selectedFileSize = cursor.getLong(sizeIndex);
            
            tvSelectedFile.setText("Arquivo selecionado: " + selectedFileName + " (" + formatFileSize(selectedFileSize) + ")");
            cursor.close();
        }
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
        return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    private void uploadGame() {
        String gameName = etGameName.getText().toString().trim();
        String accessKey = etAccessKey.getText().toString().trim();
        String secretKey = etSecretKey.getText().toString().trim();
        String itemIdentifier = etItemIdentifier.getText().toString().trim();

        if (gameName.isEmpty() || accessKey.isEmpty() || 
            secretKey.isEmpty() || itemIdentifier.isEmpty() || selectedFileUri == null) {
            Toast.makeText(this, "Preencha todos os campos e selecione um arquivo", Toast.LENGTH_SHORT).show();
            return;
        }

        // Iniciar o serviço de upload
        Intent uploadIntent = new Intent(this, UploadService.class);
        uploadIntent.putExtra("game_name", gameName);
        uploadIntent.putExtra("access_key", accessKey);
        uploadIntent.putExtra("secret_key", secretKey);
        uploadIntent.putExtra("item_identifier", itemIdentifier);
        uploadIntent.putExtra("file_uri", selectedFileUri.toString());
        uploadIntent.putExtra("file_name", selectedFileName);
        uploadIntent.putExtra("file_size", selectedFileSize);
        
        startForegroundService(uploadIntent);
        
        Toast.makeText(this, "Upload iniciado", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences("community_games", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        editor.putString("access_key", etAccessKey.getText().toString().trim());
        editor.putString("secret_key", etSecretKey.getText().toString().trim());
        editor.putString("item_identifier", etItemIdentifier.getText().toString().trim());
        
        editor.apply();
        Toast.makeText(this, "Configurações salvas", Toast.LENGTH_SHORT).show();
    }
}

