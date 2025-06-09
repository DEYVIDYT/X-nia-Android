package com.winlator.Download.utils;

import android.util.Log; // For logging

import org.json.JSONObject; // For parsing JSON response

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets; // Added for UTF-8 encoding
import java.util.UUID; // For generating boundary

/**
 * Handles file uploads to the Datanodes.to API.
 * This class is responsible for constructing and sending a multipart/form-data POST request
 * containing the file to be uploaded and the necessary API key.
 * It provides progress updates and reports success or failure via a callback interface.
 */
public class DatanodesUploader {

    private static final String TAG = "DatanodesUploader";
    private static final String UPLOAD_URL = "https://datanodes.to/api/v1/files/upload";
    private String apiKey;

    /**
     * Callback interface for upload events.
     */
    public interface UploadCallback {
        /**
         * Called when upload progress is updated.
         * @param uploadedBytes The number of bytes uploaded so far.
         * @param progress The percentage of the upload completed (0-100), or -1 if indeterminate.
         */
        void onProgress(long uploadedBytes, int progress);

        /**
         * Called when the upload is successful.
         * @param fileUrl The URL of the uploaded file.
         */
        void onSuccess(String fileUrl);

        /**
         * Called when an error occurs during upload.
         * @param error A message describing the error.
         */
        void onError(String error);
    }

