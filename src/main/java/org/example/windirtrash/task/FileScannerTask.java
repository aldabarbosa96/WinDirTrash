package org.example.windirtrash.task;

import javafx.application.Platform;
import javafx.concurrent.Task;
import org.example.windirtrash.model.FileNode;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class FileScannerTask extends Task<FileNode> {

    /* extensiones → categoría */
    private static final Map<String, String> EXT_TO_CAT = Map.ofEntries(Map.entry(".tmp", "Archivos temporales"), Map.entry(".temp", "Archivos temporales"), Map.entry(".log", "Archivos log"), Map.entry(".bak", "Copias de seguridad"), Map.entry(".old", "Copias de seguridad"), Map.entry(".dmp", "Volcados (dumps)"));

    /* carpetas basura → categoría */
    private static final Map<String, String> DIR_TO_CAT = Map.of("temp", "Carpetas de caché", "tmp", "Carpetas de caché", "__pycache__", "Carpetas de caché", "node_modules", "node_modules");

    private final Path rootPath;
    private final Consumer<FileNode> junkCallback;

    public FileScannerTask(Path rootPath, Consumer<FileNode> cb) {
        this.rootPath = rootPath;
        this.junkCallback = cb;
    }

    @Override
    protected FileNode call() throws Exception {

        updateMessage("Escaneando " + rootPath + " …");

        FileNode rootNode = new FileNode(rootPath.toFile());
        Map<Path, FileNode> map = new HashMap<>();
        map.put(rootPath, rootNode);

        AtomicLong total = new AtomicLong();
        AtomicLong done = new AtomicLong();

        /* 1) Contar archivos para la barra de progreso */
        Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                total.incrementAndGet();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });

        /* 2) Recorrido real */
        Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                addDirectory(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                addFile(file, attrs.size());
                updateProgress(done.incrementAndGet(), total.get());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException exc) {
                /* sin permiso → contamos y saltamos (no abortamos) */
                updateProgress(done.incrementAndGet(), total.get());
                return Files.isDirectory(path) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            /* ---------- helpers internos ---------- */

            private void addDirectory(Path dir) {
                if (map.containsKey(dir)) return;

                FileNode parent = map.get(dir.getParent());
                FileNode node = new FileNode(dir.toFile());
                String cat = DIR_TO_CAT.get(dir.getFileName().toString().toLowerCase());
                if (cat != null) {
                    node.setJunk(true);
                    node.setCategory(cat);
                    notifyJunk(node);
                }

                parent.getChildren().add(node);
                map.put(dir, node);
            }

            private void addFile(Path file, long size) {
                FileNode parent = map.get(file.getParent());
                FileNode node = new FileNode(file.toFile());
                node.setSize(size);

                EXT_TO_CAT.entrySet().stream().filter(e -> file.getFileName().toString().toLowerCase().endsWith(e.getKey())).findFirst().ifPresent(e -> {
                    node.setJunk(true);
                    node.setCategory(e.getValue());
                });

                if (node.isJunk()) notifyJunk(node);
                parent.getChildren().add(node);

                /* propagar tamaño hacia arriba */
                for (Path p = file.getParent(); p != null; p = p.getParent()) {
                    map.get(p).setSize(map.get(p).getSize() + size);
                    if (p.equals(rootPath)) break;
                }
            }

            private void notifyJunk(FileNode n) {
                if (junkCallback != null) Platform.runLater(() -> junkCallback.accept(n));
            }
        });

        updateMessage("Escaneo completado");
        updateProgress(1, 1);
        return rootNode;
    }
}
