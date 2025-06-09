package com.winlator.Download.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.winlator.Download.utils.DatanodesUploader;
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

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
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
import android.util.Base64;
import android.database.Cursor;
import android.provider.MediaStore;

public class UploadService extends Service {

    private interface UnifiedUploadCallback {
        void onProgress(long totalUploadedBytes, int overallProgress);
        void onSuccess(String fileUrl);
        void onError(String error);
    }

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
    private UnifiedUploadCallback masterCallbackHandler;

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
            String accessKey = intent.getStringExtra("access_key");
            String secretKey = intent.getStringExtra("secret_key");
            String itemIdentifier = intent.getStringExtra("item_identifier");
            String datanodesApiKey = intent.getStringExtra("datanodes_api_key");

            String fileUriString = intent.getStringExtra("file_uri");
            String fileName = intent.getStringExtra("file_name");
            long fileSize = intent.getLongExtra("file_size", 0);
            int uploadId = intent.getIntExtra("upload_id", -1);

            String uploadDestinationService = intent.getStringExtra("upload_destination_service");
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

            final String finalUploadDestinationService = uploadDestinationService;

            executor.execute(() -> {
                UploadStatus currentUploadStatus;
                if (uploadId != -1) {
                    currentUploadStatus = uploadRepository.getUploadById(uploadId);
                    if (currentUploadStatus == null) {
                        Log.e("UploadService", "UploadStatus with ID " + uploadId + " not found. Starting new upload.");
                        currentUploadStatus = new UploadStatus(gameName, fileName, fileSize, accessKey, secretKey, itemIdentifier, fileUriString);
                        long newId = uploadRepository.insertUpload(currentUploadStatus);
                        currentUploadStatus.setId((int) newId);
                    } else {
                        if (currentUploadStatus.getStatus() == UploadStatus.Status.PAUSED || currentUploadStatus.getStatus() == UploadStatus.Status.ERROR) {
                            currentUploadStatus.setStatus(UploadStatus.Status.UPLOADING);
                            uploadRepository.updateUpload(currentUploadStatus);
                        }
                    }
                } else {
                    currentUploadStatus = new UploadStatus(gameName, fileName, fileSize, accessKey, secretKey, itemIdentifier, fileUriString);
                    long newId = uploadRepository.insertUpload(currentUploadStatus);
                    currentUploadStatus.setId((int) newId);
                }

                final UploadStatus effectivelyFinalUploadStatus = currentUploadStatus;

                this.masterCallbackHandler = new UnifiedUploadCallback() {
                    @Override
                    public void onProgress(long totalUploadedBytes, int overallProgress) {
                        effectivelyFinalUploadStatus.setUploadedBytes(totalUploadedBytes);
                        effectivelyFinalUploadStatus.setProgress(overallProgress);
                        effectivelyFinalUploadStatus.setStatus(UploadStatus.Status.UPLOADING);
                        uploadRepository.updateUpload(effectivelyFinalUploadStatus);

                        String notificationText = "Enviando " + effectivelyFinalUploadStatus.getGameName() + "...";
                        if (overallProgress == 100 && DATANODES_SERVICE_ID.equals(finalUploadDestinationService)) {
                             notificationText = "Finalizando upload de " + effectivelyFinalUploadStatus.getGameName() + "...";
                        } else if (overallProgress == 100) {
                            notificationText = "Verificando upload de " + effectivelyFinalUploadStatus.getGameName() + "...";
                        }
                        updateNotification(notificationText, overallProgress);
                        sendUploadBroadcast(ACTION_UPLOAD_PROGRESS, effectivelyFinalUploadStatus.getId(), effectivelyFinalUploadStatus.getGameName(), effectivelyFinalUploadStatus.getFileName(), effectivelyFinalUploadStatus.getFileSize(), overallProgress, null, totalUploadedBytes);
                    }

                    @Override
                    public void onSuccess(String fileUrl) {
                        sendToPhpApi(effectivelyFinalUploadStatus, fileUrl);
                    }

                    @Override
                    public void onError(String error) {
                        Log.e("UploadService", "Erro no upload (" + finalUploadDestinationService + ") de " + effectivelyFinalUploadStatus.getGameName() + ": " + error);
                        String errorMsg = "Erro no upload ("+finalUploadDestinationService+") de " + effectivelyFinalUploadStatus.getGameName() + ": " + error;
                        showErrorNotification(errorMsg);
                        effectivelyFinalUploadStatus.setStatus(UploadStatus.Status.ERROR);
                        effectivelyFinalUploadStatus.setErrorMessage(errorMsg);
                        uploadRepository.updateUpload(effectivelyFinalUploadStatus);
                        sendUploadBroadcast(ACTION_UPLOAD_ERROR, effectivelyFinalUploadStatus.getId(), effectivelyFinalUploadStatus.getGameName(), effectivelyFinalUploadStatus.getFileName(), effectivelyFinalUploadStatus.getFileSize(), 0, errorMsg, effectivelyFinalUploadStatus.getUploadedBytes());
                        stopSelf();
                    }
                };

                sendUploadBroadcast(ACTION_UPLOAD_STARTED, effectivelyFinalUploadStatus.getId(), gameName, fileName, fileSize, effectivelyFinalUploadStatus.getProgress(), null, effectivelyFinalUploadStatus.getUploadedBytes());
                uploadGame(effectivelyFinalUploadStatus, accessKey, secretKey, itemIdentifier, datanodesApiKey, finalUploadDestinationService, Uri.parse(fileUriString));
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

        InputStream streamToUpload = null; // Renamed for clarity
        final long initialBytesSkipped = uploadStatus.getUploadedBytes(); // Bytes already uploaded before this attempt

        try {
            updateNotification("Enviando " + uploadStatus.getGameName() + "...", uploadStatus.getProgress());
            Log.d("UploadService", "Using URI stream for upload: " + fileUri.toString());
            streamToUpload = getContentResolver().openInputStream(fileUri);
            if (streamToUpload == null) {
                throw new IOException("Não foi possível abrir InputStream para URI: " + fileUri.toString());
            }

            if (initialBytesSkipped > 0) {
                long actualSkipped = streamToUpload.skip(initialBytesSkipped);
                if (actualSkipped != initialBytesSkipped) {
                    Log.w("UploadService", "Falha ao pular bytes para resumo. Esperado: " + initialBytesSkipped + ", pulado: " + actualSkipped + ". Reiniciando upload.");
                    try { streamToUpload.close(); } catch (IOException e) { Log.e("UploadService", "Error closing stream on partial skip", e); }
                    streamToUpload = getContentResolver().openInputStream(fileUri);
                    if (streamToUpload == null) {
                         throw new IOException("Não foi possível reabrir InputStream para URI após falha no skip: " + fileUri.toString());
                    }
                    // Reset persisted progress as we are starting over for this attempt.
                    if (masterCallbackHandler != null) { // Update progress via master callback
                        masterCallbackHandler.onProgress(0, 0);
                    } else { // Fallback if masterCallbackHandler is null (should not happen)
                        uploadStatus.setUploadedBytes(0);
                        uploadStatus.setProgress(0);
                        uploadRepository.updateUpload(uploadStatus);
                    }
                } else {
                    Log.d("UploadService", "Resumed upload from byte " + actualSkipped + " for " + destinationService);
                }
            }

            // Ensure masterCallbackHandler is not null before proceeding
            if (this.masterCallbackHandler == null) {
                throw new IllegalStateException("masterCallbackHandler is not initialized.");
            }

            final InputStream finalStreamToUpload = streamToUpload; // Make it final for use in anonymous classes

            if (DATANODES_SERVICE_ID.equals(destinationService)) {
                Log.d("UploadService", "Starting upload to Datanodes.to for: " + uploadStatus.getGameName());
                if (dnApiKey == null || dnApiKey.isEmpty()) {
                    throw new IllegalArgumentException("Datanodes.to API Key is missing.");
                }
                DatanodesUploader datanodesUploader = new DatanodesUploader(dnApiKey);
                datanodesUploader.uploadFile(
                    finalStreamToUpload,
                    uploadStatus.getFileSize(),
                    uploadStatus.getFileName(),
                    new DatanodesUploader.UploadCallback() {
                        @Override
                        public void onProgress(long uploadedBytesFromDatanodes, int progressFromDatanodes) {
                            long totalUploadedBytes = initialBytesSkipped + uploadedBytesFromDatanodes;
                            int overallProgress = (uploadStatus.getFileSize() > 0) ? (int) ((totalUploadedBytes * 100) / uploadStatus.getFileSize()) : progressFromDatanodes;
                            masterCallbackHandler.onProgress(totalUploadedBytes, overallProgress);
                        }
                        @Override
                        public void onSuccess(String fileUrl) {
                            masterCallbackHandler.onSuccess(fileUrl);
                        }
                        @Override
                        public void onError(String error) {
                            masterCallbackHandler.onError(error);
                        }
                    }
                );

            } else { // Default to Internet Archive
                Log.d("UploadService", "Starting upload to Internet Archive for: " + uploadStatus.getGameName());
                if (iaAccessKey == null || iaAccessKey.isEmpty() || iaSecretKey == null || iaSecretKey.isEmpty() || iaItemIdentifier == null || iaItemIdentifier.isEmpty()) {
                     throw new IllegalArgumentException("Internet Archive credentials are missing.");
                }
                InternetArchiveUploader internetArchiveUploader = new InternetArchiveUploader(iaAccessKey, iaSecretKey, iaItemIdentifier);
                internetArchiveUploader.uploadFile(
                    finalStreamToUpload,
                    uploadStatus.getFileSize(),
                    uploadStatus.getFileName(),
                    null,
                    initialBytesSkipped, // This is the streamStartOffset for IA
                    new InternetArchiveUploader.UploadCallback() {
                         @Override
                        public void onProgress(long newTotalUploadedBytesByIA, int progressReportedByIA) {
                            // InternetArchiveUploader's onProgress 'uploadedBytes' is already the total for the file
                            masterCallbackHandler.onProgress(newTotalUploadedBytesByIA, progressReportedByIA);
                        }
                        @Override
                        public void onSuccess(String fileUrl) {
                            masterCallbackHandler.onSuccess(fileUrl);
                        }
                        @Override
                        public void onError(String error) {
                            masterCallbackHandler.onError(error);
                        }
                    }
                );
            }

        } catch (Throwable t) {
            Log.e("UploadService", "Critical error in uploadGame for " + destinationService + ": " + t.getMessage(), t);
            String errorMsgForUser = "Erro crítico no upload (" + destinationService + "): " + t.getMessage();
            if (this.masterCallbackHandler != null) {
                this.masterCallbackHandler.onError(errorMsgForUser);
            } else { // Fallback if masterCallbackHandler somehow null
                showErrorNotification(errorMsgForUser);
                uploadStatus.setStatus(UploadStatus.Status.ERROR);
                uploadStatus.setErrorMessage(errorMsgForUser);
                uploadRepository.updateUpload(uploadStatus);
                sendUploadBroadcast(ACTION_UPLOAD_ERROR, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), 0, errorMsgForUser, uploadStatus.getUploadedBytes());
                stopSelf();
            }
        } finally {
            // Ensure the input stream is closed if it was opened.
            // The uploader callbacks (onSuccess/onError) are now responsible for signaling completion,
            // and the masterCallbackHandler's onSuccess/onError might trigger service stop or other actions.
            // Closing the stream here ensures it's closed even if an exception occurs before uploader starts
            // or if the uploader itself doesn't close it.
            if (streamToUpload != null) {
                try {
                    streamToUpload.close();
                    Log.d("UploadService", "InputStream closed in uploadGame finally block for " + destinationService);
                } catch (IOException e) {
                    Log.e("UploadService", "Error closing InputStream in uploadGame finally block for " + destinationService, e);
                }
            }
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
        final String column = "_data";
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
        }

        if (id != null) {
            try {
                Uri contentUri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                 if (android.text.TextUtils.isDigitsOnly(id)) {
                    cursor = context.getContentResolver().query(contentUri, projection, "_id=?", new String[]{id}, null);
                 } else {
                    Log.w("UploadService", "Non-numeric ID for Download URI, _id query unlikely to work: " + id);
                 }

                if (cursor != null && cursor.moveToFirst()) {
                    int columnIndex = cursor.getColumnIndexOrThrow(column);
                    path = cursor.getString(columnIndex);
                }
            } catch (Exception e) {
                Log.e("UploadService", "Error querying MediaStore for Download URI path", e);
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

    private void sendToPhpApi(UploadStatus uploadStatus, String gameUrl) {
        // This method is called from masterCallbackHandler.onSuccess
        // Stream closure should have been handled before this point or by the uploader.
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
                    stopSelf(); // Stop service on full completion
                } else {
                    String error = "Erro na API (PHP) para " + uploadStatus.getGameName() + ": " + responseJson.getString("message");
                    // Use masterCallbackHandler for consistency, though it will call stopSelf again.
                    if (this.masterCallbackHandler != null) this.masterCallbackHandler.onError(error);
                    else { /* Should not happen if masterCallbackHandler is always initialized */ }
                }
            } else {
                String error = "Erro na API (PHP) para " + uploadStatus.getGameName() + ": Código " + responseCode;
                if (this.masterCallbackHandler != null) this.masterCallbackHandler.onError(error);
                else { /* Should not happen */ }
            }
            connection.disconnect();
        } catch (Exception e) {
            Log.e("UploadService", "Erro na API PHP para " + uploadStatus.getGameName() + ": " + e.getMessage());
            String error = "Erro durante comunicação com API (PHP) para " + uploadStatus.getGameName() + ": " + e.getMessage();
            if (this.masterCallbackHandler != null) this.masterCallbackHandler.onError(error);
            else { /* Should not happen */ }
        }
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
