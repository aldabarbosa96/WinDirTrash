package org.example.windirtrash.task;

import javafx.application.Platform;
import javafx.concurrent.Task;
import org.example.windirtrash.model.FileNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class FileScannerTask extends Task<FileNode> {

    /* ─────────── reglas nombre / extensión ─────────── */

    private static final Map<String, String> EXT_TO_CAT = Map.ofEntries(Map.entry(".tmp", "Archivos temporales"), Map.entry(".temp", "Archivos temporales"), Map.entry(".log", "Archivos log"), Map.entry(".dmp", "Volcados (dumps)"),

            Map.entry(".ds_store", "Miniaturas del sistema"), Map.entry("thumbs.db", "Miniaturas del sistema"), Map.entry(".appledouble", "Miniaturas del sistema"),

            Map.entry(".class", "Objetos compilados"), Map.entry(".o", "Objetos compilados"), Map.entry(".obj", "Objetos compilados"));

    private static final Map<String, String> DIR_TO_CAT = Map.ofEntries(Map.entry("temp", "Carpetas de caché"), Map.entry("tmp", "Carpetas de caché"), Map.entry("__pycache__", "Carpetas de caché"), Map.entry("node_modules", "node_modules"),

            Map.entry("npm-cache", "Carpetas de caché"), Map.entry(".gradle", "Carpetas de caché"),   // subdir /caches
            Map.entry(".m2", "Carpetas de caché"),    // subdir /repository
            Map.entry("cache", "Carpetas de caché"),

            Map.entry("target", "Objetos compilados"), Map.entry("build", "Objetos compilados"));

    /* ─────────── fields ─────────── */

    private final Path rootPath;
    private final Consumer<FileNode> junkCallback;

    public FileScannerTask(Path rootPath, Consumer<FileNode> cb) {
        this.rootPath = rootPath;
        this.junkCallback = cb;
    }

    /* ─────────── main ─────────── */

    @Override
    protected FileNode call() throws Exception {

        updateMessage("Escaneando " + rootPath + " …");

        final FileNode rootNode = new FileNode(rootPath.toFile());
        final Map<Path, FileNode> map = new HashMap<>();
        map.put(rootPath, rootNode);

        final AtomicLong total = new AtomicLong();
        final AtomicLong done = new AtomicLong();

        /* 1) contar para la barra */
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

        /* 2) recorrido real */
        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                addDirectory(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                addFile(file, a.size());
                updateProgress(done.incrementAndGet(), total.get());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path p, IOException e) {
                updateProgress(done.incrementAndGet(), total.get());
                return Files.isDirectory(p) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            /* helpers */

            private void addDirectory(Path dir) {
                if (map.containsKey(dir)) return;

                final FileNode parent = map.get(dir.getParent());
                final FileNode node = new FileNode(dir.toFile());

                String cat = DIR_TO_CAT.get(dir.getFileName().toString().toLowerCase());
                if (cat == null) {
                    final String p = dir.toString().replace('\\', '/').toLowerCase();
                    if (p.contains("/.gradle/caches") || p.contains("/.m2/repository")) cat = "Carpetas de caché";
                }
                if (cat != null) markAs(node, cat);

                parent.getChildren().add(node);
                map.put(dir, node);
            }

            private void addFile(Path file, long size) {
                final FileNode parent = map.get(file.getParent());
                final FileNode node = new FileNode(file.toFile());
                node.setSize(size);

                final String name = file.getFileName().toString().toLowerCase();

                String cat = EXT_TO_CAT.get(name); // exact match
                if (cat == null) {
                    for (var e : EXT_TO_CAT.entrySet())
                        if (name.endsWith(e.getKey())) {
                            cat = e.getValue();
                            break;
                        }
                }
                if (cat != null) markAs(node, cat);

                parent.getChildren().add(node);

                /* propaga tamaño */
                for (Path p = file.getParent(); p != null; p = p.getParent()) {
                    map.get(p).setSize(map.get(p).getSize() + size);
                    if (p.equals(rootPath)) break;
                }
            }
        });

        /* 3) reglas globales */
        updateMessage("Post-procesando reglas globales…");
        postProcess(map);

        updateMessage("Escaneo completado");
        updateProgress(1, 1);
        return rootNode;
    }

    /* ─────────── post-process ─────────── */

    private void postProcess(Map<Path, FileNode> map) {

        final int total = map.size();
        final AtomicInteger done = new AtomicInteger();

        map.values().forEach(n -> {

            /* ───────────────── archivos vacíos ───────────────── */
            if (!n.isJunk()
                    && n.getFile().isFile()
                    && n.getSize() == 0) {
                markAs(n, "Archivos vacíos");
                return;                    // ① sigue con el siguiente nodo
            }

            /* ──────────────── carpetas vacías ────────────────── */
            if (!n.isJunk()
                    && n.getFile().isDirectory()
                    && n.getChildren().isEmpty()) {
                markAs(n, "Carpetas vacías");
                return;                    // ② evita que pase al bloque .lnk
            }

            /* ──────────────── atajos .lnk rotos ──────────────── */
            if (!n.isJunk()
                    && n.getFile().isFile()
                    && n.getFile().getName().toLowerCase().endsWith(".lnk")
                    && !linkTargetExists(n.getFile().toPath())) {
                markAs(n, "Atajos rotos");
            }
        });

        updateProgress(total, total);
    }

    /* ─────────── util ─────────── */

    private void markAs(FileNode n, String cat) {
        n.setJunk(true);
        n.setCategory(cat);
        notifyJunk(n);
    }

    private void notifyJunk(FileNode n) {
        if (junkCallback != null) Platform.runLater(() -> junkCallback.accept(n));
    }

    /**
     * comprueba destino de .lnk (versión rápida)
     */
    private boolean linkTargetExists(Path lnk) {
        try {
            if (Files.size(lnk) == 0) return false;        // vacíos ⇒ rotos
        } catch (IOException ignored) {
            return false;
        }

        try (RandomAccessFile raf = new RandomAccessFile(lnk.toFile(), "r")) {
            raf.seek(0x14);
            int flags = Integer.reverseBytes(raf.readInt());
            if ((flags & 0x1) == 0) return false;

            raf.seek(0x4c);
            raf.readInt(); // attrs (sin usar)

            raf.seek(0x4 + Short.toUnsignedInt(Short.reverseBytes(raf.readShort())) + 0x18);
            int len = raf.readUnsignedByte();
            if (len == 0) return false;

            byte[] buf = new byte[len];
            raf.readFully(buf);
            String path = new String(buf);
            return Files.exists(Path.of(path));
        } catch (IOException e) {
            return false;
        }
    }
}
