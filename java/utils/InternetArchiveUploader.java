package com.winlator.Download.utils;

import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class InternetArchiveUploader {

    private String accessKey;
    private String secretKey;
    private String itemIdentifier;

    public InternetArchiveUploader(String accessKey, String secretKey, String itemIdentifier) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.itemIdentifier = itemIdentifier;
    }

    public interface UploadCallback {
        void onProgress(long uploadedBytes, int progress);
        void onSuccess(String fileUrl);
        void onError(String error);
    }

    // Restoring precalculatedMd5 parameter for optional MD5
    public void uploadFile(InputStream inputStream, long fileSize, String fileName, String precalculatedMd5, long streamStartOffset, UploadCallback callback) {
        try {
            String bucketName = itemIdentifier;
            String objectKey = fileName;

            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
            String timestamp = dateFormat.format(new Date());

            // MD5 is now precalculated
            // String md5Hash = calculateMD5(file);

            // Conditionally include MD5 in stringToSign
            String md5ForSigning = (precalculatedMd5 != null && !precalculatedMd5.isEmpty()) ? precalculatedMd5 : "";
            String stringToSign = "PUT\n" +
                                  md5ForSigning + "\n" + // Use md5ForSigning (empty if precalculatedMd5 is null/empty)
                                  "application/octet-stream\n" +
                                  timestamp + "\n" +
                                  "/" + bucketName + "/" + objectKey;
            String signature = generateSignature(stringToSign, secretKey);
            String uploadUrl = "https://s3.us.archive.org/" + bucketName + "/" + objectKey;

            URL url = new URL(uploadUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "LOW " + accessKey + ":" + signature);
            connection.setRequestProperty("Date", timestamp);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            // Conditionally set Content-MD5 header
            if (precalculatedMd5 != null && !precalculatedMd5.isEmpty()) {
                connection.setRequestProperty("Content-MD5", precalculatedMd5);
            }

            if (streamStartOffset > 0) {
                connection.setRequestProperty("Content-Range", "bytes " + streamStartOffset + "-" + (fileSize - 1) + "/" + fileSize);
                connection.setRequestProperty("Content-Length", String.valueOf(fileSize - streamStartOffset));
            } else {
                connection.setRequestProperty("Content-Length", String.valueOf(fileSize));
            }

            long contentLengthForStreaming;
            if (streamStartOffset > 0) {
                contentLengthForStreaming = fileSize - streamStartOffset;
            } else {
                contentLengthForStreaming = fileSize;
            }
            // Ensure contentLengthForStreaming is not negative, which could happen if fileSize or streamStartOffset is incorrect.
            // Though Content-Length header above would also be problematic in that case.
            if (contentLengthForStreaming < 0) {
                 contentLengthForStreaming = 0; // Or handle as an error, but for setFixedLengthStreamingMode, non-negative is needed.
            }
            connection.setFixedLengthStreamingMode(contentLengthForStreaming);

            DataOutputStream dos = new DataOutputStream(connection.getOutputStream());
            // FileInputStream fileInputStream = new FileInputStream(file); // Replaced by inputStream parameter
            // fileInputStream.skip(startByte); // streamStartOffset is assumed to be handled by caller

            byte[] buffer = new byte[8192]; // Or a suitable buffer size
            int bytesRead;
            long totalBytesReadForThisUploadSession = 0; // For progress within this specific upload call
            
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
                totalBytesReadForThisUploadSession += bytesRead;
                
                // Progress calculation should use 'fileSize' (total size of the file)
                // and 'streamStartOffset + totalBytesReadForThisUploadSession' (total bytes effectively uploaded)
                int progress = (int) (((streamStartOffset + totalBytesReadForThisUploadSession) * 100) / fileSize);
                callback.onProgress(streamStartOffset + totalBytesReadForThisUploadSession, progress);
            }
            
            // inputStream.close(); // Closing the stream should be the responsibility of the caller
            dos.flush();
            dos.close();

            int responseCode = connection.getResponseCode();
            if (responseCode == 200 || responseCode == 201) {
                String fileUrl = "https://archive.org/download/" + bucketName + "/" + objectKey;
                callback.onSuccess(fileUrl);
            } else {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    errorResponse.append(line);
                }
                reader.close();

                String fullErrorText = errorResponse.toString();
                int maxLogLength = 1000;
                String loggedErrorText;

                if (fullErrorText.length() > maxLogLength) {
                    loggedErrorText = fullErrorText.substring(0, maxLogLength) + "... (truncated)";
                } else {
                    loggedErrorText = fullErrorText;
                }
                callback.onError("Erro no upload: " + responseCode + " - " + loggedErrorText);
            }
            
            connection.disconnect();
            
        } catch (Exception e) {
            callback.onError("Erro no upload: " + e.getMessage());
        }
    }

    // This method is removed, its logic moved to calculateAndGetMd5ForInternalFile if needed for createItem
    // private String calculateMD5(File file) throws Exception { ... }

    // New helper method for internal MD5 calculation, e.g., for metadata in createItem
    private String calculateAndGetMd5ForInternalFile(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        FileInputStream fis = new FileInputStream(file);
        
        byte[] buffer = new byte[8192];
        int bytesRead;
        
        while ((bytesRead = fis.read(buffer)) != -1) {
            md.update(buffer, 0, bytesRead);
        }
        
        fis.close();
        
        byte[] digest = md.digest();
        return Base64.encodeToString(digest, Base64.NO_WRAP);
    }

    private String generateSignature(String stringToSign, String secretKey) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(), "HmacSHA1");
        mac.init(secretKeySpec);
        
        byte[] signature = mac.doFinal(stringToSign.getBytes());
        return Base64.encodeToString(signature, Base64.NO_WRAP);
    }

    public void createItem(String title, String description, CreateItemCallback callback) {
        try {
            JSONObject metadata = new JSONObject();
            metadata.put("title", title);
            metadata.put("description", description);
            metadata.put("mediatype", "software");
            metadata.put("collection", "opensource_software");

            String metadataContent = metadata.toString();
            
            File tempFile = File.createTempFile("metadata", ".json");
            java.io.FileWriter writer = new java.io.FileWriter(tempFile);
            writer.write(metadataContent);
            writer.close();

            FileInputStream fis = null;
            try {
                String metadataMd5 = calculateAndGetMd5ForInternalFile(tempFile);
                fis = new FileInputStream(tempFile);
                uploadFile(fis, tempFile.length(), itemIdentifier + "_meta.xml", metadataMd5, 0, new UploadCallback() {
                    @Override
                    public void onProgress(long uploadedBytes, int progress) {
                        // Metadata upload progress usually not critical to expose, but can be logged
                    }

                    @Override
                    public void onSuccess(String fileUrl) {
                        tempFile.delete();
                        callback.onSuccess("Item criado com sucesso");
                    }

                    @Override
                    public void onError(String error) {
                        tempFile.delete();
                        callback.onError(error);
                    }
                });
            } catch (Exception e) { // Catch exceptions from MD5 calculation or FileInputStream creation
                tempFile.delete(); // Ensure tempFile is deleted on error
                callback.onError("Erro ao preparar upload de metadados: " + e.getMessage());
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (IOException e) {
                        // Log this error, but the original error (if any) is more important
                        System.err.println("Erro ao fechar FileInputStream para metadados: " + e.getMessage());
                    }
                }
            }

        } catch (Exception e) { // Catch exceptions from metadata JSON creation or temp file operations
            callback.onError("Erro ao criar item: " + e.getMessage());
        }
    }

    public interface CreateItemCallback {
        void onSuccess(String message);
        void onError(String error);
    }
}

