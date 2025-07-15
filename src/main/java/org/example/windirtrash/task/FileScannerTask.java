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

/**
 * FileScannerTask v2.1
 * — una sola pasada, tolerante a permisos.
 * — tamaños de directorio corregidos antes de devolver el árbol.
 * — copias de seguridad: se detectan por patrón *.bak|~|.old…
 *     » la copia <strong>más reciente</strong> del mismo “stem” se marca
 *       para REVIEW; las anteriores se consideran basura segura.
 * — post‑procesado sin bloquear UI.
 */
public class FileScannerTask extends Task<FileNode> {

    /* ───────────────────── reglas por extensión / carpeta ──────────────────── */

    private static final Map<String, String> EXT_TO_CAT = Map.ofEntries(Map.entry(".tmp", "Archivos temporales"), Map.entry(".temp", "Archivos temporales"), Map.entry(".log", "Archivos log"), Map.entry(".dmp", "Volcados (dumps)"),

            Map.entry(".ds_store", "Miniaturas del sistema"), Map.entry("thumbs.db", "Miniaturas del sistema"), Map.entry(".appledouble", "Miniaturas del sistema"),

            Map.entry(".class", "Objetos compilados"), Map.entry(".o", "Objetos compilados"), Map.entry(".obj", "Objetos compilados"));

    private static final Map<String, String> DIR_TO_CAT = Map.ofEntries(Map.entry("temp", "Carpetas de caché"), Map.entry("tmp", "Carpetas de caché"), Map.entry("__pycache__", "Carpetas de caché"), Map.entry("npm-cache", "Carpetas de caché"), Map.entry(".gradle", "Carpetas de caché"),   // sub‑dir /caches
            Map.entry(".m2", "Carpetas de caché"),   // sub‑dir /repository
            Map.entry("cache", "Carpetas de caché"),

            Map.entry("target", "Objetos compilados"), Map.entry("build", "Objetos compilados"),

            Map.entry("node_modules", "node_modules"));

    /* ───────────────────── ajustes ──────────────────── */

    private static final List<String> EXCLUDED = List.of("/windows/winsxs", "/system volume information", "/$recycle.bin");
    private static final int PROGRESS_MASK = 0x3FFF;   // ≈ 16K items → UI tick

    /* ───────────────────── estado ──────────────────── */

    private final Path rootPath;
    private final ConcurrentMap<Path, FileNode> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Path, DirInfo> dirInfo = new ConcurrentHashMap<>();
    private final Map<String, FileNode> latestBk = new HashMap<>();
    private final List<FileNode> junkList = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong processed = new AtomicLong();

    /* acumulador de tamaño e hijos para cada directorio */
    private static final class DirInfo {
        long size;
        int childCnt;
    }

    public FileScannerTask(Path root) {
        this.rootPath = root;
    }

    public List<FileNode> getJunkFound() {
        return List.copyOf(junkList);
    }

    /* ══════════════════  main  ══════════════════ */

