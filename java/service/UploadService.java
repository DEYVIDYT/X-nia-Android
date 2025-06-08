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
import java.io.File;
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

    @FunctionalInterface
    interface Md5ProgressListener {
        void onProgress(int progress);
    }

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

        // File tempFile = null; // Removed tempFile logic

        try {
            // Phase 1: Calculate MD5
            updateNotification("Calculando hash de " + uploadStatus.getGameName() + "...", 0);
            String md5Hash;
            try {
                md5Hash = calculateMd5FromUri(fileUri, uploadStatus.getFileSize(), uploadStatus.getGameName(), progress -> {
                    updateNotification("Calculando hash de " + uploadStatus.getGameName() + "...", progress);
                });

                if (md5Hash == null) {
                    String error = "Erro ao calcular MD5 para " + uploadStatus.getGameName() + " (hash nulo)";
                    Log.e("UploadService", error);
                    showErrorNotification(error);
                    uploadStatus.setStatus(UploadStatus.Status.ERROR);
                    uploadStatus.setErrorMessage(error);
                    uploadRepository.updateUpload(uploadStatus);
                    sendUploadBroadcast(ACTION_UPLOAD_ERROR, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), 0, error, uploadStatus.getUploadedBytes());
                    stopSelf(); // Stop service if MD5 calculation fails critically
                    return;
                }
            } catch (Exception e) {
                String error = "Erro ao calcular MD5 para " + uploadStatus.getGameName() + ": " + e.getMessage();
                Log.e("UploadService", error, e);
                showErrorNotification(error);
                uploadStatus.setStatus(UploadStatus.Status.ERROR);
                uploadStatus.setErrorMessage(error);
                uploadRepository.updateUpload(uploadStatus);
                sendUploadBroadcast(ACTION_UPLOAD_ERROR, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), 0, error, uploadStatus.getUploadedBytes());
                stopSelf(); // Stop service if MD5 calculation fails
                return;
            }

            // Phase 2: Upload the file
            InternetArchiveUploader uploader = new InternetArchiveUploader(accessKey, secretKey, itemIdentifier);
            // final File finalTempFile = tempFile; // Removed

            InputStream inputStream = null;
            try {
                inputStream = getContentResolver().openInputStream(fileUri);
                if (inputStream == null) {
                    throw new IOException("Não foi possível abrir InputStream para URI: " + fileUri);
                }

                if (uploadStatus.getUploadedBytes() > 0) {
                    long skipped = inputStream.skip(uploadStatus.getUploadedBytes());
                    if (skipped < uploadStatus.getUploadedBytes()) {
                        Log.w("UploadService", "Não foi possível pular todos os bytes para " + uploadStatus.getGameName() + ". Solicitado: " + uploadStatus.getUploadedBytes() + ", Pulado: " + skipped + ". Reiniciando upload.");
                        // Optionally, reset uploadedBytes and restart the upload from scratch
                        uploadStatus.setUploadedBytes(0);
                        uploadStatus.setProgress(0);
                        // Re-open stream if necessary or seek to 0 if supported, though Content URI streams usually don't support seek.
                        // For simplicity here, we might just let it proceed and upload from where skip landed, or fail.
                        // A more robust solution might involve restarting the upload from byte 0 if skip fails significantly.
                        // For now, we'll log and proceed, which might lead to issues if the skip was incomplete.
                        // Or better, fail:
                        // throw new IOException("Falha ao pular bytes para retomar o upload de " + uploadStatus.getGameName());
                        // For this iteration, let's assume skip works or upload needs to restart if it doesn't.
                        // If skip is not exact, it's safer to restart or fail. Let's restart for now.
                        inputStream.close(); // Close the current stream
                        inputStream = getContentResolver().openInputStream(fileUri); // Reopen
                        uploadStatus.setUploadedBytes(0); // Reset progress
                        uploadStatus.setProgress(0);
                        uploadRepository.updateUpload(uploadStatus); // Persist reset
                        Log.i("UploadService", "Upload de " + uploadStatus.getGameName() + " reiniciado devido a falha no skip.");

                    }
                }

                final InputStream finalInputStream = inputStream; // For use in callback
                uploader.uploadFile(finalInputStream, uploadStatus.getFileSize(), uploadStatus.getFileName(), md5Hash, uploadStatus.getUploadedBytes(), new InternetArchiveUploader.UploadCallback() {
                    @Override
                    public void onProgress(long uploadedBytes, int progress) {
                        uploadStatus.setUploadedBytes(uploadedBytes);
                        uploadStatus.setProgress(progress);
                        uploadStatus.setStatus(UploadStatus.Status.UPLOADING);
                        uploadRepository.updateUpload(uploadStatus);
                        updateNotification("Enviando " + uploadStatus.getGameName() + "...", progress);
                        sendUploadBroadcast(ACTION_UPLOAD_PROGRESS, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), progress, null, uploadedBytes);
                    }

                    @Override
                    public void onSuccess(String fileUrl) {
                        // sendToPhpApi(uploadStatus, fileUrl, finalTempFile); // finalTempFile removed
                        sendToPhpApi(uploadStatus, fileUrl);
                    }

                    @Override
                    public void onError(String error) {
                        Log.e("UploadService", "Erro no upload (uploader.uploadFile callback) de " + uploadStatus.getGameName() + ": " + error);
                        String errorMsg = "Erro no upload de " + uploadStatus.getGameName() + ": " + error;
                        showErrorNotification(errorMsg);
                        uploadStatus.setStatus(UploadStatus.Status.ERROR);
                        uploadStatus.setErrorMessage(errorMsg);
                        uploadRepository.updateUpload(uploadStatus);
                        sendUploadBroadcast(ACTION_UPLOAD_ERROR, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), 0, errorMsg, uploadStatus.getUploadedBytes());
                        // if (finalTempFile != null && finalTempFile.exists()) { // Removed
                        //    finalTempFile.delete();
                        // }
                        stopSelf();
                    }
                });
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e) {
                        Log.e("UploadService", "Erro ao fechar inputStream para " + uploadStatus.getGameName() + ": " + e.getMessage(), e);
                    }
                }
            }

        } catch (Throwable t) { // This outer catch handles MD5 calculation exceptions and InputStream opening issues before uploader.uploadFile
            Log.e("UploadService", "Erro geral EXCEPTION/THROWABLE no upload de " + uploadStatus.getGameName() + ": " + t.getMessage(), t);
            String error = "Erro crítico no upload de " + uploadStatus.getGameName() + ": " + t.getMessage();
            showErrorNotification(error);
            uploadStatus.setStatus(UploadStatus.Status.ERROR);
            uploadStatus.setErrorMessage(error);
            uploadRepository.updateUpload(uploadStatus);
            sendUploadBroadcast(ACTION_UPLOAD_ERROR, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), 0, error, uploadStatus.getUploadedBytes());
            // if (tempFile != null && tempFile.exists()) { // Removed
            //    tempFile.delete();
            // }
            stopSelf();
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                Log.d("UploadService", "Releasing WakeLock for upload: " + uploadStatus.getGameName());
                wakeLock.release();
            }
        }
    }

    // Removed copyUriToTempFile method

    private String calculateMd5FromUri(Uri fileUri, long fileSize, String gameName, Md5ProgressListener listener) throws Exception {
        if (fileSize == 0) {
            Log.w("UploadService", "File size is 0 for " + gameName + ", cannot calculate MD5 meaningfully. Returning empty hash.");
            // Consider a specific non-null hash for zero-byte files if your backend expects one,
            // or ensure this case is handled as an error before calling.
            // For now, returning a common MD5 for empty string: "d41d8cd98f00b204e9800998ecf8427e"
            // This might not be what Internet Archive expects for an empty file's Content-MD5.
            // It's safer to prevent uploads of 0-byte files or have a specific handling for them.
            // For this implementation, let's return null and let the caller handle it as an error.
            // listener.onProgress(100); // Or 0, depending on desired UX for 0-byte files
            return null; // Or throw new IllegalArgumentException("File size is 0");
        }

        InputStream inputStream = null;
        try {
            inputStream = getContentResolver().openInputStream(fileUri);
            if (inputStream == null) {
                throw new IOException("Não foi possível abrir InputStream para URI: " + fileUri);
            }

            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                if (fileSize > 0) { // Avoid division by zero if somehow fileSize was 0 despite earlier check
                    int progress = (int) ((totalBytesRead * 100) / fileSize);
                    listener.onProgress(progress);
                }
            }
            byte[] digest = md.digest();
            return Base64.encodeToString(digest, Base64.NO_WRAP);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    Log.e("UploadService", "Erro ao fechar inputStream durante cálculo de MD5 para " + gameName + ": " + e.getMessage());
                }
            }
        }
    }


    private void sendToPhpApi(UploadStatus uploadStatus, String gameUrl) { // File tempFile parameter removed
        try {
            updateNotification("Finalizando upload de " + uploadStatus.getGameName() + "...", 95);

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

