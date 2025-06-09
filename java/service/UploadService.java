package com.winlator.Download.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
            String accessKey = intent.getStringExtra("access_key");
            String secretKey = intent.getStringExtra("secret_key");
            String itemIdentifier = intent.getStringExtra("item_identifier");
            String fileUriString = intent.getStringExtra("file_uri");
            String fileName = intent.getStringExtra("file_name");
            long fileSize = intent.getLongExtra("file_size", 0);
            int uploadId = intent.getIntExtra("upload_id", -1); // -1 if new upload

            String currentProcessGameName = gameName;
            if (currentProcessGameName == null || currentProcessGameName.isEmpty()) {
                currentProcessGameName = "Upload";
            }

            Notification preparingOrInitialNotification = createNotification("Preparando " + currentProcessGameName + "...", 0);
            startForeground(NOTIFICATION_ID, preparingOrInitialNotification);

            executor.execute(() -> {
                UploadStatus uploadStatus;
                if (uploadId != -1) {
                    // Existing upload, try to resume
                    uploadStatus = uploadRepository.getUploadById(uploadId);
                    if (uploadStatus == null) {
                        // Should not happen if ID is valid, but handle defensively
                        Log.e("UploadService", "UploadStatus with ID " + uploadId + " not found. Starting new upload.");
                        uploadStatus = new UploadStatus(gameName, fileName, fileSize, accessKey, secretKey, itemIdentifier, fileUriString);
                        long newId = uploadRepository.insertUpload(uploadStatus);
                        uploadStatus.setId((int) newId);
                    } else {
                        // Update status to UPLOADING if it was PAUSED or ERROR
                        if (uploadStatus.getStatus() == UploadStatus.Status.PAUSED || uploadStatus.getStatus() == UploadStatus.Status.ERROR) {
                            uploadStatus.setStatus(UploadStatus.Status.UPLOADING);
                            uploadRepository.updateUpload(uploadStatus);
                        }
                    }
                } else {
                    // New upload
                    uploadStatus = new UploadStatus(gameName, fileName, fileSize, accessKey, secretKey, itemIdentifier, fileUriString);
                    long newId = uploadRepository.insertUpload(uploadStatus);
                    uploadStatus.setId((int) newId);
                }

                // Send initial broadcast for the UI to pick up
                sendUploadBroadcast(ACTION_UPLOAD_STARTED, uploadStatus.getId(), gameName, fileName, fileSize, uploadStatus.getProgress(), null, uploadStatus.getUploadedBytes());

                uploadGame(uploadStatus, accessKey, secretKey, itemIdentifier, Uri.parse(fileUriString));
            });
        }

        return START_NOT_STICKY;
    }

    private void uploadGame(UploadStatus uploadStatus, String accessKey, String secretKey,
                           String itemIdentifier, Uri fileUri) {
        if (wakeLock != null && !wakeLock.isHeld()) {
            Log.d("UploadService", "Acquiring WakeLock for upload: " + uploadStatus.getGameName());
            wakeLock.acquire();
        }

        String filePath = null;
        if ("com.android.providers.downloads.documents".equals(fileUri.getAuthority())) {
            filePath = getFilePathFromDownloadUri(this, fileUri);
            if (filePath != null) {
                 android.util.Log.i("UploadService", "Obtained direct file path for Download URI: " + filePath);
            } else {
                 android.util.Log.w("UploadService", "Failed to obtain direct file path for Download URI: " + fileUri.toString() + ". Will attempt copy-to-temp fallback.");
            }
        }

        // filePath, md5Hash, and tempFileForDeletionHolder related logic removed.
        InputStream inputStreamForUpload = null;

        try {
            // Initial notification for the sending phase
            updateNotification("Enviando " + uploadStatus.getGameName() + "...", uploadStatus.getProgress());

            Log.d("UploadService", "Using URI stream for upload: " + fileUri.toString());
            inputStreamForUpload = getContentResolver().openInputStream(fileUri);
            if (inputStreamForUpload == null) {
                throw new IOException("Não foi possível abrir InputStream para URI: " + fileUri.toString());
            }

            if (uploadStatus.getUploadedBytes() > 0) {
                long actualSkipped = inputStreamForUpload.skip(uploadStatus.getUploadedBytes());
                if (actualSkipped != uploadStatus.getUploadedBytes()) {
                    Log.w("UploadService", "Falha ao pular bytes para resumo. Esperado: " + uploadStatus.getUploadedBytes() + ", pulado: " + actualSkipped + ". Reiniciando upload do URI.");
                    try { inputStreamForUpload.close(); } catch (IOException e) { Log.e("UploadService", "Error closing stream on partial skip URI", e); }
                    inputStreamForUpload = getContentResolver().openInputStream(fileUri); // Re-open
                    if (inputStreamForUpload == null) {
                         throw new IOException("Não foi possível reabrir InputStream para URI após falha no skip: " + fileUri.toString());
                    }
                    uploadStatus.setUploadedBytes(0);
                    uploadStatus.setProgress(0);
                    uploadRepository.updateUpload(uploadStatus); // Persist reset
                }
            }

            final InternetArchiveUploader uploader = new InternetArchiveUploader(accessKey, secretKey, itemIdentifier);
            final InputStream finalInputStreamForUpload = inputStreamForUpload; // To be used in callbacks

            uploader.uploadFile(
                finalInputStreamForUpload,
                uploadStatus.getFileSize(),
                uploadStatus.getFileName(),
                null, // md5Hash is explicitly null
                uploadStatus.getUploadedBytes(), // streamStartOffset
                new InternetArchiveUploader.UploadCallback() {
                    @Override
                    public void onProgress(long uploadedBytes, int progress) {
                        uploadStatus.setUploadedBytes(uploadedBytes);
                        uploadStatus.setProgress(progress);
                        uploadStatus.setStatus(UploadStatus.Status.UPLOADING);
                        uploadRepository.updateUpload(uploadStatus);
                        String notificationText = "Enviando " + uploadStatus.getGameName() + "...";
                        if (progress == 100) {
                            notificationText = "Verificando upload de " + uploadStatus.getGameName() + "...";
                        }
                        updateNotification(notificationText, progress);
                        sendUploadBroadcast(ACTION_UPLOAD_PROGRESS, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), progress, null, uploadedBytes);
                    }

                    @Override
                    public void onSuccess(String fileUrl) {
                        try { if (finalInputStreamForUpload != null) finalInputStreamForUpload.close(); } catch (java.io.IOException e) { android.util.Log.e("UploadService", "Error closing stream in onSuccess", e); }
                        // No temp file to delete here in this simplified path
                        sendToPhpApi(uploadStatus, fileUrl);
                    }

                    @Override
                    public void onError(String error) {
                        try { if (finalInputStreamForUpload != null) finalInputStreamForUpload.close(); } catch (java.io.IOException e) { android.util.Log.e("UploadService", "Error closing stream in onError", e); }
                        // No temp file to delete here in this simplified path
                        Log.e("UploadService", "Erro no upload (uploader.uploadFile callback) de " + uploadStatus.getGameName() + ": " + error);
                        String errorMsg = "Erro no upload de " + uploadStatus.getGameName() + ": " + error;
                    showErrorNotification(errorMsg);
                    uploadStatus.setStatus(UploadStatus.Status.ERROR);
                    uploadStatus.setErrorMessage(errorMsg);
                    uploadRepository.updateUpload(uploadStatus);
                    sendUploadBroadcast(ACTION_UPLOAD_ERROR, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), 0, errorMsg, uploadStatus.getUploadedBytes());
                    stopSelf();
                }
            });

        } catch (Throwable t) {
            Log.e("UploadService", "Erro geral EXCEPTION/THROWABLE no upload de " + uploadStatus.getGameName() + ": " + t.getMessage(), t);
            String errorMsgForUser = "Erro crítico no upload de " + uploadStatus.getGameName() + ": " + t.getMessage();
            showErrorNotification(errorMsgForUser);
            uploadStatus.setStatus(UploadStatus.Status.ERROR);
                    uploadStatus.setErrorMessage(errorMsgForUser);
            uploadRepository.updateUpload(uploadStatus);
            sendUploadBroadcast(ACTION_UPLOAD_ERROR, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), 0, errorMsgForUser, uploadStatus.getUploadedBytes());

            if (inputStreamForUpload != null) {
                try {
                    inputStreamForUpload.close();
                } catch (java.io.IOException e) {
                    android.util.Log.e("UploadService", "Error closing inputStreamForUpload in main catch block", e);
                }
            }
            // No temp file deletion holder to check here in this simplified path
            stopSelf();
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

