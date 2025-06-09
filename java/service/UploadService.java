package com.winlator.Download.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences; // Added
import androidx.preference.PreferenceManager; // Added
import com.winlator.Download.utils.DatanodesUploader; // Added
import com.winlator.Download.utils.InternetArchiveUploader;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.winlator.Download.MainActivity;
import com.winlator.Download.R;
import com.winlator.Download.db.UploadRepository;
import com.winlator.Download.model.UploadStatus;
import com.winlator.Download.utils.InternetArchiveUploader;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream; // Added
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.util.Base64; // Added for MD5
import android.database.Cursor; // Added
import android.provider.MediaStore; // Added

public class UploadService extends Service {

    private static final String CHANNEL_ID = "upload_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String DATANODES_SERVICE_ID = "datanodes";
    public static final String ACTION_UPLOAD_STARTED = "com.winlator.Download.UPLOAD_STARTED";
    public static final String ACTION_UPLOAD_PROGRESS = "com.winlator.Download.UPLOAD_PROGRESS";
    public static final String ACTION_UPLOAD_COMPLETED = "com.winlator.Download.UPLOAD_COMPLETED";
    public static final String ACTION_UPLOAD_ERROR = "com.winlator.Download.UPLOAD_ERROR";

    private NotificationManager notificationManager;
    private ExecutorService executor;
    private PowerManager.WakeLock wakeLock;
    private UploadRepository uploadRepository;

