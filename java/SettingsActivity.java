package com.winlator.Download;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
// import android.widget.Toast; // Not strictly needed if no toasts are shown

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.color.DynamicColors;

public class SettingsActivity extends AppCompatActivity {

    // private Button btnConfigureUploadApi; // Removed

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivityIfAvailable(this);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Configurações");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // initViews(); // Removed
        // setupClickListeners(); // Removed
    }

    // private void initViews() { // Removed
        // btnConfigureUploadApi = findViewById(R.id.btn_configure_upload_api); // Removed
    // } // Removed

    // private void setupClickListeners() { // Removed
        // if (btnConfigureUploadApi != null) { // Removed
            // btnConfigureUploadApi.setOnClickListener(v -> { // Removed
                // Intent intent = new Intent(SettingsActivity.this, UploadApiSettingsHostActivity.class); // Removed
                // startActivity(intent); // Removed
            // }); // Removed
        // } // Removed
    // } // Removed

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
