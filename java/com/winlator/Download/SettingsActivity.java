package com.winlator.Download;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
// import android.widget.Toast; // Not strictly needed if no toasts are shown

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.color.DynamicColors;

public class SettingsActivity extends AppCompatActivity {

    private Button btnConfigureUploadApi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivityIfAvailable(this);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Configurações");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        initViews();
        setupClickListeners();
    }

    private void initViews() {
        btnConfigureUploadApi = findViewById(R.id.btn_configure_upload_api);
    }

    private void setupClickListeners() {
        if (btnConfigureUploadApi != null) {
            btnConfigureUploadApi.setOnClickListener(v -> {
                Intent intent = new Intent(SettingsActivity.this, UploadApiSettingsHostActivity.class);
                startActivity(intent);
            });
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
