package com.winlator.Download;

import android.app.Activity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.winlator.Download.adapter.CommunityGamesAdapter;
import com.winlator.Download.db.UploadRepository;
import com.winlator.Download.model.CommunityGame;
import com.winlator.Download.service.DownloadService;
import com.winlator.Download.service.UploadService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;

public class CommunityGamesFragment extends Fragment {

    private static final int PICK_FILE_REQUEST = 1001;
    private static final String TAG = "CommunityGamesFragment";

    // SharedPreferences Keys
    private static final String PREF_SORT_BY_KEY = "community_sort_by";
    private static final String PREF_SORT_ORDER_KEY = "community_sort_order";
    private static final String PREF_SEARCH_QUERY_KEY = "community_search_query";
    private static final String PREF_MIN_SIZE_KEY = "community_min_size";
    private static final String PREF_MAX_SIZE_KEY = "community_max_size";

    private static final String DEFAULT_SORT_BY = "date";
    private static final String DEFAULT_SORT_ORDER = "desc";
    
    private RecyclerView recyclerView;
    private CommunityGamesAdapter adapter;
    private List<CommunityGame> gamesList;
    private ExecutorService executor;
    private FloatingActionButton fabUpload;
    private UploadRepository uploadRepository;

    // Filter UI Elements
    private SearchView svGameNameFilter;
    private Spinner spinnerSortOptions;
    private MaterialButton btnSizeFilterDialog;

    // Loading and Empty State UI
    private ProgressBar pbCommunityLoading;
    private TextView tvCommunityEmpty;

    // Current Filter Values
    private String currentSortBy = DEFAULT_SORT_BY;
    private String currentSortOrder = DEFAULT_SORT_ORDER;
    private String currentSearchQuery = null;
    private Integer currentMinSizeMb = null;
    private Integer currentMaxSizeMb = null;
    
    // Dialog variables - common
    private EditText etDialogGameName;
    private Button btnDialogUpload;

    // Dialog variables - file upload specific
    private Uri selectedFileUri;
    private String selectedFileName;
    private long selectedFileSize;
    private TextView tvDialogSelectedFile;
    private Button btnDialogSelectFile;

    // Dialog variables - direct link specific
    private TextInputEditText etDialogGameUrl;
    private TextInputEditText etDialogGameSizeManual;

