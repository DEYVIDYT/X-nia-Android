package com.winlator.Download.model;

import java.util.Locale;

public class UploadStatus {
    
    public enum Status {
        UPLOADING,
        COMPLETED,
        ERROR,
        PAUSED
    }
    
    private int id;
    private String gameName;
    private String fileName;
    private long fileSize;
    private Status status;
    private int progress;
    private String errorMessage;
    private long startTime;
    private long uploadedBytes;
    private String accessKey; // New field
    private String secretKey; // New field
    private String itemIdentifier; // New field
    private String fileUri; // New field

    public UploadStatus(String gameName, String fileName, long fileSize, String accessKey, String secretKey, String itemIdentifier, String fileUri) {
        this.gameName = gameName;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.status = Status.UPLOADING;
        this.progress = 0;
        this.startTime = System.currentTimeMillis();
        this.uploadedBytes = 0;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.itemIdentifier = itemIdentifier;
        this.fileUri = fileUri;
    }

    public UploadStatus(int id, String gameName, String fileName, long fileSize, Status status, int progress, String errorMessage, long startTime, long uploadedBytes, String accessKey, String secretKey, String itemIdentifier, String fileUri) {
        this.id = id;
        this.gameName = gameName;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.status = status;
        this.progress = progress;
        this.errorMessage = errorMessage;
        this.startTime = startTime;
        this.uploadedBytes = uploadedBytes;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.itemIdentifier = itemIdentifier;
        this.fileUri = fileUri;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getGameName() {
        return gameName;
    }

    public void setGameName(String gameName) {
        this.gameName = gameName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFormattedFileSize() {
        return formatBytes(fileSize);
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getUploadedBytes() {
        return uploadedBytes;
    }

    public void setUploadedBytes(long uploadedBytes) {
        this.uploadedBytes = uploadedBytes;
    }

    public String getFormattedUploadedBytes() {
        return formatBytes(uploadedBytes);
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getItemIdentifier() {
        return itemIdentifier;
    }

    public void setItemIdentifier(String itemIdentifier) {
        this.itemIdentifier = itemIdentifier;
    }

    public String getFileUri() {
        return fileUri;
    }

    public void setFileUri(String fileUri) {
        this.fileUri = fileUri;
    }

    public String getStatusText() {
        switch (status) {
            case UPLOADING:
                return String.format(Locale.getDefault(), "Enviando: %d%%", progress);
            case COMPLETED:
                return "Concluído";
            case ERROR:
                return "Erro: " + (errorMessage != null ? errorMessage : "Desconhecido");
            case PAUSED:
                return "Pausado";
            default:
                return "Desconhecido";
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = ("KMGTPE").charAt(exp-1) + "";
        return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    public UploadStatus(String gameName, String fileName, long fileSize) {
        this.gameName = gameName;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.status = Status.UPLOADING;
        this.progress = 0;
        this.startTime = System.currentTimeMillis();
        this.uploadedBytes = 0;
        this.accessKey = null;
        this.secretKey = null;
        this.itemIdentifier = null;
        this.fileUri = null;
    }


}

