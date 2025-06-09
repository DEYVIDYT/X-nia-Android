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

        String md5Hash = null;
        InputStream inputStreamForUpload = null;
        final File[] tempFileForDeletionHolder = new File[1];

        try {
            if (filePath != null) {
                File file = new File(filePath);
                if (!file.exists() || !file.canRead()) {
                    throw new java.io.FileNotFoundException("File path obtained but file does not exist or cannot be read: " + filePath);
                }
                updateNotification("Calculando hash de " + uploadStatus.getGameName() + "...", 0);
                md5Hash = calculateMd5FromFile(file, uploadStatus.getFileSize(), uploadStatus.getGameName(), progress -> {
                    updateNotification("Calculando hash de " + uploadStatus.getGameName() + "...", progress);
                });
                inputStreamForUpload = new java.io.FileInputStream(file);
                if (uploadStatus.getUploadedBytes() > 0) {
                    long skipped = ((java.io.FileInputStream) inputStreamForUpload).skip(uploadStatus.getUploadedBytes());
                    if (skipped != uploadStatus.getUploadedBytes()) {
                        try { inputStreamForUpload.close(); } catch (java.io.IOException e) { Log.e("UploadService", "Error closing stream on partial skip for file path", e); }
                        android.util.Log.w("UploadService", "Partial skip on FileInputStream. Restarting stream for: " + file.getName());
                        inputStreamForUpload = new java.io.FileInputStream(file);
                        uploadStatus.setUploadedBytes(0);
                        uploadStatus.setProgress(0);
                        uploadRepository.updateUpload(uploadStatus);
                        Log.i("UploadService", "Upload de " + uploadStatus.getGameName() + " reiniciado devido a falha no skip (FileInputStream).");
                    }
                }
            } else { // filePath is NULL
                if ("com.android.providers.downloads.documents".equals(fileUri.getAuthority())) {
                    android.util.Log.w("UploadService", "Download URI: No direct path, falling back to copy-to-temp-file for: " + fileUri.toString());
                    File tempFile = copyUriToTempFileInternal(fileUri, uploadStatus.getFileName());
                    if (tempFile == null) {
                        throw new Exception("Falha ao copiar arquivo (Downloads) para cache temporário. TempFile é null.");
                    }
                    tempFileForDeletionHolder[0] = tempFile;

                    updateNotification("Calculando hash (copia) de " + uploadStatus.getGameName() + "...", 0);
                    md5Hash = calculateMd5FromFile(tempFile, tempFile.length(), uploadStatus.getGameName(), progress -> {
                        updateNotification("Calculando hash (copia) de " + uploadStatus.getGameName() + "...", progress);
                    });
                    inputStreamForUpload = new java.io.FileInputStream(tempFile);
                    if (uploadStatus.getUploadedBytes() > 0) {
                        long skipped = ((java.io.FileInputStream) inputStreamForUpload).skip(uploadStatus.getUploadedBytes());
                        if (skipped != uploadStatus.getUploadedBytes()) {
                            try { inputStreamForUpload.close(); } catch (java.io.IOException e) { /* ignore */ }
                            inputStreamForUpload = new java.io.FileInputStream(tempFile);
                            uploadStatus.setUploadedBytes(0);
                            uploadStatus.setProgress(0);
                            uploadRepository.updateUpload(uploadStatus);
                            Log.i("UploadService", "Upload de " + uploadStatus.getGameName() + " (temp copy) reiniciado devido a falha no skip.");
                        }
                    }
                } else {
                    // Not a Download URI, and filePath was null. Use original URI streaming logic
                    android.util.Log.i("UploadService", "Non-Download URI or no authority (and no direct path): Using URI stream read for: " + fileUri.toString());
                    updateNotification("Calculando hash de " + uploadStatus.getGameName() + "...", 0);
                    md5Hash = calculateMd5FromUri(fileUri, uploadStatus.getFileSize(), uploadStatus.getGameName(), progress -> {
                        updateNotification("Calculando hash de " + uploadStatus.getGameName() + "...", progress);
                    });
                    inputStreamForUpload = getContentResolver().openInputStream(fileUri);
                     if (inputStreamForUpload == null) {
                        throw new IOException("Não foi possível abrir InputStream para URI (não-Downloads): " + fileUri.toString());
                    }
                    if (uploadStatus.getUploadedBytes() > 0) {
                        long skipped = inputStreamForUpload.skip(uploadStatus.getUploadedBytes());
                        if (skipped != uploadStatus.getUploadedBytes()) {
                              try { inputStreamForUpload.close(); } catch (java.io.IOException e) { /* ignore */ }
                              inputStreamForUpload = getContentResolver().openInputStream(fileUri);
                               if (inputStreamForUpload == null) {
                                  throw new IOException("Não foi possível reabrir InputStream para URI (não-Downloads) após falha no skip: " + fileUri.toString());
                              }
                              uploadStatus.setUploadedBytes(0);
                              uploadStatus.setProgress(0);
                              uploadRepository.updateUpload(uploadStatus);
                              Log.i("UploadService", "Upload de " + uploadStatus.getGameName() + " (URI Stream non-download) reiniciado devido a falha no skip.");
                        }
                    }
                }
            }

            if (md5Hash == null) {
                String errorMsg = "Erro ao calcular MD5 para " + uploadStatus.getGameName() + " (hash nulo resultante)";
                Log.e("UploadService", errorMsg);
                showErrorNotification(errorMsg);
                uploadStatus.setStatus(UploadStatus.Status.ERROR);
                uploadStatus.setErrorMessage(errorMsg);
                uploadRepository.updateUpload(uploadStatus);
                sendUploadBroadcast(ACTION_UPLOAD_ERROR, uploadStatus.getId(), uploadStatus.getGameName(), uploadStatus.getFileName(), uploadStatus.getFileSize(), 0, errorMsg, uploadStatus.getUploadedBytes());
                if (inputStreamForUpload != null) try { inputStreamForUpload.close(); } catch (IOException e) { Log.e("UploadService", "Error closing stream early on MD5 null", e); }
                if (tempFileForDeletionHolder[0] != null) { if (tempFileForDeletionHolder[0].delete()) { Log.d("UploadService", "Temp file deleted on MD5 null."); } tempFileForDeletionHolder[0] = null; }
                stopSelf();
                return;
            }

            final InternetArchiveUploader uploader = new InternetArchiveUploader(accessKey, secretKey, itemIdentifier);
            final InputStream finalInputStreamForUpload = inputStreamForUpload;

            uploader.uploadFile(finalInputStreamForUpload, uploadStatus.getFileSize(), uploadStatus.getFileName(), md5Hash, uploadStatus.getUploadedBytes(), new InternetArchiveUploader.UploadCallback() {
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
                    if (tempFileForDeletionHolder[0] != null) {
                        android.util.Log.d("UploadService", "Attempting to delete temp file in onSuccess: " + tempFileForDeletionHolder[0].getPath());
                        if (!tempFileForDeletionHolder[0].delete()) {
                            android.util.Log.w("UploadService", "Failed to delete temp file in onSuccess: " + tempFileForDeletionHolder[0].getPath());
                        }
                        tempFileForDeletionHolder[0] = null;
                    }
                    sendToPhpApi(uploadStatus, fileUrl);
                }

                @Override
                public void onError(String error) {
                    try { if (finalInputStreamForUpload != null) finalInputStreamForUpload.close(); } catch (java.io.IOException e) { android.util.Log.e("UploadService", "Error closing stream in onError", e); }
                    if (tempFileForDeletionHolder[0] != null) {
                        android.util.Log.d("UploadService", "Attempting to delete temp file on error: " + tempFileForDeletionHolder[0].getPath());
                        if (!tempFileForDeletionHolder[0].delete()) {
                            android.util.Log.w("UploadService", "Failed to delete temp file on error: " + tempFileForDeletionHolder[0].getPath());
                        }
                        tempFileForDeletionHolder[0] = null;
                    }
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
            if (tempFileForDeletionHolder[0] != null) {
                android.util.Log.d("UploadService", "Attempting to delete temp file in main catch: " + tempFileForDeletionHolder[0].getPath());
                if (!tempFileForDeletionHolder[0].delete()) {
                    android.util.Log.w("UploadService", "Failed to delete temp file in main catch: " + tempFileForDeletionHolder[0].getPath());
                }
                tempFileForDeletionHolder[0] = null;
            }
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

    @FunctionalInterface
    interface Md5ProgressListener {
        void onProgress(int progress);
    }

    private String getFilePathFromDownloadUri(Context context, Uri uri) {
        if (uri == null) return null;
        String authority = uri.getAuthority();
        if (!"com.android.providers.downloads.documents".equals(authority) && !"com.android.externalstorage.documents".equals(authority)) { // Added externalstorage check
            // Also check if it's a direct file URI from a modern file picker
            if ("com.android.providers.media.documents".equals(authority)) {
                 String docId = android.provider.DocumentsContract.getDocumentId(uri);
                 String[] split = docId.split(":");
                 String type = split[0];
                 Uri contentUri = null;
                 if ("image".equals(type)) {
                     contentUri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                 } else if ("video".equals(type)) {
                     contentUri = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                 } else if ("audio".equals(type)) {
                     contentUri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                 } else if ("document".equals(type)) { // For general files
                    contentUri = android.provider.MediaStore.Files.getContentUri("external");
                 }


                 if (contentUri != null) {
                     String selection = "_id=?";
                     String[] selectionArgs = new String[]{ split[1] };
                     return getDataColumn(context, contentUri, selection, selectionArgs);
                 }
            } else if ("file".equalsIgnoreCase(uri.getScheme())) {
                 return uri.getPath();
            }
            return null; // Not a recognized URI for direct path extraction via this method
        }

        // For com.android.providers.downloads.documents
        Cursor cursor = null;
        final String column = "_data"; // MediaStore.Downloads.COLUMN_DATA or OpenableColumns.DISPLAY_NAME
        final String[] projection = { column };
        String path = null;
        String id = null;

        // Try to get ID from URI
        if (uri.getPath() != null) {
            // Example: content://com.android.providers.downloads.documents/document/msf%3A1234
            // Example: content://com.android.providers.downloads.documents/document/1234
            // Example: content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Ftest.zip (for some devices)
            String docId = android.provider.DocumentsContract.getDocumentId(uri); // Use this helper
            if (docId != null) {
                if (docId.startsWith("raw:")) {
                    path = docId.substring(4); // Remove "raw:" prefix
                    if (new File(path).exists()) return path;
                    else path = null; // Reset if path from "raw:" doesn't exist
                } else if (android.text.TextUtils.isDigitsOnly(docId)) {
                    id = docId;
                } else if (docId.contains(":")) { // For "msf:XXX" or similar
                    id = docId.substring(docId.lastIndexOf(':') + 1);
                     if (!android.text.TextUtils.isDigitsOnly(id)){
                        id = null; // If it's not numeric after "msf:", this query won't work
                     }
                }
            }
        }

        if (id != null && android.text.TextUtils.isDigitsOnly(id)) {
            try {
                Uri contentUri = android.content.ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));
                cursor = context.getContentResolver().query(contentUri, projection, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    final int columnIndex = cursor.getColumnIndexOrThrow(column);
                    path = cursor.getString(columnIndex);
                }
            } catch (Exception e) {
                android.util.Log.e("UploadService", "Error querying MediaStore for Download URI path (ID: " + id + ")", e);
                path = null;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        // If path is still null and URI scheme is file (less likely for SAF)
        if (path == null && "file".equalsIgnoreCase(uri.getScheme())) {
            path = uri.getPath();
        }

        // If path is still null, try to get it from display name (less reliable but a fallback)
        if (path == null) {
            try {
                cursor = context.getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    String displayName = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME));
                    if (displayName != null) {
                        File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                        File potentialFile = new File(downloadsDir, displayName);
                        if (potentialFile.exists()) {
                            path = potentialFile.getAbsolutePath();
                        }
                    }
                }
            } catch (Exception e) {
                 android.util.Log.e("UploadService", "Error querying display name for Download URI path", e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return path;
    }

    // Helper for getFilePathFromDownloadUri
    private String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = { column };
        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                final int index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(index);
            }
        } catch (Exception e) {
            android.util.Log.e("UploadService", "Error in getDataColumn for URI: " + uri, e);
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return null;
    }

    private File copyUriToTempFileInternal(Uri uri, String suggestedFileName) throws java.io.IOException {
        InputStream inputStream = null;
        FileOutputStream outputStream = null;
        File tempFile = null;
        try {
            inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                throw new java.io.IOException("Failed to open InputStream from URI: " + uri);
            }

            String fileNamePrefix = "upload_temp_";
            String actualFileName = (suggestedFileName != null && !suggestedFileName.isEmpty()) ? suggestedFileName : "file.tmp";
            // Sanitize filename
            actualFileName = actualFileName.replaceAll("[^a-zA-Z0-9._-]", "_");


            tempFile = new File(getCacheDir(), fileNamePrefix + System.currentTimeMillis() + "_" + actualFileName);

            outputStream = new FileOutputStream(tempFile);

            byte[] buffer = new byte[16384]; // Increased buffer size
            int bytesRead;
            // long totalBytesCopied = 0; // For progress if needed
            // long fileSize = -1; // Potentially get file size for copy progress
            // try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            //    if (cursor != null && cursor.moveToFirst()) {
            //        int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
            //        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex);
            //    }
            // }

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                // totalBytesCopied += bytesRead;
                // if (fileSize > 0 && progressListener != null) {
                //    progressListener.onProgress((int) ((totalBytesCopied * 100) / fileSize));
                // }
            }
            Log.d("UploadService", "Copied to temp file: " + tempFile.getAbsolutePath() + " size: " + tempFile.length());
            return tempFile;
        } finally {
            try {
                if (inputStream != null) inputStream.close();
            } catch (java.io.IOException e) {
                android.util.Log.e("UploadService", "Error closing InputStream in copyUriToTempFileInternal", e);
            }
            try {
                if (outputStream != null) outputStream.close();
            } catch (java.io.IOException e) {
                android.util.Log.e("UploadService", "Error closing OutputStream in copyUriToTempFileInternal", e);
            }
        }
    }

    private String calculateMd5FromFile(File file, long fileSize, String gameName, Md5ProgressListener listener) throws Exception {
        Log.d("UploadService", "Calculating MD5 for file: " + file.getPath() + " Size: " + fileSize);
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = 0;

            while ((bytesRead = fis.read(buffer)) != -1) {
                if (bytesRead == 0) {
                    continue;
                }
                md.update(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                if (listener != null) {
                    if (fileSize > 0) {
                        int progress = (int) ((totalBytesRead * 100) / fileSize);
                        listener.onProgress(progress);
                    } else if (fileSize == 0) {
                        listener.onProgress(100);
                    }
                }
            }

            byte[] digest = md.digest();
            String md5HashResult = Base64.encodeToString(digest, Base64.NO_WRAP);
            Log.d("UploadService", "MD5 for " + file.getName() + ": " + md5HashResult);
            return md5HashResult;
        } catch (Exception e) {
            Log.e("UploadService", "Error calculating MD5 from file: " + file.getPath(), e);
            throw e;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    Log.e("UploadService", "Error closing FileInputStream for MD5 calculation: " + file.getPath(), e);
                }
            }
        }
    }

    private String calculateMd5FromUri(Uri fileUri, long fileSize, String gameName, Md5ProgressListener listener) throws Exception {
        Log.d("UploadService", "Calculating MD5 for URI: " + fileUri.toString() + " Size: " + fileSize);
        if (fileSize == 0) {
            Log.w("UploadService", "File size is 0 for " + gameName + ". Returning empty string hash or handling as error.");
            // For a 0-byte file, an MD5 hash is typically of an empty input.
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(); // Hash of empty
            return Base64.encodeToString(digest, Base64.NO_WRAP);
        }

        InputStream inputStream = null;
        try {
            inputStream = getContentResolver().openInputStream(fileUri);
            if (inputStream == null) {
                throw new IOException("Não foi possível abrir InputStream para URI: " + fileUri.toString());
            }

            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                if (bytesRead == 0) continue;
                md.update(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                if (listener != null) {
                    if (fileSize > 0) {
                        int progress = (int) ((totalBytesRead * 100) / fileSize);
                        listener.onProgress(progress);
                    }
                }
            }
            byte[] digest = md.digest();
            String md5HashResult = Base64.encodeToString(digest, Base64.NO_WRAP);
            Log.d("UploadService", "MD5 for URI " + gameName + ": " + md5HashResult);
            return md5HashResult;
        } catch (Exception e) {
            Log.e("UploadService", "Error calculating MD5 from URI: " + fileUri.toString(), e);
            throw e;
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

