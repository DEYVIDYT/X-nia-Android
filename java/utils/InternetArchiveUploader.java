package com.winlator.Download.utils;

import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
        void onProgress(int progress);
        void onSuccess(String fileUrl);
        void onError(String error);
    }

    public void uploadFile(File file, String fileName, UploadCallback callback) {
        try {
            String bucketName = itemIdentifier;
            String objectKey = fileName;
            
            // Gerar timestamp
            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
            String timestamp = dateFormat.format(new Date());

            // Calcular MD5 do arquivo
            String md5Hash = calculateMD5(file);

            // Criar string para assinatura
            String stringToSign = "PUT\n" + md5Hash + "\napplication/octet-stream\n" + timestamp + "\n/" + bucketName + "/" + objectKey;

            // Gerar assinatura HMAC-SHA1
            String signature = generateSignature(stringToSign, secretKey);

            // URL do Internet Archive S3
            String uploadUrl = "https://s3.us.archive.org/" + bucketName + "/" + objectKey;

            // Fazer upload
            URL url = new URL(uploadUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "LOW " + accessKey + ":" + signature);
            connection.setRequestProperty("Date", timestamp);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("Content-MD5", md5Hash);
            connection.setRequestProperty("Content-Length", String.valueOf(file.length()));

            // Enviar arquivo
            DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
            FileInputStream fileInputStream = new FileInputStream(file);
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = 0;
            long fileSize = file.length();
            
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                
                int progress = (int) ((totalBytesRead * 100) / fileSize);
                callback.onProgress(progress);
            }
            
            fileInputStream.close();
            outputStream.close();

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
                    // Optional: Add a newline if you want to preserve some structure,
                    // but be mindful if the original error is one giant line.
                    // For this fix, let's keep it simple and append raw lines.
                }
                reader.close();

                String fullErrorText = errorResponse.toString();
                int maxLogLength = 1000; // Define a max length for the logged error part
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

    private String calculateMD5(File file) throws Exception {
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

            // Para criar um item, fazemos upload de um arquivo de metadados
            String metadataContent = metadata.toString();
            
            // Criar arquivo temporário com metadados
            File tempFile = File.createTempFile("metadata", ".json");
            java.io.FileWriter writer = new java.io.FileWriter(tempFile);
            writer.write(metadataContent);
            writer.close();

            uploadFile(tempFile, itemIdentifier + "_meta.xml", new UploadCallback() {
                @Override
                public void onProgress(int progress) {
                    // Não precisa reportar progresso para metadados
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

        } catch (Exception e) {
            callback.onError("Erro ao criar item: " + e.getMessage());
        }
    }

    public interface CreateItemCallback {
        void onSuccess(String message);
        void onError(String error);
    }
}

