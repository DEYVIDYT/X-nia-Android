package com.winlator.Download;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;
import com.google.android.material.color.DynamicColors;


public class UploadApiSettingsHostActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivityIfAvailable(this);
        setContentView(R.layout.activity_upload_api_settings_host);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Upload API Settings");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.upload_api_settings_container, new UploadApiSettingsFragment())
                    .commit();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        // Handle the Up button action (back navigation)
        onBackPressed();
        return true;
    }
}
