package com.winlator.Download.model;

import java.io.Serializable;

public class Release implements Serializable {
    private String name;
    private String tagName;
    private String publishedAt;
    private String body;
    private String htmlUrl;
    private String downloadUrl;
    private String assetName;
    private long assetSize;

    public Release(String name, String tagName, String publishedAt, String body, String htmlUrl, String downloadUrl, String assetName, long assetSize) {
        this.name = name;
        this.tagName = tagName;
        this.publishedAt = publishedAt;
        this.body = body;
        this.htmlUrl = htmlUrl;
        this.downloadUrl = downloadUrl;
        this.assetName = assetName;
        this.assetSize = assetSize;
    }

    public String getName() {
        return name;
    }

    public String getTagName() {
        return tagName;
    }

    public String getPublishedAt() {
        return publishedAt;
    }

    public String getBody() {
        return body;
    }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public String getAssetName() {
        return assetName;
    }

    public long getAssetSize() {
        return assetSize;
    }

    // Método para formatar o tamanho do arquivo em KB, MB, etc.
    public String getFormattedSize() {
        if (assetSize < 1024) {
            return assetSize + " B";
        } else if (assetSize < 1024 * 1024) {
            return String.format("%.1f KB", assetSize / 1024.0);
        } else if (assetSize < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", assetSize / (1024.0 * 1024));
        } else {
            return String.format("%.1f GB", assetSize / (1024.0 * 1024 * 1024));
        }
    }
}


