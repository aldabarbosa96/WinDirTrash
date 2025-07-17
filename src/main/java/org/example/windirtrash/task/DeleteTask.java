package org.example.windirtrash.task;

import javafx.concurrent.Task;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class DeleteTask extends Task<Integer> {

    private final List<Path> targets;    // lista plana de rutas
    private final boolean toTrash;
    private long freedBytes = 0;         // bytes realmente liberados

    public DeleteTask(List<Path> targets, boolean toTrash) {
        this.targets = targets;
        this.toTrash = toTrash;
    }

    @Override
    protected Integer call() {
        int ok = 0, fail = 0;
        final int TOTAL = targets.size();

        for (int idx = 0; idx < TOTAL; idx++) {
            Path p = targets.get(idx);
            long size = 0;
            try {
                size = calculateSize(p);
            } catch (IOException ex) {
                // no abortar, lo contamos como fallo
                fail++;
                continue;
            }
            try {
                if (toTrash) {
                    if (Desktop.getDesktop().moveToTrash(p.toFile())) {
                        ok++;
                        freedBytes += size;
                    } else fail++;
                } else {
                    try {
                        recursiveDelete(p);
                        ok++;
                        freedBytes += size;
                    } catch (IOException delEx) {
                        fail++;
                    }
                }
            } catch (Throwable t) {
                // cualquier otro error, no abortar todo el Task
                fail++;
            }

            if (idx % 200 == 0) {
                updateProgress(idx + 1, TOTAL);
                updateMessage("Eliminando… " + (idx + 1) + "/" + TOTAL);
            }
        }

        updateProgress(TOTAL, TOTAL);
        updateMessage("Completado: " + ok + " | Fallos: " + fail);
        return ok;
    }


    /**
     * @return bytes realmente liberados durante la ejecución
     */
    public long getFreedBytes() {
        return freedBytes;
    }

    /**
     * Calcula el tamaño total en bytes de un archivo o directorio (recursivo).
     */
    private long calculateSize(Path p) throws IOException {
        if (Files.notExists(p)) return 0L;
        AtomicLong size = new AtomicLong(0);
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(p, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    size.addAndGet(attrs.size());
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            size.set(Files.size(p));
        }
        return size.get();
    }

    /**
     * Borrado recursivo de ficheros y carpetas.
     */
    private static void recursiveDelete(Path p) throws IOException {
        if (Files.notExists(p)) return;
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(p, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    Files.deleteIfExists(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            Files.deleteIfExists(p);
        }
    }
}
