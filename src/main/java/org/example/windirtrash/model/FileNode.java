package org.example.windirtrash.model;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Nodo del árbol de archivos.  Ahora incluye la categoría de “basura”.
 */
public class FileNode {

    private final File file;
    private long size;
    private boolean junk;
    private String category;        // ← NUEVO
    private final List<FileNode> children = new ArrayList<>();

    public FileNode(File file) {
        this.file = file;
    }

    /* ---------- getters / setters ------------------------------------------ */
    public File getFile() {
        return file;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public boolean isJunk() {
        return junk;
    }

    public void setJunk(boolean junk) {
        this.junk = junk;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public List<FileNode> getChildren() {
        return children;
    }

    /* ---------- helpers ----------------------------------------------------- */
    public String getDisplayName() {
        return file.getName().isEmpty() ? file.getAbsolutePath() : file.getName();
    }

    public static String convertToHumanReadable(long bytes) {
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        double v = bytes;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.1f %s", v, u[i]);
    }
}