    // Dialog sections and radio buttons
    private RadioGroup rgUploadType;
    private RadioButton rbUploadFile;
    private RadioButton rbDirectLink;
    private LinearLayout sectionFileUpload;
    private LinearLayout sectionDirectLink;


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_community_games, container, false);
        
        loadFilterPreferences(); // Load preferences before setting up UI

        recyclerView = view.findViewById(R.id.recycler_view_community_games);
        fabUpload = view.findViewById(R.id.fab_upload);
        pbCommunityLoading = view.findViewById(R.id.pb_community_loading);
        tvCommunityEmpty = view.findViewById(R.id.tv_community_empty);
        
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        gamesList = new ArrayList<>();
        adapter = new CommunityGamesAdapter(gamesList, getContext());
        recyclerView.setAdapter(adapter);
        
        executor = Executors.newSingleThreadExecutor();
        uploadRepository = new UploadRepository(getContext());
        
        svGameNameFilter = view.findViewById(R.id.sv_game_name_filter);
        spinnerSortOptions = view.findViewById(R.id.spinner_sort_options);
        btnSizeFilterDialog = view.findViewById(R.id.btn_size_filter_dialog);

        setupFilterBarListeners(); // Setup listeners for filter UI (also updates UI from prefs)
        setupFabClickListener();
        applyFiltersAndLoadGames(); // Initial load with (potentially loaded) filters
        
        return view;
    }

    private void setupFilterBarListeners() {
        ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.sort_options_array, android.R.layout.simple_spinner_item);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSortOptions.setAdapter(spinnerAdapter);

        // Update Spinner selection based on loaded preferences
        String targetSortOption = mapSortStateToDisplayString(currentSortBy, currentSortOrder);
        String[] sortOptions = getResources().getStringArray(R.array.sort_options_array);
        for (int i = 0; i < sortOptions.length; i++) {
            if (sortOptions[i].equals(targetSortOption)) {
                spinnerSortOptions.setSelection(i, false); // false to prevent listener fire
                break;
            }
        }

        // Update SearchView from loaded preferences
        if (currentSearchQuery != null && svGameNameFilter != null) {
            svGameNameFilter.setQuery(currentSearchQuery, false); // false to not submit
        }


        spinnerSortOptions.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = parent.getItemAtPosition(position).toString();
                // TODO: Replace string literals with string resources for localization for robustness
                if ("Mais Recentes".equals(selected)) {
                    currentSortBy = "date"; currentSortOrder = "desc";
                } else if ("Mais Antigos".equals(selected)) {
                    currentSortBy = "date"; currentSortOrder = "asc";
                } else if ("Nome (A-Z)".equals(selected)) {
                    currentSortBy = "name"; currentSortOrder = "asc";
                } else if ("Nome (Z-A)".equals(selected)) {
                    currentSortBy = "name"; currentSortOrder = "desc";
                } else if ("Tamanho (Menor Primeiro)".equals(selected)) {
                    currentSortBy = "size"; currentSortOrder = "asc";
                } else if ("Tamanho (Maior Primeiro)".equals(selected)) {
                    currentSortBy = "size"; currentSortOrder = "desc";
                }
                applyFiltersAndLoadGames();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        svGameNameFilter.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                currentSearchQuery = query.trim();
                applyFiltersAndLoadGames();
                svGameNameFilter.clearFocus();
                return true;
            }
            @Override
            public boolean onQueryTextChange(String newText) {
                String trimmedNewText = newText.trim();
                if (trimmedNewText.isEmpty() && (currentSearchQuery != null && !currentSearchQuery.isEmpty())) {
                    currentSearchQuery = null;
                    applyFiltersAndLoadGames();
                } else if (!trimmedNewText.isEmpty() && trimmedNewText.length() > 2) {
                    // currentSearchQuery = trimmedNewText; // For live search, uncomment and debounce
                    // applyFiltersAndLoadGames();
                }
                return true;
            }
        });
         svGameNameFilter.setOnCloseListener(() -> {
            currentSearchQuery = null;
            applyFiltersAndLoadGames();
            svGameNameFilter.clearFocus();
            return false;
        });

        btnSizeFilterDialog.setOnClickListener(v -> {
            Toast.makeText(getContext(), "Filtro de tamanho a ser implementado.", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Size filter dialog to be implemented. currentMinSizeMb=" + currentMinSizeMb + ", currentMaxSizeMb=" + currentMaxSizeMb);
        });
    }

    private void applyFiltersAndLoadGames() {
        Log.d(TAG, "Applying filters: SortBy=" + currentSortBy +
                     ", SortOrder=" + currentSortOrder +
                     ", Query=" + currentSearchQuery +
                     ", MinSize=" + currentMinSizeMb +
                     ", MaxSize=" + currentMaxSizeMb);

        saveFilterPreferences(); // Save current filters

        if (pbCommunityLoading != null) pbCommunityLoading.setVisibility(View.VISIBLE);
        if (tvCommunityEmpty != null) tvCommunityEmpty.setVisibility(View.GONE);
        if (recyclerView != null) recyclerView.setVisibility(View.GONE);

        loadCommunityGames();
    }

    private void setupFabClickListener() {
        fabUpload.setOnClickListener(v -> showUploadDialog());
    }

    private void showUploadDialog() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String accessKey = prefs.getString("access_key", "");
        String secretKey = prefs.getString("secret_key", "");
        String itemIdentifier = prefs.getString("item_identifier", "");

        Log.d(TAG, "Upload Dialog Check (Simplified):");
        Log.d(TAG, "IA Access Key Empty: " + (accessKey == null || accessKey.trim().isEmpty()));
        Log.d(TAG, "IA Secret Key Empty: " + (secretKey == null || secretKey.trim().isEmpty()));
        Log.d(TAG, "IA Item Identifier Empty: " + (itemIdentifier == null || itemIdentifier.trim().isEmpty()));

        boolean iaKeysSet = (accessKey != null && !accessKey.trim().isEmpty()) &&
                            (secretKey != null && !secretKey.trim().isEmpty()) &&
                            (itemIdentifier != null && !itemIdentifier.trim().isEmpty());

        if (!iaKeysSet) {
            Toast.makeText(getContext(), "Configure as credenciais do Internet Archive nas configurações.", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(getContext(), SettingsActivity.class);
            startActivity(intent);
            return;
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_upload_game, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();

        etDialogGameName = dialogView.findViewById(R.id.et_dialog_game_name);
        btnDialogUpload = dialogView.findViewById(R.id.btn_dialog_upload);
        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        rgUploadType = dialogView.findViewById(R.id.rg_upload_type);
        rbUploadFile = dialogView.findViewById(R.id.rb_upload_file);
        rbDirectLink = dialogView.findViewById(R.id.rb_direct_link);
        sectionFileUpload = dialogView.findViewById(R.id.section_file_upload);
        sectionDirectLink = dialogView.findViewById(R.id.section_direct_link);
        btnDialogSelectFile = dialogView.findViewById(R.id.btn_dialog_select_file);
        tvDialogSelectedFile = dialogView.findViewById(R.id.tv_dialog_selected_file);
        etDialogGameUrl = dialogView.findViewById(R.id.et_dialog_game_url);
        etDialogGameSizeManual = dialogView.findViewById(R.id.et_dialog_game_size_manual);

        selectedFileUri = null;
        selectedFileName = null;
        selectedFileSize = 0;

        rgUploadType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_upload_file) {
                sectionFileUpload.setVisibility(View.VISIBLE);
                sectionDirectLink.setVisibility(View.GONE);
            } else if (checkedId == R.id.rb_direct_link) {
                sectionFileUpload.setVisibility(View.GONE);
                sectionDirectLink.setVisibility(View.VISIBLE);
            }
            updateUploadButtonState();
        });

        TextWatcher textWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateUploadButtonState(); }
        };
        etDialogGameName.addTextChangedListener(textWatcher);
        if(etDialogGameUrl != null) etDialogGameUrl.addTextChangedListener(textWatcher);
        if(etDialogGameSizeManual != null) etDialogGameSizeManual.addTextChangedListener(textWatcher);

        updateUploadButtonState();

        btnDialogSelectFile.setOnClickListener(v -> {
            Intent intentFilePicker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intentFilePicker.setType("*/*");
            intentFilePicker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/x-rar-compressed",
                "application/vnd.rar", "application/x-7z-compressed"
            });
            intentFilePicker.addCategory(Intent.CATEGORY_OPENABLE);
            intentFilePicker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intentFilePicker, PICK_FILE_REQUEST);
        });

        btnCancel.setOnClickListener(v -> {
            dialog.dismiss();
            cleanupDialogViewReferences();
        });

        btnDialogUpload.setOnClickListener(v -> {
            String gameName = etDialogGameName.getText().toString().trim();
            if (gameName.isEmpty()) {
                etDialogGameName.setError("Nome do jogo é obrigatório");
                return;
            }

            if (rbUploadFile.isChecked()) {
                if (selectedFileUri == null) {
                    Toast.makeText(getContext(), "Selecione um arquivo para upload", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent uploadIntent = new Intent(getContext(), UploadService.class);
                uploadIntent.putExtra("game_name", gameName);
                uploadIntent.putExtra("file_uri", selectedFileUri.toString());
                uploadIntent.putExtra("file_name", selectedFileName);
                uploadIntent.putExtra("file_size", selectedFileSize);
                uploadIntent.putExtra("access_key", prefs.getString("access_key", ""));
                uploadIntent.putExtra("secret_key", prefs.getString("secret_key", ""));
                uploadIntent.putExtra("item_identifier", prefs.getString("item_identifier", ""));
                requireContext().startForegroundService(uploadIntent);
                Toast.makeText(getContext(), "Upload de " + gameName + " iniciado.", Toast.LENGTH_SHORT).show();
            } else if (rbDirectLink.isChecked()) {
                String gameUrl = etDialogGameUrl.getText().toString().trim();
                String manualSize = etDialogGameSizeManual.getText().toString().trim();

                if (gameUrl.isEmpty() || !Patterns.WEB_URL.matcher(gameUrl).matches()) {
                    etDialogGameUrl.setError("URL inválida");
                    return;
                }
                if (manualSize.isEmpty()) {
                    etDialogGameSizeManual.setError("Tamanho é obrigatório");
                    return;
                }
                submitDirectLinkDetails(gameName, gameUrl, manualSize);
            }
            
            dialog.dismiss();
            cleanupDialogViewReferences();
        });

        dialog.setOnDismissListener(dialogInterface -> cleanupDialogViewReferences());
        dialog.show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            selectedFileUri = data.getData();
            if (selectedFileUri != null) {
                try {
                    final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    requireContext().getContentResolver().takePersistableUriPermission(selectedFileUri, takeFlags);
                    getFileInfo(selectedFileUri);
                } catch (SecurityException e) {
                    Log.e(TAG, "Failed to take persistable URI permission for: " + selectedFileUri, e);
                    Toast.makeText(getContext(), "Erro: Não foi possível obter permissão de acesso permanente ao arquivo.", Toast.LENGTH_LONG).show();
                    selectedFileUri = null;
                    selectedFileName = null;
                    selectedFileSize = 0;
                    if (tvDialogSelectedFile != null) {
                         tvDialogSelectedFile.setText("Falha ao obter permissão.");
                    }
                    updateUploadButtonState();
                }
            } else {
                if (tvDialogSelectedFile != null) {
                  tvDialogSelectedFile.setText("Nenhum arquivo selecionado.");
                }
                updateUploadButtonState();
            }
        }
    }

    private void getFileInfo(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = requireContext().getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                
                selectedFileName = (nameIndex != -1) ? cursor.getString(nameIndex) : uri.getLastPathSegment();
                selectedFileSize = (sizeIndex != -1 && !cursor.isNull(sizeIndex)) ? cursor.getLong(sizeIndex) : -1;
                
                if (tvDialogSelectedFile != null) {
                    String fileSizeStr = (selectedFileSize != -1) ? formatFileSize(selectedFileSize) : "Tamanho desconhecido";
                    tvDialogSelectedFile.setText("Arquivo: " + selectedFileName + " (" + fileSizeStr + ")");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting file info", e);
            Toast.makeText(getContext(), "Erro ao obter informações do arquivo", Toast.LENGTH_SHORT).show();
            selectedFileName = null;
            selectedFileSize = 0;
            if (tvDialogSelectedFile != null) {
                tvDialogSelectedFile.setText("Erro ao ler arquivo.");
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        updateUploadButtonState();
    }

    private void updateUploadButtonState() {
        if (btnDialogUpload == null || etDialogGameName == null) {
            return;
        }
        String gameName = etDialogGameName.getText().toString().trim();
        boolean isGameNameValid = !gameName.isEmpty();
        boolean canEnable = false;

        if (rbUploadFile != null && rbUploadFile.isChecked()) {
            canEnable = isGameNameValid && selectedFileUri != null;
        } else if (rbDirectLink != null && rbDirectLink.isChecked()) {
            String gameUrl = (etDialogGameUrl != null && etDialogGameUrl.getText() != null) ? etDialogGameUrl.getText().toString().trim() : "";
            String manualSize = (etDialogGameSizeManual != null && etDialogGameSizeManual.getText() != null) ? etDialogGameSizeManual.getText().toString().trim() : "";
            canEnable = isGameNameValid && !gameUrl.isEmpty() && Patterns.WEB_URL.matcher(gameUrl).matches() && !manualSize.isEmpty();
        }
        btnDialogUpload.setEnabled(canEnable);
    }

    private void cleanupDialogViewReferences() {
        etDialogGameName = null;
        tvDialogSelectedFile = null;
        btnDialogUpload = null;
        btnDialogSelectFile = null;

        rgUploadType = null;
        rbUploadFile = null;
        rbDirectLink = null;
        sectionFileUpload = null;
        sectionDirectLink = null;
        etDialogGameUrl = null;
        etDialogGameSizeManual = null;
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
        return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    private void loadCommunityGames() {
        Log.d(TAG, "loadCommunityGames called. Filters: SortBy=" + currentSortBy +
                     ", SortOrder=" + currentSortOrder +
                     ", Query=" + currentSearchQuery +
                     ", MinSize=" + currentMinSizeMb +
                     ", MaxSize=" + currentMaxSizeMb);
        executor.execute(() -> {
            try {
                StringBuilder urlBuilder = new StringBuilder("https://ldgames.x10.mx/list_games.php");
                List<String> params = new ArrayList<>();

                if (currentSortBy != null && !currentSortBy.isEmpty()) {
                    params.add("sort_by=" + URLEncoder.encode(currentSortBy, StandardCharsets.UTF_8.name()));
                    if (currentSortOrder != null && !currentSortOrder.isEmpty()) {
                        params.add("sort_order=" + URLEncoder.encode(currentSortOrder, StandardCharsets.UTF_8.name()));
                    }
                }
                if (currentSearchQuery != null && !currentSearchQuery.trim().isEmpty()) {
                    params.add("search_name=" + URLEncoder.encode(currentSearchQuery.trim(), StandardCharsets.UTF_8.name()));
                }
                if (currentMinSizeMb != null) {
                    params.add("min_size_mb=" + currentMinSizeMb.toString());
                }
                if (currentMaxSizeMb != null) {
                    params.add("max_size_mb=" + currentMaxSizeMb.toString());
                }

                if (!params.isEmpty()) {
                    urlBuilder.append("?");
                    for (int i = 0; i < params.size(); i++) {
                        if (i > 0) {
                            urlBuilder.append("&");
                        }
                        urlBuilder.append(params.get(i));
                    }
                }

                String finalUrl = urlBuilder.toString();
                Log.d(TAG, "Loading games from URL: " + finalUrl);

                URL url = new URL(finalUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        parseGamesJson(response.toString());
                    }
                } else {
                    Log.e(TAG, "Error loading games. HTTP Code: " + responseCode);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (pbCommunityLoading != null) pbCommunityLoading.setVisibility(View.GONE);
                            if (tvCommunityEmpty != null) {
                                tvCommunityEmpty.setText("Erro ao carregar jogos. Verifique sua conexão.");
                                tvCommunityEmpty.setVisibility(View.VISIBLE);
                            }
                            if (recyclerView != null) recyclerView.setVisibility(View.GONE);
                            Toast.makeText(getContext(), "Erro " + responseCode + " ao carregar jogos.", Toast.LENGTH_SHORT).show();
                        });
                    }
                }
                connection.disconnect();
            } catch (UnsupportedEncodingException e) {
                Log.e(TAG, "Error encoding URL parameters", e);
                 if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (pbCommunityLoading != null) pbCommunityLoading.setVisibility(View.GONE);
                        if (tvCommunityEmpty != null) {
                            tvCommunityEmpty.setText("Erro ao construir filtros.");
                            tvCommunityEmpty.setVisibility(View.VISIBLE);
                        }
                        if (recyclerView != null) recyclerView.setVisibility(View.GONE);
                        Toast.makeText(getContext(), "Erro ao construir filtros.", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading community games", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (pbCommunityLoading != null) pbCommunityLoading.setVisibility(View.GONE);
                        if (tvCommunityEmpty != null) {
                            tvCommunityEmpty.setText("Erro de conexão ao carregar jogos.");
                            tvCommunityEmpty.setVisibility(View.VISIBLE);
                        }
                        if (recyclerView != null) recyclerView.setVisibility(View.GONE);
                        Toast.makeText(getContext(), "Erro de conexão ao carregar jogos.", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }

    private void parseGamesJson(String jsonString) {
        try {
            JSONArray jsonArray = new JSONArray(jsonString);
            List<CommunityGame> newGamesList = new ArrayList<>();
            
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject gameObject = jsonArray.getJSONObject(i);
                String name = gameObject.getString("name");
                String size = gameObject.getString("size");
                String url = gameObject.getString("url");
                
                CommunityGame game = new CommunityGame(name, size, url);
                newGamesList.add(game);
            }
            
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (pbCommunityLoading != null) pbCommunityLoading.setVisibility(View.GONE);
                    gamesList.clear();
                    gamesList.addAll(newGamesList);
                    adapter.notifyDataSetChanged();
                    if (gamesList.isEmpty()) {
                        if (tvCommunityEmpty != null) {
                            tvCommunityEmpty.setText("Nenhum jogo encontrado com os filtros atuais.");
                            tvCommunityEmpty.setVisibility(View.VISIBLE);
                        }
                        if (recyclerView != null) recyclerView.setVisibility(View.GONE);
                    } else {
                        if (tvCommunityEmpty != null) tvCommunityEmpty.setVisibility(View.GONE);
                        if (recyclerView != null) recyclerView.setVisibility(View.VISIBLE);
                    }
                });
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing games JSON", e);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (pbCommunityLoading != null) pbCommunityLoading.setVisibility(View.GONE);
                    if (tvCommunityEmpty != null) {
                        tvCommunityEmpty.setText("Erro ao processar dados dos jogos.");
                        tvCommunityEmpty.setVisibility(View.VISIBLE);
                    }
                    if (recyclerView != null) recyclerView.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "Erro ao processar dados dos jogos.", Toast.LENGTH_SHORT).show();
                });
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cleanupDialogViewReferences();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    private void saveFilterPreferences() {
        if (getContext() == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        SharedPreferences.Editor editor = prefs.edit();

        if (currentSortBy != null) editor.putString(PREF_SORT_BY_KEY, currentSortBy); else editor.remove(PREF_SORT_BY_KEY);
        if (currentSortOrder != null) editor.putString(PREF_SORT_ORDER_KEY, currentSortOrder); else editor.remove(PREF_SORT_ORDER_KEY);
        if (currentSearchQuery != null) editor.putString(PREF_SEARCH_QUERY_KEY, currentSearchQuery); else editor.remove(PREF_SEARCH_QUERY_KEY);
        if (currentMinSizeMb != null) editor.putInt(PREF_MIN_SIZE_KEY, currentMinSizeMb); else editor.remove(PREF_MIN_SIZE_KEY);
        if (currentMaxSizeMb != null) editor.putInt(PREF_MAX_SIZE_KEY, currentMaxSizeMb); else editor.remove(PREF_MAX_SIZE_KEY);

        editor.apply();
        Log.d(TAG, "Saved filter preferences.");
    }

    private void loadFilterPreferences() {
        if (getContext() == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        currentSortBy = prefs.getString(PREF_SORT_BY_KEY, DEFAULT_SORT_BY);
        currentSortOrder = prefs.getString(PREF_SORT_ORDER_KEY, DEFAULT_SORT_ORDER);
        currentSearchQuery = prefs.getString(PREF_SEARCH_QUERY_KEY, null);

        if (prefs.contains(PREF_MIN_SIZE_KEY)) {
            currentMinSizeMb = prefs.getInt(PREF_MIN_SIZE_KEY, -1); // Use a sentinel like -1 if 0 is a valid min size
            if (currentMinSizeMb == -1 && !prefs.contains(PREF_MIN_SIZE_KEY+"_explicitly_saved_as_minus_one")) {
                // This check is a bit tricky. If -1 could be a valid value, need a different approach.
                // Or, simply trust that if it's there, it's intentional.
                // For now, if it's -1 (our default for getInt if key not found), we assume it means 'not set' unless we had a more complex save.
                // A simpler way: just getInt, and if it's the default for "not found" (e.g. 0 or -1), treat as null.
                // Let's assume 0 is not a typical filter value for min_size, so -1 is a safe "not set" indicator from getInt.
                 currentMinSizeMb = null; // If getInt returned default and key wasn't there.
            }
        } else {
            currentMinSizeMb = null;
        }

        if (prefs.contains(PREF_MAX_SIZE_KEY)) {
            currentMaxSizeMb = prefs.getInt(PREF_MAX_SIZE_KEY, -1);
             if (currentMaxSizeMb == -1 && !prefs.contains(PREF_MAX_SIZE_KEY+"_explicitly_saved_as_minus_one") ) { // Similar logic
                currentMaxSizeMb = null;
            }
        } else {
            currentMaxSizeMb = null;
        }
        Log.d(TAG, "Loaded filter preferences: SortBy=" + currentSortBy + ", Order=" + currentSortOrder + ", Query=" + currentSearchQuery + ", MinSize=" + currentMinSizeMb + ", MaxSize=" + currentMaxSizeMb);
    }

    private String mapSortStateToDisplayString(String sortBy, String sortOrder) {
        // This is a simplified mapping. Consider using a map or more robust structure.
        // Also, these strings should come from R.string resources for localization.
        if ("date".equals(sortBy) && "desc".equals(sortOrder)) return "Mais Recentes";
        if ("date".equals(sortBy) && "asc".equals(sortOrder)) return "Mais Antigos";
        if ("name".equals(sortBy) && "asc".equals(sortOrder)) return "Nome (A-Z)";
        if ("name".equals(sortBy) && "desc".equals(sortOrder)) return "Nome (Z-A)";
        if ("size".equals(sortBy) && "asc".equals(sortOrder)) return "Tamanho (Menor Primeiro)";
        if ("size".equals(sortBy) && "desc".equals(sortOrder)) return "Tamanho (Maior Primeiro)";
        return getResources().getStringArray(R.array.sort_options_array)[0]; // Default to first item
    }


private void submitDirectLinkDetails(String name, String gameUrl, String manualSize) {
    if (executor == null || executor.isShutdown()) {
        executor = Executors.newSingleThreadExecutor();
    }
    executor.execute(() -> {
        String resultMessage;
        HttpURLConnection connection = null;
        try {
            JSONObject jsonData = new JSONObject();
            jsonData.put("name", name);
            jsonData.put("url", gameUrl);
            jsonData.put("size", manualSize);
            jsonData.put("source", "direct_link");

            URL url = new URL("https://ldgames.x10.mx/add_update_game.php");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonData.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            StringBuilder response = new StringBuilder();

            InputStream responseStream = (responseCode >= 200 && responseCode < 300) ? connection.getInputStream() : connection.getErrorStream();
            if (responseStream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
            }

            if (responseCode >= 200 && responseCode < 300) {
                JSONObject responseJson = new JSONObject(response.toString());
                if (responseJson.optBoolean("success", false)) {
                    resultMessage = "Jogo '" + name + "' submetido com link direto!";
                } else {
                    resultMessage = "Falha ao submeter link: " + responseJson.optString("message", "Erro desconhecido do servidor.");
                }
            } else {
                resultMessage = "Falha na submissão. Código: " + responseCode + ". Resposta: " + response.toString().trim();
                Log.e(TAG, "Direct link submission error. Code: " + responseCode + ", Response: " + response.toString().trim());
            }

        } catch (JSONException e) {
            Log.e(TAG, "JSON error during direct link submission", e);
            resultMessage = "Erro ao formar ou processar dados para submissão.";
        } catch (java.io.IOException e) {
            Log.e(TAG, "Network I/O error during direct link submission", e);
            resultMessage = "Erro de rede: " + e.getMessage();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error during direct link submission", e);
            resultMessage = "Erro inesperado: " + e.getMessage();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        final String finalResultMessage = resultMessage;
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> Toast.makeText(getContext(), finalResultMessage, Toast.LENGTH_LONG).show());
        }
    });
}
}
