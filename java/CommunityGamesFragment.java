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
import android.util.Patterns; // Added for URL validation
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout; // Added for section visibility
import android.widget.RadioGroup;   // Added for RadioGroup
import android.widget.RadioButton;  // Added for RadioButton
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.app.AlertDialog; // For dialog instance type

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText; // For direct link fields
import com.winlator.Download.adapter.CommunityGamesAdapter;
import com.winlator.Download.db.UploadRepository;
import com.winlator.Download.model.CommunityGame;
import com.winlator.Download.model.UploadStatus;
import com.winlator.Download.service.UploadService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream; // Added
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.OutputStream; // Added for HttpURLConnection
import java.nio.charset.StandardCharsets; // Added for HttpURLConnection

public class CommunityGamesFragment extends Fragment {

    private static final int PICK_FILE_REQUEST = 1001;
    private static final String TAG = "CommunityGamesFragment"; // For logging
    
    private RecyclerView recyclerView;
    private CommunityGamesAdapter adapter;
    private List<CommunityGame> gamesList;
    private ExecutorService executor;
    private FloatingActionButton fabUpload;
    private UploadRepository uploadRepository;
    
    // Dialog variables - common
    private EditText etDialogGameName;
    private Button btnDialogUpload;

    // Dialog variables - file upload specific
    private Uri selectedFileUri;
    private String selectedFileName;
    private long selectedFileSize;
    private TextView tvDialogSelectedFile;
    private Button btnDialogSelectFile; // Re-added as a class member for updateUploadButtonState

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
        
        recyclerView = view.findViewById(R.id.recycler_view_community_games);
        fabUpload = view.findViewById(R.id.fab_upload);
        
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        
        gamesList = new ArrayList<>();
        adapter = new CommunityGamesAdapter(gamesList, getContext());
        recyclerView.setAdapter(adapter);
        
        executor = Executors.newSingleThreadExecutor();
        uploadRepository = new UploadRepository(getContext());
        
        setupFabClickListener();
        loadCommunityGames();
        
        return view;
    }

    private void setupFabClickListener() {
        fabUpload.setOnClickListener(v -> showUploadDialog());
    }

    private void showUploadDialog() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String accessKey = prefs.getString("access_key", "");
        String secretKey = prefs.getString("secret_key", "");
        String itemIdentifier = prefs.getString("item_identifier", "");

        android.util.Log.d(TAG, "Upload Dialog Check (Simplified):");
        android.util.Log.d(TAG, "IA Access Key Empty: " + (accessKey == null || accessKey.trim().isEmpty()));
        android.util.Log.d(TAG, "IA Secret Key Empty: " + (secretKey == null || secretKey.trim().isEmpty()));
        android.util.Log.d(TAG, "IA Item Identifier Empty: " + (itemIdentifier == null || itemIdentifier.trim().isEmpty()));

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

        // Initialize common views
        etDialogGameName = dialogView.findViewById(R.id.et_dialog_game_name);
        btnDialogUpload = dialogView.findViewById(R.id.btn_dialog_upload);
        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);

        // Initialize RadioGroup and RadioButtons
        rgUploadType = dialogView.findViewById(R.id.rg_upload_type);
        rbUploadFile = dialogView.findViewById(R.id.rb_upload_file);
        rbDirectLink = dialogView.findViewById(R.id.rb_direct_link);

        // Initialize sections
        sectionFileUpload = dialogView.findViewById(R.id.section_file_upload);
        sectionDirectLink = dialogView.findViewById(R.id.section_direct_link);

        // Initialize file upload specific views
        btnDialogSelectFile = dialogView.findViewById(R.id.btn_dialog_select_file);
        tvDialogSelectedFile = dialogView.findViewById(R.id.tv_dialog_selected_file);

        // Initialize direct link specific views
        etDialogGameUrl = dialogView.findViewById(R.id.et_dialog_game_url);
        etDialogGameSizeManual = dialogView.findViewById(R.id.et_dialog_game_size_manual);

        // Set initial state for file upload section
        selectedFileUri = null;
        selectedFileName = null;
        selectedFileSize = 0;

        // Add listener for RadioGroup
        rgUploadType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_upload_file) {
                sectionFileUpload.setVisibility(View.VISIBLE);
                sectionDirectLink.setVisibility(View.GONE);
            } else if (checkedId == R.id.rb_direct_link) {
                sectionFileUpload.setVisibility(View.GONE);
                sectionDirectLink.setVisibility(View.VISIBLE);
            }
            updateUploadButtonState(); // Update button state when selection changes
        });

        // Add TextChangedListeners for enabling upload button
        TextWatcher textWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateUploadButtonState(); }
        };
        etDialogGameName.addTextChangedListener(textWatcher);
        etDialogGameUrl.addTextChangedListener(textWatcher);
        etDialogGameSizeManual.addTextChangedListener(textWatcher);

        updateUploadButtonState(); // Initial button state

        btnDialogSelectFile.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/x-rar-compressed",
                "application/vnd.rar", "application/x-7z-compressed"
            });
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, PICK_FILE_REQUEST);
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
                // For now, just log these values. Actual submission will be handled later.
                // android.util.Log.d(TAG, "Direct Link Submission: Name=" + gameName + ", URL=" + gameUrl + ", Size=" + manualSize);
                // Toast.makeText(getContext(), "Enviando link direto...", Toast.LENGTH_SHORT).show();
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
                    getFileInfo(selectedFileUri); // This will call updateUploadButtonState
                } catch (SecurityException e) {
                    android.util.Log.e(TAG, "Failed to take persistable URI permission for: " + selectedFileUri, e);
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
            android.util.Log.e(TAG, "Error getting file info", e);
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
        updateUploadButtonState(); // Ensure button state is updated after getting file info or if an error occurs
    }

    private void updateUploadButtonState() {
        if (btnDialogUpload == null || etDialogGameName == null) {
            return; // Views not initialized yet
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
        executor.execute(() -> {
            try {
                URL url = new URL("https://ldgames.x10.mx/list_games.php");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    parseGamesJson(response.toString());
                } else {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> 
                            Toast.makeText(getContext(), "Erro ao carregar jogos da comunidade", Toast.LENGTH_SHORT).show()
                        );
                    }
                }
                connection.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> 
                        Toast.makeText(getContext(), "Erro de conexão", Toast.LENGTH_SHORT).show()
                    );
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
                    gamesList.clear();
                    gamesList.addAll(newGamesList);
                    adapter.notifyDataSetChanged();
                });
            }
        } catch (JSONException e) {
            e.printStackTrace();
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> 
                    Toast.makeText(getContext(), "Erro ao processar dados", Toast.LENGTH_SHORT).show()
                );
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

