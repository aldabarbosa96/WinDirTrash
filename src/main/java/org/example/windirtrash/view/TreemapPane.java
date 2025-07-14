package org.example.windirtrash.view;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.example.windirtrash.model.FileNode;
import org.example.windirtrash.utils.CategoryInfo;

import java.util.*;
import java.util.function.Consumer;

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
    private final Canvas cv = new Canvas();
    private final List<Rect> rects = new ArrayList<>();
    private final Tooltip tip = new Tooltip();
    private FileNode root;
    private String highlightCat;
    private Consumer<String> onCategoryClicked;

    /* ---------- ctor ---------- */
    public TreemapPane() {
        getChildren().add(cv);
        widthProperty().addListener((o, ov, nv) -> redraw());
        heightProperty().addListener((o, ov, nv) -> redraw());
        cv.addEventHandler(MouseEvent.MOUSE_MOVED, this::onMove);
        cv.addEventHandler(MouseEvent.MOUSE_CLICKED, this::onClick);
        setStyle("-fx-background-color:#fafafa;");
    }

    /* ---------- API ---------- */
    public void setRoot(FileNode r) {
        root = r;
        requestLayout();          // provoca layoutChildren()

        // 1ª pasada: si todavía medimos 0×0, esperamos a la 1ª
        // dimensión “real” para dibujar (one-shot listener).
        if (getWidth() < 5 || getHeight() < 5) {
            ChangeListener<Number> once = new ChangeListener<>() {
                @Override
                public void changed(ObservableValue<? extends Number> o, Number ov, Number nv) {
                    if (getWidth() >= 5 && getHeight() >= 5) {
                        redraw();
                        widthProperty().removeListener(this);
                        heightProperty().removeListener(this);
                    }
                }
            };
            widthProperty().addListener(once);
            heightProperty().addListener(once);
        } else {
            redraw();
        }
    }

    public void setHighlightCategory(String c) {
        highlightCat = c;
        redraw();
    }

    public void setOnCategoryClicked(Consumer<String> cb) {
        onCategoryClicked = cb;
    }

    /* ---------- layout ---------- */
    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        cv.setWidth(getWidth());
        cv.setHeight(getHeight());
        redraw();                 // tamaño ya actualizado
    }


    // TreemapPane.java

    private void redraw() {
        double W = getWidth(), H = getHeight();
        GraphicsContext g = cv.getGraphicsContext2D();
        g.clearRect(0, 0, W, H);
        rects.clear();

        if (root == null || W < 5 || H < 5) return;

        List<FileNode> junk = new ArrayList<>();
        collect(root, junk);
        if (junk.isEmpty()) return;
        junk.sort(Comparator.comparingLong(FileNode::getSize).reversed());

        long totalBytes = junk.stream().mapToLong(FileNode::getSize).sum();
        if (totalBytes == 0) return;  // sólo 0 B

        double areaT = W * H;
        Map<FileNode, Double> area = new HashMap<>();
        List<FileNode> draw = new ArrayList<>();

        // ---> Incluir todos los nodos, aunque salgan muy pequeñitos <---
        for (FileNode n : junk) {
            double a = areaT * n.getSize() / totalBytes;
            area.put(n, a);
            draw.add(n);
        }
        if (draw.isEmpty()) return;

        long drawBytes = draw.stream().mapToLong(FileNode::getSize).sum();
        for (FileNode n : draw) {
            area.put(n, areaT * n.getSize() / drawBytes);   // reescalar
        }

        split(draw, 0, draw.size() - 1, 0, 0, W, H, area);

        for (Rect r : rects) {
            g.setFill(color(r.n.getCategory()));
            g.fillRect(r.x, r.y, r.w, r.h);

            boolean sel = highlightCat != null && highlightCat.equals(r.n.getCategory());
            g.setStroke(sel ? Color.RED : Color.WHITE);
            g.setLineWidth(sel ? 2 : 1);
            g.strokeRect(r.x + (sel ? 1 : 0), r.y + (sel ? 1 : 0), r.w - (sel ? 2 : 0), r.h - (sel ? 2 : 0));

            if (r.w > 48 && r.h > 20) {
                g.setFill(Color.BLACK);
                g.fillText(r.n.getDisplayName(), r.x + 4, r.y + 14);
            }
        }
    }


    /* ---------- interacción ---------- */
    private void onMove(MouseEvent e) {
        for (Rect r : rects)
            if (r.contains(e.getX(), e.getY())) {
                tip.setText(r.n.getFile().getAbsolutePath() + '\n' + FileNode.convertToHumanReadable(r.n.getSize()) + "\n\n" + CategoryInfo.get(r.n.getCategory()).desc() + "\n" + (CategoryInfo.get(r.n.getCategory()).risk() == CategoryInfo.Risk.SAFE ? "✅ Seguro de borrar" : "⚠️ Revisa antes de borrar"));
                Tooltip.install(cv, tip);
                tip.setShowDelay(Duration.millis(40));
                return;
            }
        Tooltip.uninstall(cv, tip);
    }

    private void onClick(MouseEvent e) {
        for (Rect r : rects)
            if (r.contains(e.getX(), e.getY())) {
                if (onCategoryClicked != null) onCategoryClicked.accept(r.n.getCategory());
                break;
            }
    }

    /* ---------- helpers ---------- */
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
            case "Miniaturas del sistema" -> Color.LIGHTPINK;
            case "Objetos compilados" -> Color.LIGHTSEAGREEN;
            case "Archivos vacíos" -> Color.SILVER;
            case "Carpetas vacías" -> Color.DARKGOLDENROD;
            case "Atajos rotos" -> Color.LIGHTCORAL;
            default -> Color.LIGHTGRAY;
        };
    }

    private void split(List<FileNode> list, int from, int to, double x, double y, double w, double h, Map<FileNode, Double> area) {

        // Si no hay nada que pintar o la región es inválida, corta.
        if (from > to || w <= 0 || h <= 0) return;

        // Caso base: un único elemento ocupa toda la región restante
        if (from == to) {
            rects.add(new Rect(list.get(from), x, y, w, h));
            return;
        }

        // Calcular área total de [from..to]
        double total = 0;
        for (int i = from; i <= to; i++) {
            total += area.getOrDefault(list.get(i), 0.0);
        }
        if (total <= 0) return;  // todo a 0 B

        // Encontrar pivot: punto donde la suma parcial >= total/2
        double acc = 0;
        int pivot = from;
        for (int i = from; i <= to; i++) {
            acc += area.get(list.get(i));
            if (acc >= total / 2) {
                pivot = i;
                break;
            }
        }

        // Asegurar que siempre acortamos el rango
        if (pivot == from) {
            pivot++;
        } else if (pivot == to) {
            pivot--;
        }

        // Si tras el ajuste el rango es inválido, salimos
        if (pivot < from || pivot >= to) {
            // Como última salvaguarda, tratamos todos como uno solo
            rects.add(new Rect(list.get(from), x, y, w, h));
            return;
        }

        // Particionar según orientación
        if (w >= h) {
            double w1 = w * (acc / total);
            // izquierda: [from..pivot], derecha: [pivot+1..to]
            split(list, from, pivot, x, y, w1, h, area);
            split(list, pivot + 1, to, x + w1, y, w - w1, h, area);
        } else {
            double h1 = h * (acc / total);
            // arriba:   [from..pivot], abajo: [pivot+1..to]
            split(list, from, pivot, x, y, w, h1, area);
            split(list, pivot + 1, to, x, y + h1, w, h - h1, area);
        }
    }
}
