package com.winlator.Download;

import android.Manifest; // Added
import android.content.pm.PackageManager; // Added
import androidx.core.app.ActivityCompat; // Added
import androidx.core.content.ContextCompat; // Added
import androidx.annotation.NonNull; // Added
// import androidx.appcompat.app.AlertDialog; // Will be replaced by MaterialAlertDialogBuilder for building
import androidx.appcompat.app.AlertDialog; // Still needed for the dialog instance type
import com.google.android.material.dialog.MaterialAlertDialogBuilder; // Added
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import android.content.DialogInterface; // Added
import android.content.Intent;
import android.net.Uri; // Added
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings; // Added
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.winlator.Download.model.Release;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private MyPagerAdapter pagerAdapter;
    private Map<String, List<Release>> apiData = new LinkedHashMap<>();
    private ProgressBar progressBar;
    private TextView errorTextView;

    private static final String API_URL = "https://raw.githubusercontent.com/DEYVIDYT/WINLATOR-DOWNLOAD/refs/heads/main/WINLATOR.json";
    private static final int STORAGE_PERMISSION_CODE = 101; // Added

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivityIfAvailable(this);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        tabLayout = findViewById(R.id.tab_layout);
        viewPager = findViewById(R.id.view_pager);
        progressBar = findViewById(R.id.progressBar);
        errorTextView = findViewById(R.id.errorTextView);

        // Adicionando logs para depuração
        if (progressBar == null) {
            Log.e("MainActivity", "progressBar is null after findViewById");
        } else {
            Log.d("MainActivity", "progressBar found");
        }

        if (errorTextView == null) {
            Log.e("MainActivity", "errorTextView is null after findViewById");
        } else {
            Log.d("MainActivity", "errorTextView found");
        }

        new FetchApiDataTask().execute(API_URL);

        if (!checkStoragePermissions()) {
            requestStoragePermissions();
        } else {
            // Permissions are already granted, proceed with app logic that needs storage
            // e.g., initializeFileDependentFeatures();
            // For now, just log or do nothing specific if already granted at this stage.
            Log.d("MainActivity", "Storage permissions already granted.");
        }
    }

    private boolean checkStoragePermissions() {
        // Check for READ_EXTERNAL_STORAGE is always relevant.
        boolean readGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

        // For WRITE_EXTERNAL_STORAGE, if we're on API 28 or lower, it must be granted.
        // If on API 29+, this permission (as defined with maxSdkVersion=28) is not available for general use,
        // so we don't strictly need to check its grant status for the app to proceed,
        // as long as READ is granted and the app uses modern storage practices.
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) { // Android 9 (Pie) or older
            boolean writeGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            return readGranted && writeGranted;
        } else { // Android 10 (Q) or newer
            return readGranted; // Primarily rely on READ for shared storage access.
        }
    }

    private void requestStoragePermissions() {
         ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                STORAGE_PERMISSION_CODE);
    }

    // onRequestPermissionsResult will be handled in the next subtask
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (checkStoragePermissions()) { // Re-check using the same logic as onCreate
                Toast.makeText(this, "Permissões de armazenamento concedidas!", Toast.LENGTH_SHORT).show();
                // Proceed with app logic
                // e.g., initializeFileDependentFeatures();
            } else {
                // Permissions were denied or not fully granted as per checkStoragePermissions logic
                boolean canRequestAgain = false;
                for (String permission : permissions) {
                    // Check if we can request any of the *requested* permissions again.
                    if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                        canRequestAgain = true;
                        break;
                    }
                }

                if (canRequestAgain) {
                    // User denied, but not "Don't ask again". Show dialog to explain and retry.
                    showPermissionDeniedDialog(false);
                } else {
                    // User denied with "Don't ask again" or permission is otherwise blocked.
                    // Show dialog to explain and guide to settings.
                    showPermissionDeniedDialog(true);
                }
            }
        }
    }

    private void showPermissionDeniedDialog(boolean goToSettings) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this); // Changed
        builder.setTitle("Permissão Necessária");
        builder.setMessage("Esta aplicação precisa da permissão de armazenamento para funcionar corretamente. Por favor, conceda a permissão.");
        builder.setCancelable(false); // User must interact with the dialog

        if (goToSettings) {
            builder.setPositiveButton("Abrir Configurações", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    // Intent to open app settings
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                    // Consider finishing MainActivity or re-checking permission in onResume after returning from settings
                }
            });
            builder.setNegativeButton("Sair", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    finish(); // Exit the app
                }
            });
        } else {
            builder.setPositiveButton("Conceder Permissão", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    requestStoragePermissions(); // Request again
                }
            });
            builder.setNegativeButton("Sair", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    finish(); // Exit the app
                }
            });
        }
        builder.show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_downloads) {
            Intent intent = new Intent(this, DownloadManagerActivity.class);
            startActivity(intent);
            return true;
        } else if (item.getItemId() == R.id.action_community_games) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
            return true;
        } else if (item.getItemId() == R.id.action_upload_monitor) {
            Intent intent = new Intent(this, UploadMonitorActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupViewPagerAndTabs() {
        pagerAdapter = new MyPagerAdapter(this, apiData);
        viewPager.setAdapter(pagerAdapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            if (position == 0) {
                tab.setText("Jogos da Comunidade");
            } else {
                List<String> categories = new ArrayList<>(apiData.keySet());
                tab.setText(categories.get(position - 1));
            }
        }).attach();
    }

    private static class MyPagerAdapter extends FragmentStateAdapter {
        private final Map<String, List<Release>> data;
        private final List<String> categories;

        public MyPagerAdapter(AppCompatActivity activity, Map<String, List<Release>> data) {
            super(activity);
            this.data = data;
            this.categories = new ArrayList<>(data.keySet());
        }

        @Override
        public Fragment createFragment(int position) {
            // Primeira aba sempre será Jogos da Comunidade
            if (position == 0) {
                return new CommunityGamesFragment();
            } else {
                // Abas seguintes são as categorias da API
                String category = categories.get(position - 1);
                List<Release> categoryReleases = data.get(category);
                return ReleasesFragment.newInstance(category, categoryReleases);
            }
        }

        @Override
        public int getItemCount() {
            // +1 para incluir a aba de Jogos da Comunidade
            return categories.size() + 1;
        }
    }

    private class FetchApiDataTask extends AsyncTask<String, Void, Map<String, List<Release>>> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            // Verificação adicional antes de usar progressBar e errorTextView
            if (progressBar != null) {
                progressBar.setVisibility(View.VISIBLE);
            } else {
                Log.e("MainActivity", "progressBar is null in onPreExecute");
            }
            if (errorTextView != null) {
                errorTextView.setVisibility(View.GONE);
            } else {
                Log.e("MainActivity", "errorTextView is null in onPreExecute");
            }
            if (tabLayout != null) {
                tabLayout.setVisibility(View.GONE);
            } else {
                Log.e("MainActivity", "tabLayout is null in onPreExecute");
            }
            if (viewPager != null) {
                viewPager.setVisibility(View.GONE);
            } else {
                Log.e("MainActivity", "viewPager is null in onPreExecute");
            }
        }

        @Override
        protected Map<String, List<Release>> doInBackground(String... urls) {
            if (urls.length == 0) {
                return null;
            }

            String urlString = urls[0];
            HttpURLConnection urlConnection = null;
            BufferedReader reader = null;
            String jsonString = null;

            Map<String, List<Release>> allReleasesData = new LinkedHashMap<>();

            try {
                URL url = new URL(urlString);
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                urlConnection.setConnectTimeout(15000);
                urlConnection.setReadTimeout(15000);
                urlConnection.connect();

                int responseCode = urlConnection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e("MainActivity", "HTTP error code: " + responseCode);
                    return null;
                }

                StringBuilder buffer = new StringBuilder();
                reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    buffer.append(line).append("\n");
                }

                if (buffer.length() == 0) {
                    return null;
                }
                jsonString = buffer.toString();

                JSONObject jsonObject = new JSONObject(jsonString);

                Iterator<String> categories = jsonObject.keys();
                while (categories.hasNext()) {
                    String category = categories.next();
                    JSONObject categoryObject = jsonObject.getJSONObject(category);
                    List<Release> releasesForCategory = new ArrayList<>();

                    Iterator<String> repos = categoryObject.keys();
                    while (repos.hasNext()) {
                        String repoName = repos.next();
                        String repoUrl = categoryObject.getString(repoName);

                        // Fetch latest release for each repository
                        String apiUrl = convertToApiUrl(repoUrl);
                        if (apiUrl != null) {
                            Release latestRelease = fetchLatestRelease(apiUrl, repoName, repoUrl);
                            if (latestRelease != null) {
                                releasesForCategory.add(latestRelease);
                            }
                        }
                    }
                    allReleasesData.put(category, releasesForCategory);
                }
                return allReleasesData;

            } catch (Exception e) {
                Log.e("MainActivity", "Error fetching or parsing JSON", e);
                return null;
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (final Exception e) {
                        Log.e("MainActivity", "Error closing stream", e);
                    }
                }
            }
        }

        @Override
        protected void onPostExecute(Map<String, List<Release>> result) {
            super.onPostExecute(result);
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }

            // Sempre configurar o ViewPager, mesmo se a API falhar
            // Isso garante que a aba de Jogos da Comunidade sempre apareça
            if (result != null && !result.isEmpty()) {
                apiData.putAll(result);
            }
            
            setupViewPagerAndTabs();
            if (tabLayout != null) {
                tabLayout.setVisibility(View.VISIBLE);
            }
            if (viewPager != null) {
                viewPager.setVisibility(View.VISIBLE);
            }

            // Só mostrar erro se não conseguir configurar as abas
            if (result == null && apiData.isEmpty()) {
                if (errorTextView != null) {
                    errorTextView.setText("Erro ao carregar dados da API. A aba de Jogos da Comunidade ainda está disponível.");
                    errorTextView.setVisibility(View.VISIBLE);
                }
            }
        }

        private String convertToApiUrl(String githubUrl) {
            // Converter https://github.com/user/repo para https://api.github.com/repos/user/repo/releases/latest
            if (githubUrl != null && githubUrl.contains("github.com")) {
                Pattern pattern = Pattern.compile("https://github\\.com/([^/]+)/([^/]+)");
                Matcher matcher = pattern.matcher(githubUrl);
                if (matcher.find()) {
                    String user = matcher.group(1);
                    String repo = matcher.group(2);
                    return "https://api.github.com/repos/" + user + "/" + repo + "/releases/latest";
                }
            }
            return null;
        }

        private Release fetchLatestRelease(String apiUrl, String repoName, String htmlUrl) {
            HttpURLConnection urlConnection = null;
            BufferedReader reader = null;

            try {
                URL url = new URL(apiUrl);
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                urlConnection.setRequestProperty("Accept", "application/vnd.github.v3+json");
                urlConnection.setConnectTimeout(15000);
                urlConnection.setReadTimeout(15000);
                urlConnection.connect();

                int responseCode = urlConnection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e("MainActivity", "HTTP error code: " + responseCode + " for " + apiUrl);
                    return null;
                }

                StringBuilder buffer = new StringBuilder();
                reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    buffer.append(line).append("\n");
                }

                String jsonString = buffer.toString();
                JSONObject releaseJson = new JSONObject(jsonString);

                String tagName = releaseJson.optString("tag_name", "");
                String releaseName = releaseJson.optString("name", tagName);
                String body = releaseJson.optString("body", "");
                String publishedAt = releaseJson.optString("published_at", "");

                // Buscar o primeiro asset disponível para download
                JSONArray assets = releaseJson.optJSONArray("assets");
                String downloadUrl = "";
                String assetName = "";
                long assetSize = 0;

                if (assets != null && assets.length() > 0) {
                    JSONObject firstAsset = assets.getJSONObject(0);
                    downloadUrl = firstAsset.optString("browser_download_url", "");
                    assetName = firstAsset.optString("name", "");
                    assetSize = firstAsset.optLong("size", 0);
                }

                // Se não houver assets, usar o zipball_url
                if (downloadUrl.isEmpty()) {
                    downloadUrl = releaseJson.optString("zipball_url", "");
                    assetName = repoName + "-" + tagName + ".zip";
                }

                return new Release(
                    repoName,
                    tagName,
                    releaseName,
                    body,
                    downloadUrl,
                    htmlUrl,
                    assetName,
                    assetSize
                );

            } catch (Exception e) {
                Log.e("MainActivity", "Erro ao buscar release de " + apiUrl, e);
                return null;
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Exception e) {
                        Log.e("MainActivity", "Erro ao fechar stream", e);
                    }
                }
            }
        }
    }
}