private void submitDirectLinkDetails(String name, String gameUrl, String manualSize) {
    if (executor == null || executor.isShutdown()) {
        // Consider if reinitialization is appropriate here or should be handled at a higher level
        // For now, let's assume it might be needed if fragment was backgrounded and executor terminated.
        executor = Executors.newSingleThreadExecutor();
    }
    executor.execute(() -> {
        String resultMessage;
        // boolean success = false; // Not strictly needed if only showing Toast

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
                byte[] input = jsonData.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            StringBuilder response = new StringBuilder();

            if (responseCode >= 200 && responseCode < 300) { // HTTP_OK is 200, also handle 201, 202 etc. as success
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
                // Assuming server sends back JSON like {"success": true/false, "message": "..."}
                JSONObject responseJson = new JSONObject(response.toString());
                if (responseJson.optBoolean("success", false)) {
                    resultMessage = "Jogo '" + name + "' submetido com link direto!";
                    // success = true;
                } else {
                    resultMessage = "Falha ao submeter link: " + responseJson.optString("message", "Erro desconhecido do servidor.");
                }
            } else {
                 // Try to read error stream for non-successful responses
                InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(errorStream, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = errorReader.readLine()) != null) {
                            response.append(line);
                        }
                    } catch (java.io.IOException ex) { // Changed to java.io.IOException
                        // Log if reading error stream itself fails, but proceed with response code
                         android.util.Log.e("CommunityGamesFragment", "Error reading error stream: " + ex.getMessage());
                    }
                }
                resultMessage = "Falha na submissão. Código: " + responseCode + ". Resposta: " + response.toString().trim();
                android.util.Log.e("CommunityGamesFragment", "Direct link submission error. Code: " + responseCode + ", Response: " + response.toString().trim());
            }

        } catch (org.json.JSONException e) {
            android.util.Log.e("CommunityGamesFragment", "JSON error during direct link submission", e);
            resultMessage = "Erro ao formar ou processar dados para submissão.";
        } catch (java.io.IOException e) { // Changed to java.io.IOException
            android.util.Log.e("CommunityGamesFragment", "Network I/O error during direct link submission", e);
            resultMessage = "Erro de rede: " + e.getMessage();
        } catch (Exception e) { // Catch any other unexpected exceptions
            android.util.Log.e("CommunityGamesFragment", "Unexpected error during direct link submission", e);
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
