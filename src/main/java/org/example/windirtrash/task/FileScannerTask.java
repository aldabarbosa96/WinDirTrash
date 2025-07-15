package org.example.windirtrash.task;

import javafx.concurrent.Task;
import org.example.windirtrash.model.FileNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public class FileScannerTask extends Task<FileNode> {

    /* ══════════════════ tablas de reglas ══════════════════ */

    private static final Map<String, String> EXT_TO_CAT = Map.ofEntries(Map.entry(".tmp", "Archivos temporales"),
            Map.entry(".temp", "Archivos temporales"), Map.entry(".log", "Archivos log"), Map.entry(".dmp", "Volcados (dumps)"),
            Map.entry(".ds_store", "Miniaturas del sistema"), Map.entry("thumbs.db", "Miniaturas del sistema"), Map.entry(".appledouble", "Miniaturas del sistema"),
            Map.entry(".class", "Objetos compilados"), Map.entry(".o", "Objetos compilados"), Map.entry(".obj", "Objetos compilados"));

    private static final Map<String, String> DIR_TO_CAT = Map.ofEntries(Map.entry("temp", "Carpetas de caché"), Map.entry("tmp", "Carpetas de caché"), Map.entry("__pycache__", "Carpetas de caché"), Map.entry("npm-cache", "Carpetas de caché"), Map.entry(".gradle", "Carpetas de caché"),   // /caches
            Map.entry(".m2", "Carpetas de caché"),
            Map.entry("cache", "Carpetas de caché"),
            Map.entry("target", "Objetos compilados"), Map.entry("build", "Objetos compilados"),
            Map.entry("node_modules", "node_modules"));

    /* ══════════════════ configuración ══════════════════ */

    private static final List<String> EXCLUDED = List.of("/windows/winsxs", "/system volume information", "/$recycle.bin");

    private static final int PROGRESS_MASK = 0x1FFF;   // 8 192

    /* ══════════════════ estado ══════════════════ */

    private final Path rootPath;
    private final ConcurrentMap<Path, FileNode> cache = new ConcurrentHashMap<>();
    private final AtomicLong done = new AtomicLong();

    /**
     * Lista final con todos los nodos basura encontrados.
     */
    private final List<FileNode> junkFound = Collections.synchronizedList(new ArrayList<>());

    public FileScannerTask(Path root) {
        this.rootPath = root;
    }

    /* ── getter público para el controlador ───────────────────────── */
    public List<FileNode> getJunkFound() {
        return List.copyOf(junkFound);
    }

    /* ══════════════════ ejecución principal ══════════════════ */

    @Override
    protected FileNode call() throws Exception {

        updateMessage("Escaneando " + rootPath + "…");
        cache.put(rootPath, new FileNode(rootPath.toFile()));

        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                if (isExcluded(dir)) return FileVisitResult.SKIP_SUBTREE;

                ensureLinked(dir);
                String cat = categoryOfDir(dir);
                if (cat != null) markAs(cache.get(dir), cat);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                processFile(file, a.size());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path p, IOException e) {
                return (e instanceof AccessDeniedException) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
        });

        updateMessage("Post‑procesando reglas globales…");
        postProcess(cache.get(rootPath));

        updateMessage("Escaneo completado – " + junkFound.size() + " elementos basura");
        updateProgress(1, 1);
        return cache.get(rootPath);
    }

    /* ══════════════════ manejo de ficheros ══════════════════ */

    private void processFile(Path file, long size) {

        if (isJunkFile(file, size)) {
            FileNode n = new FileNode(file.toFile());
            n.setSize(size);
            markAs(n, categoryOfFile(file, size));   // añade a junkFound
            cache.put(file, n);
            ensureLinked(file);
        }

        /* propaga tamaño a ancestros */
        for (Path p = file.getParent(); p != null; p = p.getParent()) {
            cache.computeIfAbsent(p, k -> new FileNode(k.toFile())).setSize(cache.get(p).getSize() + size);
            if (p.equals(rootPath)) break;
        }

        long d = done.incrementAndGet();
        if ((d & PROGRESS_MASK) == 0) updateProgress(d, -1);
        if ((d & PROGRESS_MASK) == 0) updateMessage("Escaneados: " + d + "  |  Basura: " + junkFound.size());
    }

    /* ══════════════════ batching interno (sólo lista) ══════════════════ */

    private void markAs(FileNode n, String cat) {
        n.setJunk(true);
        n.setCategory(cat);
        junkFound.add(n);
    }

    /* ══════════════════ post‑procesado ══════════════════ */

    private void postProcess(FileNode root) {
        Deque<FileNode> st = new ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            FileNode n = st.pop();

            if (!n.isJunk() && n.getFile().isDirectory() && n.getChildren().isEmpty() && n.getSize() == 0) {
                markAs(n, "Carpetas vacías");
            } else if (!n.isJunk() && n.getFile().isFile() && n.getSize() == 0) {
                markAs(n, "Archivos vacíos");
            } else if (!n.isJunk() && n.getFile().isFile() && n.getFile().getName().toLowerCase().endsWith(".lnk") && !linkTargetExists(n.getFile().toPath())) {
                markAs(n, "Atajos rotos");
            }
            n.getChildren().forEach(st::push);
        }
    }

    /* ══════════════════ helpers ══════════════════ */

    private void ensureLinked(Path child) {
        Path parentP = child.getParent();
        if (parentP == null) return;
        FileNode parent = cache.computeIfAbsent(parentP, k -> new FileNode(k.toFile()));
        FileNode me = cache.computeIfAbsent(child, k -> new FileNode(k.toFile()));
        if (!parent.getChildren().contains(me)) parent.getChildren().add(me);
    }

    private boolean isExcluded(Path p) {
        String s = p.toString().toLowerCase().replace('\\', '/');
        return EXCLUDED.stream().anyMatch(s::contains);
    }

    private boolean isJunkFile(Path p, long size) {
        String name = p.getFileName().toString().toLowerCase();
        if (EXT_TO_CAT.containsKey(name)) return true;
        for (String ext : EXT_TO_CAT.keySet())
            if (name.endsWith(ext)) return true;
        if (size == 0) return true;
        return name.endsWith(".lnk") && !linkTargetExists(p);
    }

    private String categoryOfFile(Path p, long size) {
        String name = p.getFileName().toString().toLowerCase();
        String cat = EXT_TO_CAT.get(name);
        if (cat == null) for (var e : EXT_TO_CAT.entrySet())
            if (name.endsWith(e.getKey())) {
                cat = e.getValue();
                break;
            }
        if (cat == null && size == 0) cat = "Archivos vacíos";
        if (cat == null && name.endsWith(".lnk")) cat = "Atajos rotos";
        if (cat == null) cat = "Otros";
        return cat;
    }

    private String categoryOfDir(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase();
        String cat = DIR_TO_CAT.get(name);
        if (cat == null) {
            String p = dir.toString().replace('\\', '/').toLowerCase();
            if (p.contains("/.gradle/caches") || p.contains("/.m2/repository")) cat = "Carpetas de caché";
        }
        return cat;
    }

    private boolean linkTargetExists(Path lnk) {
        try {
            if (Files.size(lnk) == 0) return false;
        } catch (IOException ignored) {
            return false;
        }

        try (RandomAccessFile raf = new RandomAccessFile(lnk.toFile(), "r")) {
            raf.seek(0x14);
            int flags = Integer.reverseBytes(raf.readInt());
            if ((flags & 0x1) == 0) return false;

            raf.seek(0x4c);
            raf.readInt();

            raf.seek(0x4 + Short.toUnsignedInt(Short.reverseBytes(raf.readShort())) + 0x18);
            int len = raf.readUnsignedByte();
            if (len == 0) return false;

            byte[] buf = new byte[len];
            raf.readFully(buf);
            String target = new String(buf, StandardCharsets.UTF_8);

            try {
                return Files.exists(Path.of(target));
            } catch (InvalidPathException | NullPointerException e) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
    }
}