    @Override
    protected FileNode call() throws Exception {

        updateMessage("Escaneando " + rootPath + "…");
        dirInfo.put(rootPath, new DirInfo());
        cache.put(rootPath, new FileNode(rootPath.toFile()));

        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {

            /* ── directorios ─────────────────────────────── */
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                if (isExcluded(dir)) return FileVisitResult.SKIP_SUBTREE;

                dirInfo.computeIfAbsent(dir, k -> new DirInfo());
                if (dir.getParent() != null) dirInfo.computeIfAbsent(dir.getParent(), k -> new DirInfo()).childCnt++;

                FileNode node = cache.computeIfAbsent(dir, p -> new FileNode(p.toFile()));
                ensureLinked(dir);

                String cat = quickDirCategory(dir);
                if (cat != null) markAs(node, cat);

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                DirInfo info = dirInfo.get(dir);
                if (info != null && info.size == 0 && info.childCnt == 0) {
                    FileNode fn = cache.get(dir);
                    markAs(fn, "Carpetas vacías");
                }
                if (dir.getParent() != null && info != null) dirInfo.get(dir.getParent()).size += info.size;
                return FileVisitResult.CONTINUE;
            }

            /* ── ficheros ────────────────────────────────── */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                processFile(file, a.size());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path p, IOException ex) {
                return (ex instanceof AccessDeniedException) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
        });

        /* --- sincroniza tamaños definitivos --- */
        cache.forEach((p, n) -> {
            DirInfo d = dirInfo.get(p);
            if (d != null) n.setSize(d.size);
        });

        updateMessage("Escaneo completado – " + junkList.size() + " elementos basura");
        updateProgress(1, 1);
        return cache.get(rootPath);
    }

    /* ═════════════ fichero individual ═════════════ */

    private void processFile(Path file, long size) {

        DirInfo di = dirInfo.computeIfAbsent(file.getParent(), k -> new DirInfo());
        di.childCnt++;
        di.size += size;

        boolean alreadyClassified = false;

        /* —— copias de seguridad —— */
        String stem = backupStem(file.getFileName().toString());
        if (stem != null) {
            FileNode cur = createNode(file, size);
            FileNode prev = latestBk.put(stem, cur);

            if (prev == null)       // primera (la más reciente vista)
                markAsReview(cur, "Copias de seguridad");
            else                  // anteriores ⇒ basura segura
                markAs(cur, "Copias de seguridad");

            alreadyClassified = true;
        }

        /* —— demás basura rápida —— */
        if (!alreadyClassified && isJunkFile(file, size)) {
            FileNode n = createNode(file, size);
            markAs(n, categoryOfFile(file, size));
        }

        long d = processed.incrementAndGet();
        if ((d & PROGRESS_MASK) == 0) {
            updateProgress(d, -1);
            updateMessage("Escaneados: " + d + "  |  Basura: " + junkList.size());
        }
    }

    /* ═════════════ creación / enlace ═════════════ */

    private FileNode createNode(Path p, long size) {
        FileNode n = new FileNode(p.toFile());
        n.setSize(size);
        cache.put(p, n);
        ensureLinked(p);
        return n;
    }

    private void ensureLinked(Path child) {
        Path parent = child.getParent();
        if (parent == null) return;
        FileNode par = cache.get(parent);
        FileNode me = cache.get(child);
        if (par != null && me != null && !par.getChildren().contains(me)) par.getChildren().add(me);
    }

    /* ═════════════ marcado ═════════════ */

    private void markAs(FileNode n, String cat) {
        if (n == null) return;
        n.setJunk(true);
        n.setCategory(cat);
        junkList.add(n);
    }

    /**
     * copia más reciente → no basura, pero clasificada para el árbol
     */
    private void markAsReview(FileNode n, String cat) {
        if (n == null) return;
        n.setJunk(false);
        n.setCategory(cat);
        // no se añade a junkList => no se propone borrar
    }

    /* ═════════════ helpers ═════════════ */

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
        else if (cat == null && name.endsWith(".lnk")) cat = "Atajos rotos";
        else if (cat == null) cat = "Otros";
        return cat;
    }

    private String quickDirCategory(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase();
        String cat = DIR_TO_CAT.get(name);
        if (cat == null) {
            String p = dir.toString().replace('\\', '/').toLowerCase();
            if (p.contains("/.gradle/caches") || p.contains("/.m2/repository")) cat = "Carpetas de caché";
        }
        return cat;
    }

    /* —— copias de seguridad —— */
    private static final String[] BK_TAGS = {".bak", ".backup", ".old", "~"};

    private String backupStem(String fname) {
        String lower = fname.toLowerCase();
        for (String tag : BK_TAGS)
            if (lower.endsWith(tag)) return lower.substring(0, lower.length() - tag.length());
        return null;
    }

    /* —— comprobación rápida de .lnk —— */
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
            raf.readInt();  // attrs
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
