package org.example.windirtrash.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.example.windirtrash.model.FileNode;
import org.example.windirtrash.task.FileScannerTask;
import org.example.windirtrash.utils.CategoryInfo;
import org.example.windirtrash.view.TreemapPane;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class MainController {

    /* ─── UI ──────────────────────────────────────────────────────────── */
    @FXML
    private TreeView<String> treeView;
    @FXML
    private VBox placeholderBox;
    @FXML
    private SplitPane mainSplit;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private Label statusLabel;
    @FXML
    private TreemapPane treemapPane;

    private final Map<String, TreeItem<String>> catItems = new HashMap<>();

    /* ─── init ────────────────────────────────────────────────────────── */
    @FXML
    private void initialize() {

        progressBar.setVisible(false);
        statusLabel.setText("Listo.");
        mainSplit.setVisible(false);
        placeholderBox.managedProperty().bind(placeholderBox.visibleProperty());
        mainSplit.managedProperty().bind(mainSplit.visibleProperty());

        treeView.setCellFactory(tv -> {
            // Tooltip único por celda
            Tooltip tt = new Tooltip();
            TreeCell<String> cell = new TreeCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);

                    if (empty || item == null) {            // celda vacía
                        setText(null);
                        setTooltip(null);
                        setStyle("");
                        return;
                    }

                    setText(item);

                    /* ----- categorías (no-leaf) ----- */
                    if (!getTreeItem().isLeaf()) {
                        String cat = item.contains(" (") ? item.substring(0, item.indexOf(" (")) : item;
                        tt.setText(CategoryInfo.DESC.getOrDefault(cat, "Sin descripción."));
                        setTooltip(tt);
                        setStyle("");
                    } else {
                        setTooltip(null);
                        setStyle("-fx-text-fill: crimson;");
                    }
                }
            };
            return cell;
        });



        /* menú contextual */
        MenuItem miDel = new MenuItem("Eliminar seleccionado");
        MenuItem miCat = new MenuItem("Eliminar categoría completa");
        miDel.setOnAction(e -> deleteSelected(false));
        miCat.setOnAction(e -> deleteSelected(true));
        ContextMenu ctx = new ContextMenu(miDel, miCat);

        treeView.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY && treeView.getRoot() != null)
                ctx.show(treeView, e.getScreenX(), e.getScreenY());
            else ctx.hide();
        });

    }

    /* ─── menú superior ──────────────────────────────────────────────── */
    @FXML
    private void onSalir() {
        ((Stage) treeView.getScene().getWindow()).close();
    }

    @FXML
    private void onAcercaDe() {
        new Alert(Alert.AlertType.INFORMATION, "WindirTrash v0.1\nDesarrollado por Manito").showAndWait();
    }

    /* ─── Escanear ───────────────────────────────────────────────────── */
    @FXML
    private void onEscanear() {

        placeholderBox.setVisible(false);
        mainSplit.setVisible(true);

        catItems.clear();
        TreeItem<String> rootTI = new TreeItem<>("Basura encontrada");
        rootTI.setExpanded(true);
        treeView.setRoot(rootTI);
        treeView.setShowRoot(false);

        Path home = Paths.get(System.getProperty("user.home"));
        FileScannerTask task = new FileScannerTask(home, this::addJunkToUI);

        progressBar.progressProperty().bind(task.progressProperty());
        progressBar.setVisible(true);
        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> {
            finishScan("Escaneo completado");
            treemapPane.setRoot(task.getValue());      // ← solo UNA vez
        });
        task.setOnFailed(e -> finishScan("Error: " + task.getException().getMessage()));

        new Thread(task, "scan").start();
    }

    private void finishScan(String msg) {
        progressBar.progressProperty().unbind();
        progressBar.setVisible(false);
        statusLabel.textProperty().unbind();
        statusLabel.setText(msg);

        if (treeView.getRoot().getChildren().isEmpty()) {
            treeView.setRoot(null);
            mainSplit.setVisible(false);
            placeholderBox.setVisible(true);
        }
    }

    /* inserción incremental SOLO al árbol (ya no al treemap) */
    private void addJunkToUI(FileNode n) {
        String cat = n.getCategory() == null ? "Otros" : n.getCategory();
        TreeItem<String> catItem = catItems.computeIfAbsent(cat, c -> {
            TreeItem<String> ti = new TreeItem<>(c);
            treeView.getRoot().getChildren().add(ti);
            return ti;
        });

        String label = n.getFile().getAbsolutePath() + " (" + FileNode.convertToHumanReadable(n.getSize()) + ")";
        catItem.getChildren().add(new TreeItem<>(label));

        long total = catItem.getChildren().stream().map(TreeItem::getValue).mapToLong(this::bytesFromDisplay).sum();
        catItem.setValue(cat + " (" + FileNode.convertToHumanReadable(total) + ")");
    }

    private long bytesFromDisplay(String disp) {
        int i1 = disp.lastIndexOf('('), i2 = disp.lastIndexOf(')');
        if (i1 < 0 || i2 < 0) return 0;
        String[] p = disp.substring(i1 + 1, i2).split(" ");
        double v = Double.parseDouble(p[0].replace(',', '.'));
        return switch (p[1]) {
            case "KB" -> (long) (v * 1024);
            case "MB" -> (long) (v * 1024 * 1024);
            case "GB" -> (long) (v * 1024 * 1024 * 1024);
            case "TB" -> (long) (v * 1024L * 1024 * 1024 * 1024);
            default -> (long) v;
        };
    }

    /* ─── Eliminación / Papelera (sin cambios de lógica) ─────────────── */
    @FXML
    private void onLimpiar() {
        deleteSelected(false);
    }

    private void deleteSelected(boolean whole) {
        TreeItem<String> sel = treeView.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        List<Path> tgt = new ArrayList<>();
        if (whole && !sel.isLeaf()) sel.getChildren().forEach(c -> tgt.add(extractPath(c.getValue())));
        else if (sel.isLeaf()) tgt.add(extractPath(sel.getValue()));
        else {
            statusLabel.setText("Selecciona un archivo o categoría.");
            return;
        }

        Alert dlg = new Alert(Alert.AlertType.CONFIRMATION, "Mover a Papelera permite recuperar.\nEliminar borra definitivamente.");
        ButtonType btTrash = new ButtonType("Mover a Papelera");
        ButtonType btDel = new ButtonType("Eliminar");
        dlg.getButtonTypes().setAll(btTrash, btDel, ButtonType.CANCEL);

        ButtonType choice = dlg.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == ButtonType.CANCEL) return;

        boolean toTrash = choice == btTrash;
        boolean trashOK = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        if (toTrash && !trashOK) {
            Alert w = new Alert(Alert.AlertType.WARNING, "Tu sistema no soporta Papelera.\nSe usará eliminación permanente.", ButtonType.OK, ButtonType.CANCEL);
            if (w.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) return;
            toTrash = false;
        }

        int ok = 0, fail = 0;
        for (Path p : tgt) {
            try {
                if (toTrash) {
                    if (Files.exists(p) && Desktop.getDesktop().moveToTrash(p.toFile())) ok++;
                    else fail++;
                } else {
                    recursiveDelete(p);
                    ok++;
                }
            } catch (IllegalArgumentException | IOException ex) {
                fail++;
            }
        }
        statusLabel.setText((toTrash ? "A Papelera: " : "Eliminados: ") + ok + (fail > 0 ? " | Fallos: " + fail : ""));

        /* limpia el árbol */
        if (whole && !sel.isLeaf()) sel.getParent().getChildren().remove(sel);
        else {
            TreeItem<String> cat = sel.getParent();
            cat.getChildren().remove(sel);
            if (cat.getChildren().isEmpty()) cat.getParent().getChildren().remove(cat);
        }
        if (treeView.getRoot() != null && treeView.getRoot().getChildren().isEmpty()) {
            treeView.setRoot(null);
            mainSplit.setVisible(false);
            placeholderBox.setVisible(true);
            treemapPane.setRoot(null);
        }
    }

    /* utilidades */
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
        } else Files.deleteIfExists(p);
    }

    private static Path extractPath(String disp) {
        int idx = disp.lastIndexOf(" (");
        return Paths.get((idx < 0 ? disp : disp.substring(0, idx)).trim());
    }
}
