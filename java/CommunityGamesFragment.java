package com.winlator.Download;

// import android.app.AlertDialog; // To be removed
import androidx.appcompat.app.AlertDialog; // Added for explicit typing
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
import android.widget.Spinner; // Added for Spinner
import android.widget.AdapterView; // Added for Spinner listener
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
    private Spinner spinnerDialogSizeUnit; // Added
    private Button btnDialogUpload;
    private boolean isSeekBarChanging = false;
    private boolean isEditTextChanging = false; // To prevent listener loops
    private boolean isSpinnerChanging = false; // To prevent listener loops


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

        androidx.appcompat.app.AlertDialog dialog = builder.create(); // Changed variable type

        etDialogGameName = dialogView.findViewById(R.id.et_dialog_game_name);
        etDialogGameLink = dialogView.findViewById(R.id.et_dialog_game_link);
        sbDialogGameSize = dialogView.findViewById(R.id.sb_dialog_game_size);
        tvDialogSelectedSize = dialogView.findViewById(R.id.tv_dialog_selected_size);
        etDialogManualGameSize = dialogView.findViewById(R.id.et_dialog_game_size);
        spinnerDialogSizeUnit = dialogView.findViewById(R.id.spinner_dialog_size_unit); // Added
        Button btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        btnDialogUpload = dialogView.findViewById(R.id.btn_dialog_upload);

        // Initialize UI states
        tvDialogSelectedSize.setText(sbDialogGameSize.getProgress() + " GB");
        updateUploadButtonState(); // Initial state
        etDialogManualGameSize.setText("0"); // Initialize with 0
        spinnerDialogSizeUnit.setSelection(1); // Default to GB (index 1, MB is 0)
        tvDialogSelectedSize.setText("0 GB"); // Initial display for SeekBar

        TextWatcher formValidationWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateUploadButtonState(); }
        };

        etDialogGameName.addTextChangedListener(formValidationWatcher);
        etDialogGameLink.addTextChangedListener(formValidationWatcher);

        sbDialogGameSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    isSeekBarChanging = true;
                    tvDialogSelectedSize.setText(progress + " GB");
                    etDialogManualGameSize.setText(String.valueOf(progress));
                    spinnerDialogSizeUnit.setSelection(1); // Set to GB
                    updateUploadButtonState();
                    isSeekBarChanging = false;
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        etDialogManualGameSize.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (isSeekBarChanging || isSpinnerChanging) return;
                isEditTextChanging = true;
                updateSeekBarFromManualInput();
                updateUploadButtonState();
                isEditTextChanging = false;
            }
        });

        spinnerDialogSizeUnit.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isSeekBarChanging || isEditTextChanging) return;
                isSpinnerChanging = true;
                updateSeekBarFromManualInput();
                updateUploadButtonState();
                isSpinnerChanging = false;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnCancel.setOnClickListener(v -> {
            dialog.dismiss();
            // cleanupDialogViewReferences(); // Handled by onDismiss
        });

        btnDialogUpload.setOnClickListener(v -> {
            String gameName = etDialogGameName.getText().toString().trim();
            String gameLink = etDialogGameLink.getText().toString().trim();

            String sizeValueStr = etDialogManualGameSize.getText().toString().trim();
            if (TextUtils.isEmpty(sizeValueStr) || !isValidNumber(sizeValueStr)) {
                 etDialogManualGameSize.setError("Valor numérico inválido");
                 Toast.makeText(getContext(), "Valor de tamanho inválido", Toast.LENGTH_SHORT).show();
                return;
            }
            double sizeValue = Double.parseDouble(sizeValueStr);
            String selectedUnit = spinnerDialogSizeUnit.getSelectedItem().toString();
            long gameSizeBytes = 0;

            if ("MB".equalsIgnoreCase(selectedUnit)) {
                gameSizeBytes = (long) (sizeValue * 1024 * 1024);
            } else if ("GB".equalsIgnoreCase(selectedUnit)) {
                gameSizeBytes = (long) (sizeValue * 1024 * 1024 * 1024);
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

    // private long parseSizeStringToBytes(String sizeStr) { // Method Removed
    //     if (sizeStr == null || sizeStr.trim().isEmpty()) {
    //         return 0;
    //     }
    //     sizeStr = sizeStr.toUpperCase().trim();
    //     long multiplier = 1; // Default to bytes if no unit
    //     try {
    //         if (sizeStr.endsWith("GB")) {
    //             multiplier = 1024L * 1024L * 1024L;
    //             sizeStr = sizeStr.substring(0, sizeStr.length() - 2).trim();
    //         } else if (sizeStr.endsWith("MB")) {
    //             multiplier = 1024L * 1024L;
    //             sizeStr = sizeStr.substring(0, sizeStr.length() - 2).trim();
    //         } else if (sizeStr.endsWith("KB")) {
    //             multiplier = 1024L;
    //             sizeStr = sizeStr.substring(0, sizeStr.length() - 2).trim();
    //         } else if (sizeStr.endsWith("B")) { // Explicit bytes
    //              sizeStr = sizeStr.substring(0, sizeStr.length() - 1).trim();
    //         }
    //         // Remove any non-numeric characters that might remain after unit stripping (e.g. spaces)
    //         // And handle comma as decimal separator for some locales
    //         sizeStr = sizeStr.replaceAll("[^\\d.,]", "").replace(',', '.');
    //         if (sizeStr.isEmpty()) return 0;
    //
    //         double value = Double.parseDouble(sizeStr);
    //         return (long) (value * multiplier);
    //     } catch (NumberFormatException e) {
    //         Log.e("CommunityGamesFragment", "Error parsing size string: " + sizeStr, e);
    //         return 0; // Invalid format
    //     }
    // }

    private void updateUploadButtonState() {
        if (btnDialogUpload == null || etDialogGameName == null || etDialogGameLink == null || etDialogManualGameSize == null || sbDialogGameSize == null) {
            return; // Dialog not fully initialized or already dismissed
        }
        String gameName = etDialogGameName.getText().toString().trim();
        String gameLink = etDialogGameLink.getText().toString().trim();

        boolean isSizeValid = false;
        String manualSizeStr = etDialogManualGameSize.getText().toString().trim();
        boolean isSizeValueValid = false;
        if (!TextUtils.isEmpty(manualSizeStr) && isValidNumber(manualSizeStr)) {
            double value = Double.parseDouble(manualSizeStr);
            if (value > 0) {
                isSizeValueValid = true;
            }
        }
        // If manual input is empty or invalid, SeekBar must be > 0
        // However, our logic now syncs manual input from seekbar,
        // so primarily checking manualSizeStr is enough after sync.
        // For initial state or if user clears etDialogManualGameSize, this check is important.
        if (!isSizeValueValid && sbDialogGameSize.getProgress() > 0 && TextUtils.isEmpty(manualSizeStr)) {
             // This case might occur if user clears manual field after using seekbar
             // We can re-populate manual field from seekbar or just use its value
             // For simplicity, let's assume manual field should be valid.
        }


        btnDialogUpload.setEnabled(!TextUtils.isEmpty(gameName) && !TextUtils.isEmpty(gameLink) && isSizeValueValid);
    }

    private void updateSeekBarFromManualInput() {
        String sizeValueStr = etDialogManualGameSize.getText().toString().trim();
        if (!TextUtils.isEmpty(sizeValueStr) && isValidNumber(sizeValueStr)) {
            double value = Double.parseDouble(sizeValueStr);
            String selectedUnit = spinnerDialogSizeUnit.getSelectedItem().toString();
            long bytes = 0;
            if ("MB".equalsIgnoreCase(selectedUnit)) {
                bytes = (long) (value * 1024 * 1024);
            } else if ("GB".equalsIgnoreCase(selectedUnit)) {
                bytes = (long) (value * 1024 * 1024 * 1024);
            }

            if (bytes > 0) {
                double sizeInGB = (double) bytes / (1024L * 1024L * 1024L);
                int progress = (int) Math.round(sizeInGB);
                if (progress > sbDialogGameSize.getMax()) progress = sbDialogGameSize.getMax();

                isSeekBarChanging = true; // Prevent SeekBar listener from re-triggering this
                sbDialogGameSize.setProgress(progress);
                tvDialogSelectedSize.setText(progress + " GB"); // Keep this consistent with SeekBar
                isSeekBarChanging = false;
            } else { // If value is 0 or negative, reset seekbar
                 isSeekBarChanging = true;
                 sbDialogGameSize.setProgress(0);
                 tvDialogSelectedSize.setText("0 GB");
                 isSeekBarChanging = false;
            }
        } else if (TextUtils.isEmpty(sizeValueStr)) { // If field is empty, reset seekbar
            isSeekBarChanging = true;
            sbDialogGameSize.setProgress(0);
            tvDialogSelectedSize.setText("0 GB");
            isSeekBarChanging = false;
        }
    }

    private boolean isValidNumber(String str) {
        if (str == null || str.trim().isEmpty()) return false;
        try {
            Double.parseDouble(str.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void cleanupDialogViewReferences() {
        etDialogGameName = null;
        etDialogGameLink = null;
        sbDialogGameSize = null;
        tvDialogSelectedSize = null;
        etDialogManualGameSize = null;
        spinnerDialogSizeUnit = null; // Added
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


