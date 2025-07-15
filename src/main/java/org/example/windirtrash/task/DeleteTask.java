package org.example.windirtrash.task;

import javafx.concurrent.Task;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

public class DeleteTask extends Task<Integer> {

    private final List<Path> targets;    // lista plana de rutas
    private final boolean toTrash;

    public DeleteTask(List<Path> targets, boolean toTrash) {
        this.targets = targets;
        this.toTrash = toTrash;
    }

    @Override
    protected Integer call() throws Exception {

        int ok = 0, fail = 0;
        final int TOTAL = targets.size();

        for (int idx = 0; idx < TOTAL; idx++) {

            Path p = targets.get(idx);
            try {
                if (toTrash) {
                    if (Desktop.getDesktop().moveToTrash(p.toFile())) ok++;
                    else fail++;
                } else {
                    recursiveDelete(p);
                    ok++;
                }
            } catch (Exception e) {
                fail++;
            }

            if (idx % 200 == 0) {         // cada 200 ítems ⇒ update UI
                updateProgress(idx + 1, TOTAL);
                updateMessage("Eliminando… " + (idx + 1) + "/" + TOTAL);
            }
            if (isCancelled()) break;
        }
        updateProgress(TOTAL, TOTAL);
        updateMessage("Completado: " + ok + " | Fallos: " + fail);
        return ok;
    }

    /* util privado */
    private static void recursiveDelete(Path p) throws Exception {
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
        } else Files.deleteIfExists(p);
    }
}