    /**
     * Constructs a new DatanodesUploader.
     * @param apiKey The Datanodes.to API key.
     */
    public DatanodesUploader(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Uploads a file to Datanodes.to.
     *
     * @param inputStream The InputStream to read the file data from. This stream is NOT closed by this method.
     * @param fileSize The total size of the file in bytes. Used for progress calculation and setting fixed-length streaming mode.
     *                 If <= 0, progress percentage will be indeterminate and chunked streaming mode might be used.
     * @param fileName The name of the file to be uploaded.
     * @param callback The callback to report progress, success, or errors.
     */
    public void uploadFile(InputStream inputStream, long fileSize, String fileName, UploadCallback callback) {
        HttpURLConnection connection = null;
        DataOutputStream dos = null;
        String boundary = "Boundary-" + UUID.randomUUID().toString(); // Unique boundary for multipart request

        try {
            // Setup HttpURLConnection
            URL url = new URL(UPLOAD_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true); // Allow sending data
            connection.setDoInput(true);  // Allow reading response
            connection.setUseCaches(false); // Disable caching
            connection.setRequestMethod("POST"); // HTTP POST method
            // Set request headers for multipart form data
            connection.setRequestProperty("Connection", "Keep-Alive");
            connection.setRequestProperty("ENCTYPE", "multipart/form-data");
            connection.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
            connection.setRequestProperty("X-API-Key", this.apiKey); // Datanodes API Key

            // If fileSize is known, use fixed-length streaming mode for efficiency.
            // Otherwise, default to chunked streaming mode (if server supports it without Content-Length).
            if (fileSize > 0) {
                long calculatedLength = calculateMultipartContentLength(fileSize, fileName, boundary);
                Log.d(TAG, "Calculated Content-Length for Datanodes upload: " + calculatedLength);
                connection.setFixedLengthStreamingMode(calculatedLength);
            } else {
                // Datanodes.to API might require Content-Length. If fileSize is unknown, this could be an issue.
                // For robustness, chunked streaming is set, but API behavior should be verified.
                connection.setChunkedStreamingMode(0); // Use default chunk size
            }

            // Get DataOutputStream to write the request body
            dos = new DataOutputStream(connection.getOutputStream());

            // Construct the file part of the multipart request
            // Boundary marker
            dos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            // Content-Disposition header for the file field
            dos.write(("Content-Disposition: form-data; name=\"file\";filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            // Content-Type header for the file (generic binary stream)
            dos.write(("Content-Type: application/octet-stream\r\n").getBytes(StandardCharsets.UTF_8));
            // Blank line separating headers from content
            dos.write(("\r\n").getBytes(StandardCharsets.UTF_8));

            // Read from the inputStream and write to the DataOutputStream
            byte[] buffer = new byte[8192]; // 8KB buffer for reading/writing
            int bytesRead;
            long totalBytesUploaded = 0;

            // Using BufferedInputStream for potentially better performance, especially if inputStream doesn't support mark/reset well for skips.
            BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);

            while ((bytesRead = bufferedInputStream.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead); // Write buffer to output stream
                totalBytesUploaded += bytesRead; // Accumulate uploaded bytes

                // Report progress
                if (fileSize > 0) { // Calculate percentage if fileSize is known
                    int progress = (int) ((totalBytesUploaded * 100) / fileSize);
                    callback.onProgress(totalBytesUploaded, progress);
                } else { // Report bytes uploaded if fileSize is unknown (indeterminate progress)
                    callback.onProgress(totalBytesUploaded, -1);
                }
            }
            // Newline after file data
            dos.write(("\r\n").getBytes(StandardCharsets.UTF_8));

            // End of multipart data (final boundary marker)
            dos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            dos.flush(); // Ensure all data is written
            dos.close(); // Close the DataOutputStream, which also closes the underlying connection's OutputStream

            // Get and process the HTTP response
            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Datanodes API Response Code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) { // HTTP 200 OK
                // Read the successful response
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                Log.d(TAG, "Datanodes API Response: " + response.toString());
                // Parse JSON response
                JSONObject jsonResponse = new JSONObject(response.toString());

                // Check API status and extract file URL
                if (jsonResponse.has("status") && "ok".equalsIgnoreCase(jsonResponse.getString("status"))) {
                    JSONObject data = jsonResponse.getJSONObject("data");
                    JSONObject fileInfo = data.getJSONObject("file");
                    JSONObject urlInfo = fileInfo.getJSONObject("url");
                    String fullUrl = urlInfo.getString("full");
                    callback.onSuccess(fullUrl); // Report success with file URL
                } else {
                    // Handle cases where API reports an error despite HTTP 200
                    String errorMessage = "Upload successful but API returned non-ok status.";
                    if (jsonResponse.has("error") && jsonResponse.getJSONObject("error").has("message")) {
                        errorMessage = jsonResponse.getJSONObject("error").getString("message");
                    }
                    callback.onError(errorMessage);
                }
            } else { // Handle non-OK HTTP responses
                BufferedReader errorReader = null;
                try {
                    // Try to read the error stream for more details
                    errorReader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                    StringBuilder errorResponse = new StringBuilder();
                    String errorLine;
                    while ((errorLine = errorReader.readLine()) != null) {
                        errorResponse.append(errorLine);
                    }
                    Log.e(TAG, "Datanodes API Error Response: " + errorResponse.toString());
                    callback.onError("Erro no upload: " + responseCode + " - " + errorResponse.toString());
                } catch (Exception e) { // Fallback if error stream reading fails
                    Log.e(TAG, "Error reading error stream: " + e.getMessage());
                    callback.onError("Erro no upload: " + responseCode + ". Não foi possível ler a mensagem de erro.");
                } finally {
                    if (errorReader != null) {
                        try { errorReader.close(); } catch (IOException e) { Log.e(TAG, "Failed to close error reader", e); }
                    }
                }
            }

        } catch (Exception e) { // Catch any other exceptions during the process
            Log.e(TAG, "Upload failed", e);
            callback.onError("Erro no upload: " + e.getMessage());
        } finally {
            // Clean up resources
            if (dos != null) {
                // DataOutputStream is typically closed after flushing or when an error occurs before full processing.
                // Attempting to close it again here might be redundant or cause issues if already closed.
            }
            if (connection != null) {
                connection.disconnect(); // Disconnect the HttpURLConnection
            }
            // The passed 'inputStream' is managed by the caller (UploadService) and should be closed there.
        }
    }

    /**
     * Helper method to calculate the total content length for a multipart/form-data request.
     * This is used when `setFixedLengthStreamingMode` is called on the HttpURLConnection.
     *
     * @param fileSize The size of the file being uploaded.
     * @param fileName The name of the file.
     * @param boundary The boundary string used for the multipart request.
     * @return The total calculated content length in bytes.
     */
    private long calculateMultipartContentLength(long fileSize, String fileName, String boundary) {
        long length = 0;
        // Length of the initial boundary and headers for the file part
        length += ("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8).length;
        length += ("Content-Disposition: form-data; name=\"file\";filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8).length;
        length += ("Content-Type: application/octet-stream\r\n").getBytes(StandardCharsets.UTF_8).length;
        length += ("\r\n").getBytes(StandardCharsets.UTF_8).length; // Blank line before file content

        // Length of the file content itself
        length += fileSize;

        // Length of the newline after file content
        length += ("\r\n").getBytes(StandardCharsets.UTF_8).length;

        // Length of the final boundary marker
        length += ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8).length;
        return length;
    }
}
