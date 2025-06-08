package com.winlator.Download.model;

public class UploadStatus {
    
    public enum Status {
        UPLOADING,
        COMPLETED,
        ERROR
    }
    
    private String gameName;
    private String fileName;
    private long fileSize;
    private Status status;
    private int progress;
    private String errorMessage;
    private long startTime;

    public UploadStatus(String gameName, String fileName, long fileSize) {
        this.gameName = gameName;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.status = Status.UPLOADING;
        this.progress = 0;
        this.startTime = System.currentTimeMillis();
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

    public String getFormattedFileSize() {
        if (fileSize < 1024) return fileSize + " B";
        if (fileSize < 1024 * 1024) return String.format("%.1f KB", fileSize / 1024.0);
        if (fileSize < 1024 * 1024 * 1024) return String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
        return String.format("%.1f GB", fileSize / (1024.0 * 1024.0 * 1024.0));
    }

    public String getStatusText() {
        switch (status) {
            case UPLOADING:
                return "Enviando... " + progress + "%";
            case COMPLETED:
                return "Concluído";
            case ERROR:
                return "Erro: " + (errorMessage != null ? errorMessage : "Erro desconhecido");
            default:
                return "Desconhecido";
        }
    }
}

