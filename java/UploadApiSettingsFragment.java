package com.winlator.Download;

import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceCategory;

public class UploadApiSettingsFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    // Preference keys used in preferences_upload_api.xml and SharedPreferences
    private static final String KEY_UPLOAD_SERVICE = "upload_service"; // Key for the ListPreference to select upload service
    private static final String KEY_DATANODES_API_KEY = "datanodes_api_key"; // Key for the EditTextPreference for Datanodes API key
    private static final String KEY_DATANODES_CATEGORY = "Credenciais do Datanodes.to"; // Title of the Datanodes PreferenceCategory, used to find it
    private static final String VAL_DATANODES = "datanodes"; // Value for Datanodes.to in the ListPreference

    // UI Preference elements
    private ListPreference uploadServicePreference; // ListPreference for selecting upload service
    private PreferenceCategory datanodesCategory; // PreferenceCategory for Datanodes credentials
    private EditTextPreference datanodesApiKeyPreference; // EditTextPreference for Datanodes API key (though not directly manipulated for visibility here, it's part of the category)

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName("community_games");
        setPreferencesFromResource(R.xml.preferences_upload_api, rootKey);

        uploadServicePreference = findPreference(KEY_UPLOAD_SERVICE);
        // The Datanodes category is found by its title, which must match app:title in the XML.
        datanodesCategory = findPreference(KEY_DATANODES_CATEGORY);
        datanodesApiKeyPreference = findPreference(KEY_DATANODES_API_KEY); // Reference to the API key preference itself

        // Set the initial visibility of the Datanodes API key category
        // based on the currently selected upload service preference.
        toggleDatanodesOptions(getPreferenceManager().getSharedPreferences().getString(KEY_UPLOAD_SERVICE, "internet_archive"));
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        // Refresh summary for ListPreference as it's not using useSimpleSummaryProvider
        if (uploadServicePreference != null) {
            uploadServicePreference.setSummary(uploadServicePreference.getEntry());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // Check if the changed preference is the upload service selection
        if (KEY_UPLOAD_SERVICE.equals(key)) {
            // Get the newly selected service value
            String selectedService = sharedPreferences.getString(key, "internet_archive");
            // Update the visibility of the Datanodes API key category based on the new selection
            toggleDatanodesOptions(selectedService);
            // Update summary for the ListPreference to show the currently selected service name
            if (uploadServicePreference != null) {
                uploadServicePreference.setSummary(uploadServicePreference.getEntry());
            }
        }
    }

    /**
     * Toggles the visibility of the Datanodes.to API key preference category.
     * The category (and thus the API key field within it) is shown only if "Datanodes.to"
     * is the selected upload service.
     *
     * @param selectedService The value of the currently selected upload service
     *                        (e.g., "internet_archive" or "datanodes").
     */
    private void toggleDatanodesOptions(String selectedService) {
        // Determine if Datanodes.to is the selected service
        boolean isDatanodes = VAL_DATANODES.equals(selectedService);
        // Set the visibility of the Datanodes category
        if (datanodesCategory != null) {
            datanodesCategory.setVisible(isDatanodes);
        }
        // The EditTextPreference for the API key (datanodesApiKeyPreference) is contained within
        // datanodesCategory. Making the category invisible effectively hides the API key field.
        // If the API key field were outside the category, its visibility would need to be
        // managed separately here as well.
    }
}
