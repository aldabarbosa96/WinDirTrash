package org.example.windirtrash.view;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.example.windirtrash.model.FileNode;
import org.example.windirtrash.utils.CategoryInfo;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * TreemapPane – treemap estilo WinDirStat para basura.
 * <p>
 * *Redibujamos en <code>layoutChildren()</code>*, de modo que siempre se
 * ejecuta **después** de que el contenedor tenga su tamaño definitivo.
 */
public class TreemapPane extends Pane {

    /* ---------- structs ---------- */
    private static final class Rect {
        final FileNode n;
        final double x, y, w, h;

        Rect(FileNode n, double x, double y, double w, double h) {
            this.n = n;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(double px, double py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    /* ---------- fields ---------- */
    private final Canvas canvas = new Canvas();
    private final List<Rect> rects = new ArrayList<>();
    private final Tooltip tip = new Tooltip();
    private FileNode root;

    /* ---------- ctor ---------- */
    public TreemapPane() {
        getChildren().add(canvas);
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED, this::onMove);
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED, this::onClick);
        // fondo claro para verificar que el canvas realmente se pinta
        setStyle("-fx-background-color: #fafafa;");
    }

    public void setRoot(FileNode r) {
        root = r;
        requestLayout();
    }

    /* ============================================================ */
    /*  Layout & paint                                              */
    /* ============================================================ */
    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        redraw();
    }

    private void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double W = getWidth(), H = getHeight();
        g.clearRect(0, 0, W, H);
        rects.clear();
        if (root == null || W < 2 || H < 2) return;

        /* recopilar basura */
        List<FileNode> junk = new ArrayList<>();
        collect(root, junk);
        if (junk.isEmpty()) return;
        junk.sort(Comparator.comparingLong(FileNode::getSize).reversed());

        long total = junk.stream().mapToLong(FileNode::getSize).sum();
        double totalA = W * H;
        Map<FileNode, Double> area = new HashMap<>();
        for (FileNode n : junk) area.put(n, totalA * n.getSize() / total);

        split(junk, 0, junk.size() - 1, 0, 0, W, H, area);

        for (Rect r : rects) {
            g.setFill(color(r.n.getCategory()));
            g.fillRect(r.x, r.y, r.w, r.h);
            g.setStroke(Color.WHITE);
            g.strokeRect(r.x, r.y, r.w, r.h);
            if (r.w > 40 && r.h > 18) {
                g.setFill(Color.BLACK);
                g.fillText(r.n.getDisplayName(), r.x + 4, r.y + 14);
            }
        }
    }

    /* ---------- split treemap simple ---------- */
    private void split(List<FileNode> list, int from, int to, double x, double y, double w, double h, Map<FileNode, Double> area) {
        if (from > to) return;
        if (from == to) {
            rects.add(new Rect(list.get(from), x, y, w, h));
            return;
        }
        double total = 0;
        for (int i = from; i <= to; i++) total += area.get(list.get(i));
        double acc = 0;
        int pivot = from;
        for (int i = from; i <= to; i++) {
            acc += area.get(list.get(i));
            if (acc >= total / 2) {
                pivot = i;
                break;
            }
        }
        double ratio = acc / total;
        if (w >= h) {
            double w1 = w * ratio;
            split(list, from, pivot, x, y, w1, h, area);
            split(list, pivot + 1, to, x + w1, y, w - w1, h, area);
        } else {
            double h1 = h * ratio;
            split(list, from, pivot, x, y, w, h1, area);
            split(list, pivot + 1, to, x, y + h1, w, h - h1, area);
        }
    }

    /* ---------- interacción ---------- */
    private void onMove(MouseEvent e) {
        for (Rect r : rects)
            if (r.contains(e.getX(), e.getY())) {
                tip.setText(r.n.getFile().getAbsolutePath() + "\n" + FileNode.convertToHumanReadable(r.n.getSize()) + "\n\n" + CategoryInfo.get(r.n.getCategory()).desc() + "\n" + (CategoryInfo.get(r.n.getCategory()).risk() == CategoryInfo.Risk.SAFE ? "✅ Seguro de borrar" : "⚠️ Revisa antes de borrar"));
                Tooltip.install(canvas, tip);
                tip.setShowDelay(Duration.millis(40));
                return;
            }
        Tooltip.uninstall(canvas, tip);
    }

    private void onClick(MouseEvent e) {
        for (Rect r : rects)
            if (r.contains(e.getX(), e.getY())) {
                try {
                    if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(r.n.getFile().getParentFile());
                } catch (IOException ignored) {
                }
                break;
            }
    }

    /* ---------- utils ---------- */
    private static void collect(FileNode n, List<FileNode> out) {
        if (n.isJunk()) out.add(n);
        n.getChildren().forEach(ch -> collect(ch, out));
    }

    private static Color color(String c) {
        return switch (c == null ? "Otros" : c) {
            case "Archivos temporales" -> Color.LIGHTBLUE;
            case "Archivos log" -> Color.LIGHTGREEN;
            case "Copias de seguridad" -> Color.GOLD;
            case "Volcados (dumps)" -> Color.PALEVIOLETRED;
            case "Carpetas de caché" -> Color.KHAKI;
            case "node_modules" -> Color.LIGHTSALMON;
            default -> Color.LIGHTGRAY;
        };
    }
}
