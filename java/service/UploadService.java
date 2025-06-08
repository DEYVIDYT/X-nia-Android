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
import com.winlator.Download.utils.InternetArchiveUploader;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UploadService extends Service {

    private static final String CHANNEL_ID = "upload_channel";
    private static final int NOTIFICATION_ID = 1001;
    private NotificationManager notificationManager;
    private ExecutorService executor;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        executor = Executors.newSingleThreadExecutor();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UploadService::UploadWakelockTag");
            wakeLock.setReferenceCounted(false); // Optional: manage acquire/release counts carefully if true
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

            // Ensure gameName is available for the preparing notification title
            String currentProcessGameName = gameName; // Already extracted
            if (currentProcessGameName == null || currentProcessGameName.isEmpty()) {
                currentProcessGameName = "Upload"; // Default title for the very first notification
            }

            // Create and show a temporary "Preparing" notification IMMEDIATELY
            // Use the main NOTIFICATION_ID, it will be updated by uploadGame.
            Notification preparingOrInitialNotification = createNotification("Preparando " + currentProcessGameName + "...", 0); // Pass 0 for initial progress
            startForeground(NOTIFICATION_ID, preparingOrInitialNotification);

            // Enviar broadcast de início do upload
            sendUploadBroadcast("UPLOAD_STARTED", gameName, fileName, fileSize, 0, null);

            executor.execute(() -> {
                uploadGame(gameName, accessKey, secretKey, itemIdentifier,
                          Uri.parse(fileUriString), fileName, fileSize);
            });
        }

        return START_NOT_STICKY;
    }

    private void uploadGame(String gameName, String accessKey, String secretKey,
                           String itemIdentifier, Uri fileUri, String fileName, long fileSize) {
        // Acquire WakeLock if initialized
        if (wakeLock != null && !wakeLock.isHeld()) {
            Log.d("UploadService", "Acquiring WakeLock for upload: " + gameName);
            wakeLock.acquire(); // Consider a timeout for the wakelock if appropriate: wakeLock.acquire(timeout_ms);
        }

        File tempFile = null; // Define tempFile here to be accessible in finally block if needed for deletion on new error path

        try {
            // This part remains largely the same, but now inside the broader try
            updateNotification("Preparando arquivo " + gameName + "...", 5);
            tempFile = copyUriToTempFile(fileUri, fileName); // Assign to the outer scope tempFile

            if (tempFile == null) {
                String error = "Erro ao preparar arquivo para " + gameName;
                Log.e("UploadService", error + " (tempFile is null)");
                showErrorNotification(error);
                sendUploadBroadcast("UPLOAD_ERROR", gameName, fileName, fileSize, 0, error);
                // No stopSelf() here, let finally block handle wakelock and service lifecycle is managed by callbacks or stopSelf in onError
                return; // Return here as tempFile is null, further operations would fail.
            }

            InternetArchiveUploader uploader = new InternetArchiveUploader(accessKey, secretKey, itemIdentifier);
            final File finalTempFile = tempFile; // Effectively final for lambda

            uploader.uploadFile(finalTempFile, fileName, new InternetArchiveUploader.UploadCallback() {
                @Override
                public void onProgress(int progress) {
                    updateNotification("Enviando " + gameName + "...", progress);
                    sendUploadBroadcast("UPLOAD_PROGRESS", gameName, fileName, fileSize, progress, null);
                }

                @Override
                public void onSuccess(String fileUrl) {
                    sendToPhpApi(gameName, formatFileSize(fileSize), fileUrl, finalTempFile, fileName, fileSize);
                    // stopSelf() is called within sendToPhpApi or its error paths
                }

                @Override
                public void onError(String error) {
                    Log.e("UploadService", "Erro no upload (uploader.uploadFile callback) de " + gameName + ": " + error);
                    String errorMsg = "Erro no upload de " + gameName + ": " + error;
                    showErrorNotification(errorMsg);
                    sendUploadBroadcast("UPLOAD_ERROR", gameName, fileName, fileSize, 0, error);
                    if (finalTempFile != null && finalTempFile.exists()) {
                        finalTempFile.delete();
                    }
                    stopSelf(); // Stop service on uploader error
                }
            });

        } catch (Throwable t) { // Catch Throwable for more robust error handling
            Log.e("UploadService", "Erro geral EXCEPTION/THROWABLE no upload de " + gameName + ": " + t.getMessage(), t);
            String error = "Erro crítico no upload de " + gameName + ": " + t.getMessage();
            showErrorNotification(error);
            // Use original fileName and fileSize for broadcast if available, otherwise pass empty/0
            sendUploadBroadcast("UPLOAD_ERROR", gameName, (fileName != null ? fileName : ""), fileSize, 0, error);
            if (tempFile != null && tempFile.exists()) { // Clean up tempFile if it was created
                tempFile.delete();
            }
            stopSelf(); // Stop service on critical error
        } finally {
            // Release WakeLock if held
            if (wakeLock != null && wakeLock.isHeld()) {
                Log.d("UploadService", "Releasing WakeLock for upload: " + gameName);
                wakeLock.release();
            }
            // Note: stopSelf() is called in specific error/success paths within the try or callbacks.
            // If an operation completes without calling stopSelf (e.g. uploader.uploadFile starts and will call stopSelf in its callback),
            // the service continues running, which is correct.
        }
    }

    private File copyUriToTempFile(Uri uri, String fileName) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) return null;

            File tempFile = new File(getCacheDir(), fileName);
            FileOutputStream outputStream = new FileOutputStream(tempFile);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            inputStream.close();
            outputStream.close();

            return tempFile;
        } catch (Exception e) {
            Log.e("UploadService", "Erro ao copiar arquivo: " + e.getMessage());
            return null;
        }
    }

    private void sendToPhpApi(String gameName, String gameSize, String gameUrl, File tempFile, String fileName, long fileSize) {
        try {
            updateNotification("Finalizando upload de " + gameName + "...", 95);

            JSONObject jsonData = new JSONObject();
            jsonData.put("name", gameName);
            jsonData.put("size", gameSize);
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
                    String successMsg = "Upload de " + gameName + " concluído com sucesso!";
                    showSuccessNotification(successMsg);
                    sendUploadBroadcast("UPLOAD_COMPLETED", gameName, fileName, fileSize, 100, null);
                } else {
                    String error = "Erro na API para " + gameName + ": " + responseJson.getString("message");
                    showErrorNotification(error);
                    sendUploadBroadcast("UPLOAD_ERROR", gameName, fileName, fileSize, 0, responseJson.getString("message"));
                }
            } else {
                String error = "Erro na API para " + gameName + ": Código " + responseCode;
                showErrorNotification(error);
                sendUploadBroadcast("UPLOAD_ERROR", gameName, fileName, fileSize, 0, "Código de erro: " + responseCode);
            }

            connection.disconnect();
            tempFile.delete();

        } catch (Exception e) {
            Log.e("UploadService", "Erro na API PHP para " + gameName + ": " + e.getMessage());
            String error = "Erro na API para " + gameName + ": " + e.getMessage();
            showErrorNotification(error);
            sendUploadBroadcast("UPLOAD_ERROR", gameName, fileName, fileSize, 0, e.getMessage());
            tempFile.delete();
        }

        stopSelf();
    }

    private void sendUploadBroadcast(String action, String gameName, String fileName, long fileSize, int progress, String error) {
        Intent broadcast = new Intent(action);
        broadcast.putExtra("game_name", gameName);
        broadcast.putExtra("file_name", fileName);
        broadcast.putExtra("file_size", fileSize);
        broadcast.putExtra("progress", progress);
        if (error != null) {
            broadcast.putExtra("error", error);
        }

        if ("UPLOAD_PROGRESS".equals(action)) {
            Log.d("UploadService", "Sending UPLOAD_PROGRESS: game=" + gameName + ", progress=" + progress);
        } else if ("UPLOAD_STARTED".equals(action)) {
            Log.d("UploadService", "Sending UPLOAD_STARTED: game=" + gameName + ", file=" + fileName + ", size=" + fileSize);
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
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            Log.w("UploadService", "Releasing WakeLock in onDestroy as it was still held.");
            wakeLock.release();
        }
    }
}