    // Md5ProgressListener interface removed

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        executor = Executors.newSingleThreadExecutor();
        uploadRepository = new UploadRepository(this);

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UploadService::UploadWakelockTag");
            wakeLock.setReferenceCounted(false);
        } else {
            Log.e("UploadService", "PowerManager not available, WakeLock not initialized.");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String gameName = intent.getStringExtra("game_name");
            String accessKey = intent.getStringExtra("access_key"); // IA Access Key
            String secretKey = intent.getStringExtra("secret_key");   // IA Secret Key
            String itemIdentifier = intent.getStringExtra("item_identifier"); // IA Item Identifier
            // Datanodes API Key, retrieved from intent (passed by CommunityGamesFragment)
            String datanodesApiKey = intent.getStringExtra("datanodes_api_key");

            String fileUriString = intent.getStringExtra("file_uri");
            String fileName = intent.getStringExtra("file_name");
            long fileSize = intent.getLongExtra("file_size", 0);
            int uploadId = intent.getIntExtra("upload_id", -1); // -1 if new upload

            // Get the selected upload destination service (e.g., "internet_archive" or "datanodes")
            String uploadDestinationService = intent.getStringExtra("upload_destination_service");
            // Fallback: If not provided directly in intent (e.g., service restart/retry scenarios),
            // try to get it from SharedPreferences, defaulting to "internet_archive".
            if (uploadDestinationService == null || uploadDestinationService.isEmpty()) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                uploadDestinationService = prefs.getString("upload_service", "internet_archive");
            }

            String currentProcessGameName = gameName;
            if (currentProcessGameName == null || currentProcessGameName.isEmpty()) {
                currentProcessGameName = "Upload";
            }

            Notification preparingOrInitialNotification = createNotification("Preparando " + currentProcessGameName + "...", 0);
            startForeground(NOTIFICATION_ID, preparingOrInitialNotification);

            final String finalUploadDestinationService = uploadDestinationService; // For use in lambda

            executor.execute(() -> {
                UploadStatus uploadStatus;
                if (uploadId != -1) {
                    // Existing upload, try to resume
                    uploadStatus = uploadRepository.getUploadById(uploadId);
                    if (uploadStatus == null) {
                        // Should not happen if ID is valid, but handle defensively
                        Log.e("UploadService", "UploadStatus with ID " + uploadId + " not found. Starting new upload.");
                        uploadStatus = new UploadStatus(gameName, fileName, fileSize, accessKey, secretKey, itemIdentifier, fileUriString);
                        // Potentially add uploadDestinationService to UploadStatus constructor or setter if it needs to be persisted
                        long newId = uploadRepository.insertUpload(uploadStatus);
                        uploadStatus.setId((int) newId);
                    } else {
                        // Update status to UPLOADING if it was PAUSED or ERROR
                        if (uploadStatus.getStatus() == UploadStatus.Status.PAUSED || uploadStatus.getStatus() == UploadStatus.Status.ERROR) {
                            uploadStatus.setStatus(UploadStatus.Status.UPLOADING);
                            uploadRepository.updateUpload(uploadStatus);
                        }
                        // Here you could also update the stored credentials if they changed, though it's complex.
                        // For now, we use the fresh ones from the intent.
                    }
                } else {
                    // New upload
                    uploadStatus = new UploadStatus(gameName, fileName, fileSize, accessKey, secretKey, itemIdentifier, fileUriString);
                     // Potentially add uploadDestinationService to UploadStatus here too
                    long newId = uploadRepository.insertUpload(uploadStatus);
                    uploadStatus.setId((int) newId);
                }

                sendUploadBroadcast(ACTION_UPLOAD_STARTED, uploadStatus.getId(), gameName, fileName, fileSize, uploadStatus.getProgress(), null, uploadStatus.getUploadedBytes());

                // Pass all relevant keys and the destination service to uploadGame
                uploadGame(uploadStatus, accessKey, secretKey, itemIdentifier, datanodesApiKey, finalUploadDestinationService, Uri.parse(fileUriString));
            });
        }
        return START_NOT_STICKY;
    }

    private void uploadGame(UploadStatus uploadStatus, String iaAccessKey, String iaSecretKey, String iaItemIdentifier,
                           String dnApiKey, String destinationService, Uri fileUri) {
        if (wakeLock != null && !wakeLock.isHeld()) {
            Log.d("UploadService", "Acquiring WakeLock for upload: " + uploadStatus.getGameName());
            wakeLock.acquire();
        }

        InputStream inputStreamForUpload = null;
        try {
            updateNotification("Enviando " + uploadStatus.getGameName() + "...", uploadStatus.getProgress());
            Log.d("UploadService", "Using URI stream for upload: " + fileUri.toString());
            inputStreamForUpload = getContentResolver().openInputStream(fileUri);
            if (inputStreamForUpload == null) {
                throw new IOException("Não foi possível abrir InputStream para URI: " + fileUri.toString());
            }

            // Handle resuming upload by skipping bytes from the input stream if previously uploaded bytes > 0.
            // This is generic for both uploaders as it manipulates the common input stream.
            if (uploadStatus.getUploadedBytes() > 0) {
                long actualSkipped = inputStreamForUpload.skip(uploadStatus.getUploadedBytes());
                if (actualSkipped != uploadStatus.getUploadedBytes()) {
                    // If skipping fails or doesn't skip the full amount, log a warning and restart the upload from the beginning.
                    Log.w("UploadService", "Falha ao pular bytes para resumo. Esperado: " + uploadStatus.getUploadedBytes() + ", pulado: " + actualSkipped + ". Reiniciando upload do URI para " + destinationService);
                    try { inputStreamForUpload.close(); } catch (IOException e) { Log.e("UploadService", "Error closing stream on partial skip URI", e); }
                    inputStreamForUpload = getContentResolver().openInputStream(fileUri); // Re-open the stream
                    if (inputStreamForUpload == null) {
                         throw new IOException("Não foi possível reabrir InputStream para URI após falha no skip: " + fileUri.toString());
                    }
                    // Reset persisted progress as we are starting over for this attempt.
                    uploadStatus.setUploadedBytes(0);
                    uploadStatus.setProgress(0);
                    uploadRepository.updateUpload(uploadStatus);
                } else {
                    Log.d("UploadService", "Resumed upload from byte " + actualSkipped + " for " + destinationService);
                }
            }

            // Make the inputStream final so it can be accessed within anonymous callback classes.
            final InputStream finalInputStreamForUpload = inputStreamForUpload;

            // Generic callback handler object.
            // This object's methods (onProgress, onSuccess, onError) are called by the specific uploader callbacks.
            // This allows common logic for updating notifications, database, and broadcasts.
            Object genericCallback = new Object() { // Effectively an anonymous class defining the callback structure.
                /**
                 * Handles progress updates from either uploader.
                 * @param newUploadedBytes Absolute number of bytes uploaded for the file.
                 * @param overallProgress Overall percentage progress for the file.
                 */
                public void onProgress(long newUploadedBytes, int overallProgress) {
                    uploadStatus.setUploadedBytes(newUploadedBytes);
                    uploadStatus.setProgress(overallProgress);
                    uploadStatus.setStatus(UploadStatus.Status.UPLOADING);
                    uploadRepository.updateUpload(uploadStatus);

                    String notificationText = "Enviando " + uploadStatus.getGameName() + "...";
                    // Customize notification text for Datanodes when it reaches 100% as it might not have a separate verification step.
                    if (overallProgress == 100 && DATANODES_SERVICE_ID.equals(destinationService)) {
                         notificationText = "Finalizando upload de " + uploadStatus.getGameName() + "...";
                    } else if (overallProgress == 100) { // For Internet Archive, 100% means verification starts.
                        notificationText = "Verificando upload de " + uploadStatus.getGameName() + "...";
                    }
                    updateNotification(notificationText, overallProgress);
                    sendUploadBroadcast(ACTION_UPLOAD_PROGRESS, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), overallProgress, null, newUploadedBytes);
                }

                /**
                 * Handles successful upload from either uploader.
                 * @param fileUrl The URL of the successfully uploaded file.
                 */
                public void onSuccess(String fileUrl) {
                    try { if (finalInputStreamForUpload != null) finalInputStreamForUpload.close(); } catch (java.io.IOException e) { android.util.Log.e("UploadService", "Error closing stream in onSuccess", e); }
                    sendToPhpApi(uploadStatus, fileUrl); // Proceed to register the game with the backend.
                }

                /**
                 * Handles errors from either uploader.
                 * @param error The error message.
                 */
                public void onError(String error) {
                    try { if (finalInputStreamForUpload != null) finalInputStreamForUpload.close(); } catch (java.io.IOException e) { android.util.Log.e("UploadService", "Error closing stream in onError", e); }
                    Log.e("UploadService", "Erro no upload (" + destinationService + ") de " + uploadStatus.getGameName() + ": " + error);
                    String errorMsg = "Erro no upload ("+destinationService+") de " + uploadStatus.getGameName() + ": " + error; // Include service in user-facing error
                    showErrorNotification(errorMsg);
                    uploadStatus.setStatus(UploadStatus.Status.ERROR);
                    uploadStatus.setErrorMessage(errorMsg); // Store specific error
                    uploadRepository.updateUpload(uploadStatus);
                    sendUploadBroadcast(ACTION_UPLOAD_ERROR, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), 0, errorMsg, uploadStatus.getUploadedBytes());
                    stopSelf(); // Stop the service on error.
                }
            };

            // Conditional instantiation and use of the uploader based on `destinationService`.
            if (DATANODES_SERVICE_ID.equals(destinationService)) {
                // Upload to Datanodes.to
                Log.d("UploadService", "Starting upload to Datanodes.to for: " + uploadStatus.getGameName());
                // Check for Datanodes API key presence.
                if (dnApiKey == null || dnApiKey.isEmpty()) {
                    throw new IllegalArgumentException("Datanodes.to API Key is missing.");
                }
                DatanodesUploader datanodesUploader = new DatanodesUploader(dnApiKey);
                // `initialUploadedBytesForDatanodes` is the amount already skipped from the stream.
                final long initialUploadedBytesForDatanodes = uploadStatus.getUploadedBytes();

                datanodesUploader.uploadFile(
                    finalInputStreamForUpload, // The stream, potentially advanced by skip()
                    uploadStatus.getFileSize(), // Total file size
                    uploadStatus.getFileName(),
                    // Specific callback for DatanodesUploader
                    new DatanodesUploader.UploadCallback() {
                        @Override
                        public void onProgress(long uploadedBytesFromCallback, int progressFromCallback) {
                            // `uploadedBytesFromCallback` from DatanodesUploader is the total bytes processed in its current attempt
                            // (i.e., since its `uploadFile` was called, after the initial skip).
                            // `progressFromCallback` is the percentage of this current attempt.
                            // To get the absolute uploaded bytes for the file, add `initialUploadedBytesForDatanodes`.
                            long absoluteUploadedBytes = initialUploadedBytesForDatanodes + uploadedBytesFromCallback;
                            // Calculate overall progress based on the absolute uploaded bytes and total file size.
                            int overallProgress = (int) ((absoluteUploadedBytes * 100) / uploadStatus.getFileSize());
                            // Call the generic progress handler.
                            ((Object{public void onProgress(long l,int i); public void onSuccess(String s); public void onError(String s);})genericCallback).onProgress(absoluteUploadedBytes, overallProgress);
                        }
                        @Override
                        public void onSuccess(String fileUrl) {
                            ((Object{public void onProgress(long l,int i); public void onSuccess(String s); public void onError(String s);})genericCallback).onSuccess(fileUrl);
                        }
                        @Override
                        public void onError(String error) {
                             ((Object{public void onProgress(long l,int i); public void onSuccess(String s); public void onError(String s);})genericCallback).onError(error);
                        }
                    }
                );

            } else { // Default to Internet Archive
                Log.d("UploadService", "Starting upload to Internet Archive for: " + uploadStatus.getGameName());
                // Check for Internet Archive credential presence.
                if (iaAccessKey == null || iaAccessKey.isEmpty() || iaSecretKey == null || iaSecretKey.isEmpty() || iaItemIdentifier == null || iaItemIdentifier.isEmpty()) {
                     throw new IllegalArgumentException("Internet Archive credentials (access key, secret key, or item identifier) are missing.");
                }
                InternetArchiveUploader internetArchiveUploader = new InternetArchiveUploader(iaAccessKey, iaSecretKey, iaItemIdentifier);
                // `streamStartOffsetForIA` is the number of bytes already uploaded in previous attempts (i.e., already skipped from the stream).
                // This is crucial for IA's resumable upload mechanism.
                final long streamStartOffsetForIA = uploadStatus.getUploadedBytes();

                internetArchiveUploader.uploadFile(
                    finalInputStreamForUpload, // The stream, potentially advanced by skip()
                    uploadStatus.getFileSize(), // Total file size
                    uploadStatus.getFileName(),
                    null, // md5Hash is not used in this setup
                    streamStartOffsetForIA, // Offset from which IA should start/consider this chunk
                    // Specific callback for InternetArchiveUploader
                    new InternetArchiveUploader.UploadCallback() {
                         @Override
                        public void onProgress(long uploadedBytesInChunk, int progressOfChunk) {
                            // `uploadedBytesInChunk` from IA Uploader is the number of bytes uploaded *in the current chunk/call*.
                            // `progressOfChunk` is the percentage of this current chunk.
                            // To get the absolute total bytes uploaded for the file, add `streamStartOffsetForIA`.
                            long absoluteUploadedBytes = streamStartOffsetForIA + uploadedBytesInChunk;
                            // Calculate overall progress based on the absolute uploaded bytes.
                            int overallProgress = (int) ((absoluteUploadedBytes * 100) / uploadStatus.getFileSize());
                            // Call the generic progress handler.
                            ((Object{public void onProgress(long l,int i); public void onSuccess(String s); public void onError(String s);})genericCallback).onProgress(absoluteUploadedBytes, overallProgress);
                        }
                        @Override
                        public void onSuccess(String fileUrl) {
                            ((Object{public void onProgress(long l,int i); public void onSuccess(String s); public void onError(String s);})genericCallback).onSuccess(fileUrl);
                        }
                        @Override
                        public void onError(String error) {
                            ((Object{public void onProgress(long l,int i); public void onSuccess(String s); public void onError(String s);})genericCallback).onError(error);
                        }
                    }
                );
            }

        } catch (Throwable t) { // Catch any other Throwables (includes Exceptions and Errors)
            Log.e("UploadService", "Erro geral EXCEPTION/THROWABLE no upload de " + uploadStatus.getGameName() + " para " + destinationService + ": " + t.getMessage(), t);
            String errorMsgForUser = "Erro crítico no upload (" + destinationService + ") de " + uploadStatus.getGameName() + ": " + t.getMessage();
            showErrorNotification(errorMsgForUser); // Show error notification to user
            // Update status in database
            uploadStatus.setStatus(UploadStatus.Status.ERROR);
            uploadStatus.setErrorMessage(errorMsgForUser);
            uploadRepository.updateUpload(uploadStatus);
            // Send error broadcast
            sendUploadBroadcast(ACTION_UPLOAD_ERROR, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), 0, errorMsgForUser, uploadStatus.getUploadedBytes());

            // Ensure stream is closed on error
            if (inputStreamForUpload != null) {
                try {
                    inputStreamForUpload.close();
                } catch (java.io.IOException e) {
                    android.util.Log.e("UploadService", "Error closing inputStreamForUpload in main catch block", e);
                }
            }
            stopSelf(); // Stop the service on critical error.
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                Log.d("UploadService", "Releasing WakeLock for upload: " + uploadStatus.getGameName());
                wakeLock.release();
            }
        }
    }

    private String getFilePathFromDownloadUri(Context context, Uri uri) {
        if (uri == null || !"com.android.providers.downloads.documents".equals(uri.getAuthority())) {
            return null;
        }

        Cursor cursor = null;
        final String column = "_data"; // MediaStore.Downloads.COLUMN_DATA
        final String[] projection = { column };
        String path = null;

        String id = null;
        if (uri.getPath() != null) {
            String[] pathSegments = uri.getPath().split(":");
            if (pathSegments.length > 1) {
                id = pathSegments[pathSegments.length - 1];
            } else {
                pathSegments = uri.getPath().split("/");
                if (pathSegments.length > 0) {
                    id = pathSegments[pathSegments.length - 1];
                }
            }
        }

        if (id == null || !android.text.TextUtils.isDigitsOnly(id)) {
             // android.util.Log.w("UploadService", "Download URI ID is not simple numeric, direct path query might fail: " + id);
        }

        if (id != null) {
            try {
                Uri contentUri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                 if (android.text.TextUtils.isDigitsOnly(id)) {
                    cursor = context.getContentResolver().query(contentUri, projection, "_id=?", new String[]{id}, null);
                 } else {
                    android.util.Log.w("UploadService", "Non-numeric ID for Download URI, _id query unlikely to work: " + id);
                 }

                if (cursor != null && cursor.moveToFirst()) {
                    int columnIndex = cursor.getColumnIndexOrThrow(column);
                    path = cursor.getString(columnIndex);
                }
            } catch (Exception e) {
                android.util.Log.e("UploadService", "Error querying MediaStore for Download URI path", e);
                path = null;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        if (path == null && "file".equalsIgnoreCase(uri.getScheme())) {
            path = uri.getPath();
        }

        return path;
    }

    // copyUriToTempFileInternal method removed
    // calculateMd5FromFile method removed
    // calculateMd5FromUri method removed

    private void sendToPhpApi(UploadStatus uploadStatus, String gameUrl) { // File tempFile parameter removed
        try {
            updateNotification("Registrando arquivo " + uploadStatus.getGameName() + "...", 100);

            JSONObject jsonData = new JSONObject();
            jsonData.put("name", uploadStatus.getGameName());
            jsonData.put("size", formatFileSize(uploadStatus.getFileSize()));
            jsonData.put("url", gameUrl);

            URL url = new URL("https://ldgames.x10.mx/add_update_game.php");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            OutputStream outputStream = connection.getOutputStream();
            outputStream.write(jsonData.toString().getBytes());
            outputStream.close();

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject responseJson = new JSONObject(response.toString());
                if (responseJson.getBoolean("success")) {
                    String successMsg = "Upload de " + uploadStatus.getGameName() + " concluído com sucesso!";
                    showSuccessNotification(successMsg);
                    uploadStatus.setStatus(UploadStatus.Status.COMPLETED);
                    uploadStatus.setProgress(100);
                    uploadRepository.updateUpload(uploadStatus);
                    sendUploadBroadcast(ACTION_UPLOAD_COMPLETED, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), 100, null, uploadStatus.getUploadedBytes());
                } else {
                    String error = "Erro na API para " + uploadStatus.getGameName() + ": " + responseJson.getString("message");
                    showErrorNotification(error);
                    uploadStatus.setStatus(UploadStatus.Status.ERROR);
                    uploadStatus.setErrorMessage(error);
                    uploadRepository.updateUpload(uploadStatus);
                    sendUploadBroadcast(ACTION_UPLOAD_ERROR, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), 0, responseJson.getString("message"), uploadStatus.getUploadedBytes());
                }
            } else {
                String error = "Erro na API para " + uploadStatus.getGameName() + ": Código " + responseCode;
                showErrorNotification(error);
                uploadStatus.setStatus(UploadStatus.Status.ERROR);
                uploadStatus.setErrorMessage(error);
                uploadRepository.updateUpload(uploadStatus);
                sendUploadBroadcast(ACTION_UPLOAD_ERROR, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), 0, "Código de erro: " + responseCode, uploadStatus.getUploadedBytes());
            }

            connection.disconnect();
            // tempFile.delete(); // Removed

        } catch (Exception e) {
            Log.e("UploadService", "Erro na API PHP para " + uploadStatus.getGameName() + ": " + e.getMessage());
            String error = "Erro na API para " + uploadStatus.getGameName() + ": " + e.getMessage();
            showErrorNotification(error);
            uploadStatus.setStatus(UploadStatus.Status.ERROR);
            uploadStatus.setErrorMessage(error);
            uploadRepository.updateUpload(uploadStatus);
            sendUploadBroadcast(ACTION_UPLOAD_ERROR, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), 0, e.getMessage(), uploadStatus.getUploadedBytes());
            // tempFile.delete(); // Removed
        }

        stopSelf();
    }

    private void sendUploadBroadcast(String action, int id, String gameName, String fileName, long fileSize, int progress, String error, long uploadedBytes) {
        Intent broadcast = new Intent(action);
        broadcast.putExtra("upload_id", id);
        broadcast.putExtra("game_name", gameName);
        broadcast.putExtra("file_name", fileName);
        broadcast.putExtra("file_size", fileSize);
        broadcast.putExtra("progress", progress);
        broadcast.putExtra("uploaded_bytes", uploadedBytes);
        if (error != null) {
            broadcast.putExtra("error", error);
        }

        if (ACTION_UPLOAD_PROGRESS.equals(action)) {
            Log.d("UploadService", "Sending UPLOAD_PROGRESS: id=" + id + ", game=" + gameName + ", progress=" + progress + ", uploadedBytes=" + uploadedBytes);
        } else if (ACTION_UPLOAD_STARTED.equals(action)) {
            Log.d("UploadService", "Sending UPLOAD_STARTED: id=" + id + ", game=" + gameName + ", file=" + fileName + ", size=" + fileSize);
        }
        sendBroadcast(broadcast);
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
        return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Upload de Jogos",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Notificações de upload de jogos da comunidade");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String text, int progress) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Upload de Jogo")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true);

        if (progress > 0 && progress < 100) {
            builder.setProgress(100, progress, false);
        }

        return builder.build();
    }

    private void updateNotification(String text, int progress) {
        Notification notification = createNotification(text, progress);
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private void showSuccessNotification(String message) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Upload Concluído")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build();

        notificationManager.notify(NOTIFICATION_ID + 1, notification);
    }

    private void showErrorNotification(String error) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Erro no Upload")
            .setContentText(error)
            .setSmallIcon(R.drawable.ic_cancel)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build();

        notificationManager.notify(NOTIFICATION_ID + 2, notification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdownNow();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}

