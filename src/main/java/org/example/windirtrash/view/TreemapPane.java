package org.example.windirtrash.view;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import org.example.windirtrash.model.FileNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Pane que dibuja un treemap MUY sencillo.
 * Recorre recursivamente el árbol para incluir cualquier nodo “junk”.
 */
public class TreemapPane extends Pane {

    private final Canvas canvas = new Canvas();

    public TreemapPane() {
        getChildren().add(canvas);
        widthProperty().addListener((o, oldVal, n) -> redraw());
        heightProperty().addListener((o, oldVal, n) -> redraw());
    }

    /* datos a pintar */
    private FileNode root;

    public void setRoot(FileNode root) {
        this.root = root;
        redraw();
    }

    /* -------------- dibujo -------------- */
    private void redraw() {
        if (root == null) return;

        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, getWidth(), getHeight());

        /* recopila TODOS los FileNode marcados como basura */
        List<FileNode> junkList = new ArrayList<>();
        collectJunk(root, junkList);

        if (junkList.isEmpty()) return;

        long total = junkList.stream().mapToLong(FileNode::getSize).sum();
        double x = 0, y = 0, w = getWidth(), h = getHeight();

        /* ordenamos por tamaño desc para barras horizontales */
        junkList.sort((a, b) -> Long.compare(b.getSize(), a.getSize()));

        for (FileNode fn : junkList) {
            double frac = (double) fn.getSize() / total;
            double rectH = h * frac;

            g.setFill(colorFor(fn.getCategory()));
            g.fillRect(x, y, w, rectH);
            g.setStroke(Color.WHITE);
            g.strokeRect(x, y, w, rectH);

            /* etiqueta opcional */
            if (rectH > 14) {
                g.setFill(Color.BLACK);
                g.fillText(fn.getDisplayName(), x + 4, y + 12);
            }
            y += rectH;
        }
    }

    /* DFS para recopilar basura */
    private void collectJunk(FileNode node, List<FileNode> out) {
        if (node.isJunk()) out.add(node);
        node.getChildren().forEach(ch -> collectJunk(ch, out));
    }

    /* color fijo por categoría */
    private Color colorFor(String cat) {
        return switch (cat == null ? "Otros" : cat) {
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
