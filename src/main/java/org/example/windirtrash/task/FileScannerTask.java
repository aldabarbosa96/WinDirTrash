package org.example.windirtrash.task;

import javafx.concurrent.Task;
import org.example.windirtrash.model.FileNode;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Recorre el disco, clasifica archivos basura y devuelve el árbol completo.
 */
public class FileScannerTask extends Task<FileNode> {

    /* extensiones → categoría en castellano */
    private static final Map<String, String> EXT_TO_CAT = Map.ofEntries(Map.entry(".tmp", "Archivos temporales"), Map.entry(".temp", "Archivos temporales"), Map.entry(".log", "Archivos log"), Map.entry(".bak", "Copias de seguridad"), Map.entry(".old", "Copias de seguridad"), Map.entry(".dmp", "Volcados (dumps)"));

    /* nombres de carpeta basura */
    private static final Map<String, String> DIR_TO_CAT = Map.of("temp", "Carpetas de caché", "tmp", "Carpetas de caché", "__pycache__", "Carpetas de caché", "node_modules", "node_modules");

    private final Path rootPath;

    public FileScannerTask(Path rootPath) {
        this.rootPath = rootPath;
    }

    @Override
    protected FileNode call() throws Exception {

        updateMessage("Escaneando " + rootPath + " …");
        FileNode rootNode = new FileNode(rootPath.toFile());
        Map<Path, FileNode> map = new HashMap<>();
        map.put(rootPath, rootNode);

        AtomicLong total = new AtomicLong();
        AtomicLong done = new AtomicLong();

        /* contar nº de archivos */
        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
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

        /* recorrido real */
        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!map.containsKey(dir)) {
                    FileNode parent = map.get(dir.getParent());
                    FileNode nd = new FileNode(dir.toFile());
                    String cat = DIR_TO_CAT.get(dir.getFileName().toString().toLowerCase());
                    if (cat != null) {
                        nd.setJunk(true);
                        nd.setCategory(cat);
                    }
                    parent.getChildren().add(nd);
                    map.put(dir, nd);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                FileNode parent = map.get(file.getParent());
                FileNode node = new FileNode(file.toFile());
                long size = attrs.size();

                node.setSize(size);

                String cat = EXT_TO_CAT.entrySet().stream().filter(e -> file.getFileName().toString().toLowerCase().endsWith(e.getKey())).map(Map.Entry::getValue).findFirst().orElse(null);

                if (cat != null) {
                    node.setJunk(true);
                    node.setCategory(cat);
                }

                parent.getChildren().add(node);

                /* propagar tamaño */
                for (Path p = file.getParent(); p != null; p = p.getParent()) {
                    map.get(p).setSize(map.get(p).getSize() + size);
                    if (p.equals(rootPath)) break;
                }

                updateProgress(done.incrementAndGet(), total.get());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
                updateProgress(done.incrementAndGet(), total.get());
                return FileVisitResult.CONTINUE;
            }
        });

        updateMessage("Escaneo completado");
        updateProgress(1, 1);
        return rootNode;
    }
}
