package com.winlator.Download;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.winlator.Download.adapter.CommunityGamesAdapter;
import com.winlator.Download.db.UploadRepository;
import com.winlator.Download.model.CommunityGame;
import com.winlator.Download.model.UploadStatus;
import com.winlator.Download.service.UploadService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CommunityGamesFragment extends Fragment {

    private static final int PICK_FILE_REQUEST = 1001;
    
    private RecyclerView recyclerView;
    private CommunityGamesAdapter adapter;
    private List<CommunityGame> gamesList;
    private ExecutorService executor;
    private FloatingActionButton fabUpload;
    private UploadRepository uploadRepository;
    
    // Dialog variables
    private Uri selectedFileUri;
    private String selectedFileName;
    private long selectedFileSize;
    private EditText etDialogGameName;
    private TextView tvDialogSelectedFile;
    private Button btnDialogUpload;
    private Spinner spinnerUploadDestination; // Added for the spinner

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
        // Use default shared preferences for app-wide settings.
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String accessKey = prefs.getString("access_key", "");
        String secretKey = prefs.getString("secret_key", "");
        String itemIdentifier = prefs.getString("item_identifier", "");
        String datanodesApiKey = prefs.getString("datanodes_api_key", "");
        String preferredService = prefs.getString("upload_service", "internet_archive");

        // Check if credentials for the default or selected service are configured.
        // This logic might need to be more nuanced depending on how you want to guide the user.
        // For now, we'll keep the Internet Archive check as a basic guard.
        // A more robust check would verify credentials based on the 'preferredService'.
        if ("internet_archive".equals(preferredService) && (accessKey.isEmpty() || secretKey.isEmpty() || itemIdentifier.isEmpty())) {
            Toast.makeText(getContext(), "Configure as credenciais do Internet Archive nas configurações.", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(getContext(), SettingsActivity.class);
            intent.putExtra("highlight_preference_key", "access_key"); // Optional: to guide user
            startActivity(intent);
            return;
        } else if ("datanodes".equals(preferredService) && datanodesApiKey.isEmpty()) {
            Toast.makeText(getContext(), "Configure a API Key do Datanodes.to nas configurações.", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(getContext(), SettingsActivity.class);
            intent.putExtra("highlight_preference_key", "datanodes_api_key"); // Optional
            startActivity(intent);
            return;
        }


        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_upload_game, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();

        this.etDialogGameName = dialogView.findViewById(R.id.et_dialog_game_name);
        Button btnSelectFile = dialogView.findViewById(R.id.btn_dialog_select_file);
        this.tvDialogSelectedFile = dialogView.findViewById(R.id.tv_dialog_selected_file);
        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        this.btnDialogUpload = dialogView.findViewById(R.id.btn_dialog_upload);
        this.spinnerUploadDestination = dialogView.findViewById(R.id.spinner_upload_destination); // Initialize spinner for upload destination choice

        // Setup Spinner for upload destination:
        // 1. Create an ArrayAdapter using the string array R.array.upload_service_entries (display names like "Internet Archive", "Datanodes.to")
        //    and a default spinner layout.
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.upload_service_entries, android.R.layout.simple_spinner_item);
        // 2. Specify the layout to use when the list of choices appears.
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // 3. Apply the adapter to the spinner.
        spinnerUploadDestination.setAdapter(adapter);

        // Set default Spinner selection based on the user's preferred upload service stored in SharedPreferences.
        // 'preferredService' was retrieved from SharedPreferences earlier in this method.
        // 'serviceValues' (e.g., ["internet_archive", "datanodes"]) corresponds to R.array.upload_service_values.
        final String[] serviceValues = getResources().getStringArray(R.array.upload_service_values);
        for (int i = 0; i < serviceValues.length; i++) {
            if (serviceValues[i].equals(preferredService)) {
                spinnerUploadDestination.setSelection(i); // Set the spinner to the preferred service
                break;
            }
        }

        selectedFileUri = null;
        selectedFileName = null;
        selectedFileSize = 0;
        updateUploadButtonState();

        this.etDialogGameName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updateUploadButtonState();
            }
        });

        btnSelectFile.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); // Changed to ACTION_OPEN_DOCUMENT
            intent.setType("*/*"); // Set a general type
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/x-zip-compressed",
                "application/x-rar-compressed",
                "application/vnd.rar",
                "application/x-7z-compressed"
            });
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); // Added flag for persistable permission
            startActivityForResult(intent, PICK_FILE_REQUEST);
        });

        btnCancel.setOnClickListener(v -> {
            dialog.dismiss();
            cleanupDialogViewReferences();
        });

        this.btnDialogUpload.setOnClickListener(v -> {
            String gameName = this.etDialogGameName.getText().toString().trim();
            
            if (gameName.isEmpty() || selectedFileUri == null) {
                Toast.makeText(getContext(), "Preencha o nome do jogo e selecione um arquivo", Toast.LENGTH_SHORT).show();
                return;
            }

            // Create and save UploadStatus to DB first
            // UploadStatus newUpload = new UploadStatus(gameName, selectedFileName, selectedFileSize, accessKey, secretKey, itemIdentifier, selectedFileUri.toString());
            // The UploadStatus constructor and DB interaction might need to be updated if they store service-specific credentials.
            // For now, we assume UploadService handles credential retrieval based on the selected service.
            // long uploadId = uploadRepository.insertUpload(newUpload);
            // newUpload.setId((int) uploadId);

            // Get the selected upload service value (e.g., "internet_archive", "datanodes") from the Spinner.
            // serviceValues (R.array.upload_service_values) is used here to get the actual value string.
            String selectedUploadServiceValue = serviceValues[spinnerUploadDestination.getSelectedItemPosition()];

            // Start UploadService Intent
            Intent uploadIntent = new Intent(getContext(), UploadService.class);
            // Common upload parameters
            // uploadIntent.putExtra("upload_id", newUpload.getId()); // If using DB for queueing, pass the ID.
            uploadIntent.putExtra("game_name", gameName);
            uploadIntent.putExtra("file_uri", selectedFileUri.toString());
            uploadIntent.putExtra("file_name", selectedFileName);
            uploadIntent.putExtra("file_size", selectedFileSize);
            // Pass the selected upload destination service to UploadService
            uploadIntent.putExtra("upload_destination_service", selectedUploadServiceValue);

            // Pass all potentially relevant API keys to UploadService.
            // UploadService will then use the appropriate keys based on 'selectedUploadServiceValue'.
            // This approach centralizes the decision of which keys to use within UploadService.
            uploadIntent.putExtra("access_key", prefs.getString("access_key", "")); // Internet Archive Access Key
            uploadIntent.putExtra("secret_key", prefs.getString("secret_key", "")); // Internet Archive Secret Key
            uploadIntent.putExtra("item_identifier", prefs.getString("item_identifier", "")); // Internet Archive Item Identifier
            uploadIntent.putExtra("datanodes_api_key", prefs.getString("datanodes_api_key", "")); // Datanodes.to API Key
            
            // Start the foreground service for uploading
            requireContext().startForegroundService(uploadIntent);
            
            // Show a toast message indicating the upload has started and to which service.
            Toast.makeText(getContext(), "Upload de " + gameName + " para " + spinnerUploadDestination.getSelectedItem().toString() + " iniciado.", Toast.LENGTH_SHORT).show();
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
                    android.util.Log.e("CommunityGamesFragment", "Failed to take persistable URI permission for: " + selectedFileUri, e);
                    Toast.makeText(getContext(), "Erro: Não foi possível obter permissão de acesso permanente ao arquivo.", Toast.LENGTH_LONG).show();
                    selectedFileUri = null;
                    selectedFileName = null;
                    selectedFileSize = 0;
                    if (this.tvDialogSelectedFile != null) {
                         this.tvDialogSelectedFile.setText("Falha ao obter permissão.");
                    }
                    updateUploadButtonState();
                }
            } else {
                if (this.tvDialogSelectedFile != null) {
                  this.tvDialogSelectedFile.setText("Nenhum arquivo selecionado.");
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
                
                if (nameIndex != -1) {
                    selectedFileName = cursor.getString(nameIndex);
                } else {
                    // Fallback if display name is not available (less common for ACTION_OPEN_DOCUMENT)
                    selectedFileName = uri.getLastPathSegment();
                }

                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                    selectedFileSize = cursor.getLong(sizeIndex);
                } else {
                    selectedFileSize = -1; // Indicate unknown size
                }
                
                if (this.tvDialogSelectedFile != null) {
                    String fileSizeStr = (selectedFileSize != -1) ? formatFileSize(selectedFileSize) : "Tamanho desconhecido";
                    this.tvDialogSelectedFile.setText("Arquivo: " + selectedFileName + " (" + fileSizeStr + ")");
                }
                updateUploadButtonState();
            }
        } catch (Exception e) {
            android.util.Log.e("CommunityGamesFragment", "Error getting file info", e);
            Toast.makeText(getContext(), "Erro ao obter informações do arquivo", Toast.LENGTH_SHORT).show();
            selectedFileName = null;
            selectedFileSize = 0;
            if (this.tvDialogSelectedFile != null) {
                this.tvDialogSelectedFile.setText("Erro ao ler arquivo.");
            }
            updateUploadButtonState();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void updateUploadButtonState() {
        if (this.btnDialogUpload == null || this.etDialogGameName == null) {
            return;
        }
        String gameName = this.etDialogGameName.getText().toString().trim();
        this.btnDialogUpload.setEnabled(!gameName.isEmpty() && this.selectedFileUri != null);
    }

    private void cleanupDialogViewReferences() {
        this.etDialogGameName = null;
        this.tvDialogSelectedFile = null;
        this.btnDialogUpload = null;
        this.spinnerUploadDestination = null; // Clear spinner reference
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
        cleanupDialogViewReferences(); // Ensures dialog views are cleared
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}


