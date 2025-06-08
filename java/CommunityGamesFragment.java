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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.winlator.Download.adapter.CommunityGamesAdapter;
import com.winlator.Download.model.CommunityGame;
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
    
    // Dialog variables
    private Uri selectedFileUri;
    private String selectedFileName;
    private long selectedFileSize;
    private EditText etDialogGameName;
    private TextView tvDialogSelectedFile;
    private Button btnDialogUpload;

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
        
        setupFabClickListener();
        loadCommunityGames();
        
        return view;
    }

    private void setupFabClickListener() {
        fabUpload.setOnClickListener(v -> showUploadDialog());
    }

    private void showUploadDialog() {
        // Verificar se as configurações estão salvas
        SharedPreferences prefs = requireContext().getSharedPreferences("community_games", requireContext().MODE_PRIVATE);
        String accessKey = prefs.getString("access_key", "");
        String secretKey = prefs.getString("secret_key", "");
        String itemIdentifier = prefs.getString("item_identifier", "");

        if (accessKey.isEmpty() || secretKey.isEmpty() || itemIdentifier.isEmpty()) {
            Toast.makeText(getContext(), "Configure primeiro as credenciais do Internet Archive nas configurações", Toast.LENGTH_LONG).show();
            // Abrir activity de configurações
            Intent intent = new Intent(getContext(), SettingsActivity.class);
            startActivity(intent);
            return;
        }

        // Criar dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), com.google.android.material.R.style.ThemeOverlay_Material3_AlertDialog);
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_upload_game, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();

        // Encontrar views do dialog
        this.etDialogGameName = dialogView.findViewById(R.id.et_dialog_game_name);
        Button btnSelectFile = dialogView.findViewById(R.id.btn_dialog_select_file); // This can remain local if not needed elsewhere
        this.tvDialogSelectedFile = dialogView.findViewById(R.id.tv_dialog_selected_file);
        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel); // Can remain local
        this.btnDialogUpload = dialogView.findViewById(R.id.btn_dialog_upload);

        // Reset variables
        selectedFileUri = null;
        selectedFileName = null;
        selectedFileSize = 0;
        updateUploadButtonState(); // Set initial state

        // Setup listeners
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
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/zip");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
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

            // Iniciar upload
            Intent uploadIntent = new Intent(getContext(), UploadService.class);
            uploadIntent.putExtra("game_name", gameName);
            uploadIntent.putExtra("access_key", accessKey);
            uploadIntent.putExtra("secret_key", secretKey);
            uploadIntent.putExtra("item_identifier", itemIdentifier);
            uploadIntent.putExtra("file_uri", selectedFileUri.toString());
            uploadIntent.putExtra("file_name", selectedFileName);
            uploadIntent.putExtra("file_size", selectedFileSize);
            
            requireContext().startForegroundService(uploadIntent);
            
            Toast.makeText(getContext(), "Upload iniciado", Toast.LENGTH_SHORT).show();
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
                getFileInfo(selectedFileUri);
            }
        }
    }

    private void getFileInfo(Uri uri) {
        try {
            Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                
                selectedFileName = cursor.getString(nameIndex);
                selectedFileSize = cursor.getLong(sizeIndex);
                
                // Atualizar TextView no dialog se estiver visível
                if (this.tvDialogSelectedFile != null) {
                    this.tvDialogSelectedFile.setText("Arquivo: " + selectedFileName + " (" + formatFileSize(selectedFileSize) + ")");
                }
                updateUploadButtonState();
                
                cursor.close();
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), "Erro ao obter informações do arquivo", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateUploadButtonState() {
        if (this.btnDialogUpload == null || this.etDialogGameName == null) {
            // Views not initialized yet, or dialog not visible.
            return;
        }
        String gameName = this.etDialogGameName.getText().toString().trim();
        if (!gameName.isEmpty() && this.selectedFileUri != null) {
            this.btnDialogUpload.setEnabled(true);
        } else {
            this.btnDialogUpload.setEnabled(false);
        }
    }

    private void cleanupDialogViewReferences() {
        this.etDialogGameName = null;
        this.tvDialogSelectedFile = null;
        this.btnDialogUpload = null;
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
        // Note: The executor shutdown is in onDestroy(), not onDestroyView(). That's usually fine.
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}

