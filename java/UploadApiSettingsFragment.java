package com.winlator.Download;

import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

public class UploadApiSettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // Set the preferences file name before loading the preferences
        getPreferenceManager().setSharedPreferencesName("community_games");
        setPreferencesFromResource(R.xml.preferences_upload_api, rootKey);

        // Optional: Set summaries dynamically if not using useSimpleSummaryProvider,
        // or add any specific listeners if needed.
        // For now, useSimpleSummaryProvider in XML should handle basic summary updates.
        // Example for custom summary or action:
        // EditTextPreference accessKeyPref = findPreference("access_key");
        // if (accessKeyPref != null) {
        //     accessKeyPref.setOnPreferenceChangeListener((preference, newValue) -> {
        //         // Handle change if needed
        //         return true; // True to update the preference with the new value
        //     });
        // }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Potentially refresh summaries if they depend on values and don't auto-update
        // For useSimpleSummaryProvider, this is usually not needed.
    }
}
