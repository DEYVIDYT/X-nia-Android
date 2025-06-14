package com.winlator.Download;

import android.os.Bundle;
import androidx.preference.PreferenceFragmentCompat;

// Unnecessary imports can be removed if not used by other preferences,
// but keeping them for now if other preferences might be added later.
// import android.content.SharedPreferences;
// import androidx.preference.EditTextPreference;
// import androidx.preference.ListPreference;
// import androidx.preference.Preference;
// import androidx.preference.PreferenceManager;
// import androidx.preference.PreferenceCategory;

public class UploadApiSettingsFragment extends PreferenceFragmentCompat {
    // Removed SharedPreferences.OnSharedPreferenceChangeListener implementation as it's no longer needed
    // for the simplified preferences.

    // Removed constant string keys:
    // KEY_UPLOAD_SERVICE, KEY_DATANODES_API_KEY, KEY_DATANODES_CATEGORY, VAL_DATANODES

    // Removed fields:
    // uploadServicePreference, datanodesCategory, datanodesApiKeyPreference

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // Fragment now uses default SharedPreferences as setSharedPreferencesName was removed previously.
        setPreferencesFromResource(R.xml.preferences_upload_api, rootKey);

        // Removed initialization of Datanodes-related preference fields:
        // uploadServicePreference = findPreference(KEY_UPLOAD_SERVICE);
        // datanodesCategory = findPreference(KEY_DATANODES_CATEGORY);
        // datanodesApiKeyPreference = findPreference(KEY_DATANODES_API_KEY);

        // Removed call to toggleDatanodesOptions:
        // toggleDatanodesOptions(getPreferenceManager().getSharedPreferences().getString(KEY_UPLOAD_SERVICE, "internet_archive"));
    }

    @Override
    public void onResume() {
        super.onResume();
        // Removed SharedPreferences listener registration:
        // getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);

        // Removed refresh logic for uploadServicePreference summary:
        // if (uploadServicePreference != null) {
        //     uploadServicePreference.setSummary(uploadServicePreference.getEntry());
        // }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Removed SharedPreferences listener unregistration:
        // getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    // Removed onSharedPreferenceChanged method:
    // @Override
    // public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) { ... }

    // Removed toggleDatanodesOptions method:
    // private void toggleDatanodesOptions(String selectedService) { ... }
}
