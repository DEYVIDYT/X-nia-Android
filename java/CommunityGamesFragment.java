package com.winlator.Download;

import android.app.AlertDialog; // Keep for variable type if needed, or remove if var type changes
import com.google.android.material.dialog.MaterialAlertDialogBuilder; // Added
import android.content.Intent;
// import android.content.SharedPreferences; // Removed
// import android.database.Cursor; // Removed for file selection
// import android.net.Uri; // Removed for file selection
import android.os.Bundle;
// import android.provider.OpenableColumns; // Removed for file selection
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText; // Keep for game name
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.winlator.Download.adapter.CommunityGamesAdapter;
import com.winlator.Download.db.UploadRepository;
import com.winlator.Download.model.CommunityGame;
// import com.winlator.Download.model.UploadStatus; // Not directly used for creation here anymore
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

    // private static final int PICK_FILE_REQUEST = 1001; // Removed
    
    private RecyclerView recyclerView;
    private CommunityGamesAdapter adapter;
    private List<CommunityGame> gamesList;
    private ExecutorService executor;
    private FloatingActionButton fabUpload;
    private UploadRepository uploadRepository; // Keep if needed for other operations, or remove if only for upload
    
    // Dialog variables - updated
    private EditText etDialogGameName; // For game name
    private TextInputEditText etDialogGameLink;
    private SeekBar sbDialogGameSize;
    private TextView tvDialogSelectedSize;
    private TextInputEditText etDialogManualGameSize;
    private Button btnDialogUpload;
    private boolean isSeekBarChanging = false; // To prevent TextWatcher loop

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
        // SharedPreferences prefs = requireContext().getSharedPreferences("community_games", requireContext().MODE_PRIVATE); // Removed IA creds
        // String accessKey = prefs.getString("access_key", ""); // Removed
        // String secretKey = prefs.getString("secret_key", ""); // Removed
        // String itemIdentifier = prefs.getString("item_identifier", ""); // Removed

        // if (accessKey.isEmpty() || secretKey.isEmpty() || itemIdentifier.isEmpty()) { // Removed IA check
        //     Toast.makeText(getContext(), "Configure primeiro as credenciais do Internet Archive nas configurações", Toast.LENGTH_LONG).show();
        //     Intent intent = new Intent(getContext(), SettingsActivity.class);
        //     startActivity(intent);
        //     return;
        // }

        // AlertDialog.Builder builder = new AlertDialog.Builder(getContext()); // Old
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext()); // New
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_upload_game, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create(); // This is now an androidx.appcompat.app.AlertDialog

        etDialogGameName = dialogView.findViewById(R.id.et_dialog_game_name);
        etDialogGameLink = dialogView.findViewById(R.id.et_dialog_game_link);
        sbDialogGameSize = dialogView.findViewById(R.id.sb_dialog_game_size);
        tvDialogSelectedSize = dialogView.findViewById(R.id.tv_dialog_selected_size);
        etDialogManualGameSize = dialogView.findViewById(R.id.et_dialog_game_size);
        // Button btnSelectFile = dialogView.findViewById(R.id.btn_dialog_select_file); // Removed
        // tvDialogSelectedFile = dialogView.findViewById(R.id.tv_dialog_selected_file); // Removed
        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        btnDialogUpload = dialogView.findViewById(R.id.btn_dialog_upload);

        // Initialize UI states
        tvDialogSelectedSize.setText(sbDialogGameSize.getProgress() + " GB");
        updateUploadButtonState(); // Initial state based on empty fields

        TextWatcher textWatcherForEnableButton = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateUploadButtonState(); }
        };

        etDialogGameName.addTextChangedListener(textWatcherForEnableButton);
        etDialogGameLink.addTextChangedListener(textWatcherForEnableButton);

        sbDialogGameSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    isSeekBarChanging = true;
                    tvDialogSelectedSize.setText(progress + " GB");
                    etDialogManualGameSize.setText(""); // Clear manual input
                    updateUploadButtonState();
                    isSeekBarChanging = false;
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        etDialogManualGameSize.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (isSeekBarChanging) return; // Prevent loop if change is from SeekBar

                String sizeStr = s.toString().trim();
                if (!sizeStr.isEmpty()) {
                    long bytes = parseSizeStringToBytes(sizeStr);
                    int gb = 0;
                    if (bytes > 0) {
                        gb = (int) (bytes / (1024 * 1024 * 1024));
                        if (gb > sbDialogGameSize.getMax()) gb = sbDialogGameSize.getMax();
                        if (gb < 0) gb = 0; // Should not happen with parseSizeStringToBytes
                    }
                    sbDialogGameSize.setProgress(gb); // Update SeekBar progress
                    tvDialogSelectedSize.setText(gb + " GB"); // Reflect this on the label too
                }
                updateUploadButtonState();
            }
        });

        // btnSelectFile.setOnClickListener(v -> { ... }); // Removed file selection logic

        btnCancel.setOnClickListener(v -> {
            dialog.dismiss();
            // cleanupDialogViewReferences(); // Handled by onDismiss
        });

        btnDialogUpload.setOnClickListener(v -> {
            String gameName = etDialogGameName.getText().toString().trim();
            String gameLink = etDialogGameLink.getText().toString().trim();
            long gameSizeBytes = 0;

            String manualSizeStr = etDialogManualGameSize.getText().toString().trim();
            if (!TextUtils.isEmpty(manualSizeStr)) {
                gameSizeBytes = parseSizeStringToBytes(manualSizeStr);
            } else {
                gameSizeBytes = (long) sbDialogGameSize.getProgress() * 1024 * 1024 * 1024;
            }

            if (TextUtils.isEmpty(gameName)) {
                etDialogGameName.setError("Nome do jogo é obrigatório");
                Toast.makeText(getContext(), "Nome do jogo é obrigatório", Toast.LENGTH_SHORT).show();
                return;
            }
            if (TextUtils.isEmpty(gameLink)) {
                 etDialogGameLink.setError("Link do jogo é obrigatório");
                Toast.makeText(getContext(), "Link do jogo é obrigatório", Toast.LENGTH_SHORT).show();
                return;
            }
            // Basic URL validation (more robust validation server-side)
            try {
                new URL(gameLink);
            } catch (Exception e) {
                etDialogGameLink.setError("Link do jogo inválido");
                Toast.makeText(getContext(), "Link do jogo inválido", Toast.LENGTH_SHORT).show();
                return;
            }
            if (gameSizeBytes <= 0) {
                Toast.makeText(getContext(), "Tamanho do jogo deve ser maior que zero", Toast.LENGTH_SHORT).show();
                 etDialogManualGameSize.setError("Tamanho inválido");
                return;
            }

            // UploadService Intent
            Intent uploadIntent = new Intent(getContext(), UploadService.class);
            // uploadIntent.putExtra("upload_id", newUpload.getId()); // ID will be generated by service/repo
            uploadIntent.putExtra("game_name", gameName);
            uploadIntent.putExtra("game_link", gameLink);
            uploadIntent.putExtra("game_size_bytes", gameSizeBytes);
            uploadIntent.putExtra("file_name", gameName); // Using gameName as fileName

            ContextCompat.startForegroundService(requireContext(), uploadIntent);
            
            Toast.makeText(getContext(), "Registro de jogo iniciado", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            // cleanupDialogViewReferences(); // Handled by onDismiss
        });

        dialog.setOnDismissListener(dialogInterface -> cleanupDialogViewReferences());
        dialog.show();
    }

    // onActivityResult and getFileInfo removed as file selection is gone.

    private long parseSizeStringToBytes(String sizeStr) {
        if (sizeStr == null || sizeStr.trim().isEmpty()) {
            return 0;
        }
        sizeStr = sizeStr.toUpperCase().trim();
        long multiplier = 1; // Default to bytes if no unit
        try {
            if (sizeStr.endsWith("GB")) {
                multiplier = 1024L * 1024L * 1024L;
                sizeStr = sizeStr.substring(0, sizeStr.length() - 2).trim();
            } else if (sizeStr.endsWith("MB")) {
                multiplier = 1024L * 1024L;
                sizeStr = sizeStr.substring(0, sizeStr.length() - 2).trim();
            } else if (sizeStr.endsWith("KB")) {
                multiplier = 1024L;
                sizeStr = sizeStr.substring(0, sizeStr.length() - 2).trim();
            } else if (sizeStr.endsWith("B")) { // Explicit bytes
                 sizeStr = sizeStr.substring(0, sizeStr.length() - 1).trim();
            }
            // Remove any non-numeric characters that might remain after unit stripping (e.g. spaces)
            // And handle comma as decimal separator for some locales
            sizeStr = sizeStr.replaceAll("[^\\d.,]", "").replace(',', '.');
            if (sizeStr.isEmpty()) return 0;

            double value = Double.parseDouble(sizeStr);
            return (long) (value * multiplier);
        } catch (NumberFormatException e) {
            Log.e("CommunityGamesFragment", "Error parsing size string: " + sizeStr, e);
            return 0; // Invalid format
        }
    }

    private void updateUploadButtonState() {
        if (btnDialogUpload == null || etDialogGameName == null || etDialogGameLink == null || etDialogManualGameSize == null || sbDialogGameSize == null) {
            return; // Dialog not fully initialized or already dismissed
        }
        String gameName = etDialogGameName.getText().toString().trim();
        String gameLink = etDialogGameLink.getText().toString().trim();

        boolean isSizeValid = false;
        String manualSizeStr = etDialogManualGameSize.getText().toString().trim();
        if (!TextUtils.isEmpty(manualSizeStr)) {
            if (parseSizeStringToBytes(manualSizeStr) > 0) {
                isSizeValid = true;
            }
        } else {
            if (sbDialogGameSize.getProgress() > 0) {
                isSizeValid = true;
            }
        }

        btnDialogUpload.setEnabled(!TextUtils.isEmpty(gameName) && !TextUtils.isEmpty(gameLink) && isSizeValid);
    }

    private void cleanupDialogViewReferences() {
        etDialogGameName = null;
        etDialogGameLink = null;
        sbDialogGameSize = null;
        tvDialogSelectedSize = null;
        etDialogManualGameSize = null;
        btnDialogUpload = null;
    }

    // formatFileSize can be kept if used elsewhere, or removed if only for old dialog
    private String formatFileSize(long size) {
        if (size < 1024) return size + " B"; // Ensure this utility is still needed. UploadService has one.
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
}


