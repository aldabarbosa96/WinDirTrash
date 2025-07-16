package org.example.windirtrash.controller;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.example.windirtrash.MainApp;
import org.example.windirtrash.model.FileNode;
import org.example.windirtrash.task.DeleteTask;
import org.example.windirtrash.task.FileScannerTask;
import org.example.windirtrash.utils.CategoryInfo;
import org.example.windirtrash.view.TreemapPane;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class MainController {

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
    @FXML
    private ChoiceBox<File> driveChoice;
    @FXML
    private Button scanBtn;
    @FXML
    private HBox driveBar;
    @FXML
    private HBox topBar;
    @FXML
    private Button autoCleanBtn;

    private final Map<String, TreeItem<String>> catItems = new HashMap<>();
    private List<FileNode> lastJunk = List.of();

    @FXML
    private void initialize() {
        // Selector de unidades
        driveChoice.getItems().setAll(File.listRoots());
        if (!driveChoice.getItems().isEmpty()) {
            driveChoice.getSelectionModel().selectFirst();
        }
        driveChoice.setConverter(new StringConverter<>() {
            @Override
            public String toString(File f) {
                long free = f.getFreeSpace() / (1024L * 1024 * 1024);
                long tot = f.getTotalSpace() / (1024L * 1024 * 1024);
                return String.format("%s  (%d GB libres de %d GB)", f.getPath(), free, tot);
            }

            @Override
            public File fromString(String s) {
                return null;
            }
        });
        scanBtn.disableProperty().bind(driveChoice.getSelectionModel().selectedItemProperty().isNull());

        // Estado inicial
        progressBar.setVisible(false);
        mainSplit.setVisible(false);
        statusLabel.setText("Listo.");
        placeholderBox.managedProperty().bind(placeholderBox.visibleProperty());
        mainSplit.managedProperty().bind(mainSplit.visibleProperty());
        autoCleanBtn.setVisible(false);
        autoCleanBtn.setManaged(false);

        // TreeView con iconos de riesgo
        treeView.setCellFactory(tv -> {
            Image ok = new Image(MainApp.class.getResource("/org/example/windirtrash/icons/good.png").toExternalForm(), 16, 16, true, true);
            Image warn = new Image(MainApp.class.getResource("/org/example/windirtrash/icons/risk.png").toExternalForm(), 16, 16, true, true);
            ImageView ivOk = new ImageView(ok), ivWarn = new ImageView(warn);
            Tooltip tip = new Tooltip();
            return new TreeCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                        setTooltip(null);
                        setStyle("");
                        return;
                    }
                    setText(item);
                    if (!getTreeItem().isLeaf()) {
                        String cat = item.split(" \\(")[0];
                        var meta = CategoryInfo.get(cat);
                        setGraphic(meta.risk() == CategoryInfo.Risk.SAFE ? ivOk : ivWarn);
                        tip.setText(meta.desc());
                        setTooltip(tip);
                    } else {
                        setGraphic(null);
                        setTooltip(null);
                        setStyle("-fx-text-fill: crimson;");
                    }
                }
            };
        });

        // Menú contextual en TreeView
        MenuItem miDel = new MenuItem("Eliminar seleccionado");
        MenuItem miCat = new MenuItem("Eliminar categoría completa");
        miDel.setOnAction(e -> deleteSelected(false));
        miCat.setOnAction(e -> deleteSelected(true));
        ContextMenu ctx = new ContextMenu(miDel, miCat);
        treeView.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY && treeView.getRoot() != null) {
                ctx.show(treeView, e.getScreenX(), e.getScreenY());
            } else {
                ctx.hide();
            }
        });

        // Sincronía Treemap ↔ TreeView
        treemapPane.setOnCategoryClicked(cat -> {
            TreeItem<String> ti = catItems.get(cat);
            if (ti != null) {
                treeView.getSelectionModel().select(ti);
                treeView.scrollTo(treeView.getRow(ti));
            }
        });
        treeView.getSelectionModel().selectedItemProperty().addListener((ob, o, n) -> {
            if (n == null) {
                treemapPane.setHighlightCategory(null);
            } else {
                String cat = n.isLeaf() ? n.getParent().getValue().split(" \\(")[0] : n.getValue().split(" \\(")[0];
                treemapPane.setHighlightCategory(cat);
            }
        });
    }

    @FXML
    private void onSalir() {
        ((Stage) treeView.getScene().getWindow()).close();
    }

    @FXML
    private void onAcercaDe() {
        new Alert(Alert.AlertType.INFORMATION, "WinDirTrash v0.2\nDesarrollado por Manito").showAndWait();
    }

    @FXML
    private void onEscanear() {
        File selDrive = driveChoice.getValue();
        if (selDrive == null) return;

        // Reubica selector en la barra superior la primera vez
        if (!topBar.getChildren().contains(driveBar)) {
            placeholderBox.getChildren().remove(driveBar);
            topBar.getChildren().add(0, driveBar);
            topBar.setVisible(true);
            topBar.setManaged(true);
        }

        // Confirmación si es raíz
        if (selDrive.toPath().getParent() == null) {
            var w = new Alert(Alert.AlertType.CONFIRMATION, "Escanear " + selDrive.getPath() + " completo puede tardar.\n¿Continuar?");
            w.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            if (w.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) return;
        }

        // Reset UI
        placeholderBox.setVisible(false);
        mainSplit.setVisible(true);
        Platform.runLater(() -> mainSplit.setDividerPositions(0.55));
        catItems.clear();
        treemapPane.setRoot(null);
        treemapPane.setHighlightCategory(null);
        treeView.setRoot(new TreeItem<>("Basura encontrada") {{
            setExpanded(true);
        }});
        treeView.setShowRoot(false);
        autoCleanBtn.setVisible(false);
        autoCleanBtn.setManaged(false);

        // Tarea de escaneo
        FileScannerTask scanTask = new FileScannerTask(selDrive.toPath());
        progressBar.progressProperty().bind(scanTask.progressProperty());
        statusLabel.textProperty().bind(scanTask.messageProperty());
        progressBar.setVisible(true);

        scanTask.setOnSucceeded(ev -> {
            FileNode root = scanTask.getValue();
            lastJunk = scanTask.getJunkFound();

            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            statusLabel.setText("Construyendo vista…");
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

            // Pintado por lotes
            Task<Void> paint = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    final int BATCH = 25;
                    for (int i = 0; i < lastJunk.size(); i += BATCH) {
                        int end = Math.min(i + BATCH, lastJunk.size());
                        var slice = List.copyOf(lastJunk.subList(i, end));
                        Platform.runLater(() -> slice.forEach(MainController.this::addJunkToUI));
                        updateProgress(end, lastJunk.size());
                        Thread.sleep(8);
                    }
                    return null;
                }
            };
            progressBar.progressProperty().bind(paint.progressProperty());
            paint.setOnSucceeded(e2 -> {
                progressBar.setVisible(false);
                statusLabel.setText("Listo.");
                treemapPane.setRoot(root);

                boolean hasSafe = lastJunk.stream().anyMatch(fn -> CategoryInfo.get(fn.getCategory()).risk() == CategoryInfo.Risk.SAFE);
                autoCleanBtn.setVisible(hasSafe);
                autoCleanBtn.setManaged(hasSafe);
            });
            paint.setOnFailed(e2 -> {
                progressBar.setVisible(false);
                statusLabel.setText("Error vista: " + paint.getException().getMessage());
            });
            new Thread(paint, "paint-ui").start();
        });

        scanTask.setOnFailed(ev -> {
            progressBar.setVisible(false);
            statusLabel.textProperty().unbind();
            statusLabel.setText("Error: " + scanTask.getException().getMessage());
        });

        new Thread(scanTask, "scan").start();
    }

    /**
     * Borrado seguro: muestra número de elementos y espacio liberado antes de confirmar.
     */
    @FXML
    private void onBorrarSeguro() {
        var safeNodes = lastJunk.stream().filter(fn -> CategoryInfo.get(fn.getCategory()).risk() == CategoryInfo.Risk.SAFE).collect(Collectors.toList());

        if (safeNodes.isEmpty()) {
            statusLabel.setText("Nada que borrar automáticamente.");
            return;
        }

        int count = safeNodes.size();
        long totalBytes = safeNodes.stream().mapToLong(FileNode::getSize).sum();
        String humanSize = FileNode.convertToHumanReadable(totalBytes);

        Alert conf = new Alert(Alert.AlertType.CONFIRMATION);
        conf.setHeaderText("Auto‑limpieza segura");
        conf.setContentText(String.format("Se eliminarán %d elementos seguros\n– liberando %s de espacio.\n¿Continuar?", count, humanSize));
        conf.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        if (conf.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) {
            return;
        }

        var safePaths = safeNodes.stream().map(fn -> fn.getFile().toPath()).distinct().collect(Collectors.toList());
        runAutoDeleteTask(safePaths);
    }

    private void runAutoDeleteTask(List<Path> tgt) {
        DeleteTask d = new DeleteTask(tgt, false);
        progressBar.progressProperty().bind(d.progressProperty());
        statusLabel.textProperty().bind(d.messageProperty());
        progressBar.setVisible(true);

        d.setOnSucceeded(ev -> {
            progressBar.setVisible(false);
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            statusLabel.setText("Auto‑limpieza completada.");
            autoCleanBtn.setVisible(false);
            autoCleanBtn.setManaged(false);
            onEscanear();  // refresca la vista
        });
        d.setOnFailed(ev -> {
            progressBar.setVisible(false);
            statusLabel.textProperty().unbind();
            statusLabel.setText("Error auto‑limpieza: " + d.getException().getMessage());
        });
        new Thread(d, "auto-clean").start();
    }

    private void addJunkToUI(FileNode n) {
        String cat = n.getCategory() == null ? "Otros" : n.getCategory();
        TreeItem<String> catItem = catItems.computeIfAbsent(cat, c -> {
            var ti = new TreeItem<String>(c);
            treeView.getRoot().getChildren().add(ti);
            return ti;
        });

        String label = n.getFile().getAbsolutePath() + " (" + FileNode.convertToHumanReadable(n.getSize()) + ')';
        catItem.getChildren().add(new TreeItem<>(label));

        long total = catItem.getChildren().stream().map(TreeItem::getValue).mapToLong(this::bytesFromDisplay).sum();
        catItem.setValue(cat + " (" + FileNode.convertToHumanReadable(total) + ')');
    }

    @FXML
    private void onLimpiar() {
        deleteSelected(false);
    }

    private void deleteSelected(boolean wholeCat) {
        TreeItem<String> sel = treeView.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        List<Path> tgt = new ArrayList<>();
        if (wholeCat && !sel.isLeaf()) {
            sel.getChildren().forEach(c -> tgt.add(extractPath(c.getValue())));
        } else if (sel.isLeaf()) {
            tgt.add(extractPath(sel.getValue()));
        } else {
            statusLabel.setText("Selecciona un archivo o categoría.");
            return;
        }

        String cat = sel.isLeaf() ? sel.getParent().getValue().split(" \\(")[0] : sel.getValue().split(" \\(")[0];
        boolean review = CategoryInfo.get(cat).risk() == CategoryInfo.Risk.REVIEW;

        Alert dlg = new Alert(Alert.AlertType.CONFIRMATION);
        dlg.setHeaderText(review ? "⚠️ Revisa antes de borrar." : "✅ Seguro de borrar.");
        dlg.setContentText("Mover a Papelera permite recuperar.\nEliminar borra definitivamente.");
        ButtonType btTrash = new ButtonType("Mover a Papelera");
        ButtonType btDel = new ButtonType("Eliminar");
        dlg.getButtonTypes().setAll(btTrash, btDel, ButtonType.CANCEL);

        ButtonType choice = dlg.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == ButtonType.CANCEL) return;

        boolean toTrash = (choice == btTrash);
        if (toTrash && !(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH))) {
            Alert w = new Alert(Alert.AlertType.WARNING, "Tu sistema no soporta la Papelera.\nSe aplicará borrado permanente.", ButtonType.OK, ButtonType.CANCEL);
            if (w.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) return;
            toTrash = false;
        }

        runDeleteTask(tgt, toTrash, sel, wholeCat);
    }

    private void runDeleteTask(List<Path> tgt, boolean toTrash, TreeItem<String> sel, boolean wholeCat) {
        DeleteTask dt = new DeleteTask(tgt, toTrash);
        progressBar.progressProperty().bind(dt.progressProperty());
        statusLabel.textProperty().bind(dt.messageProperty());
        progressBar.setVisible(true);

        dt.setOnSucceeded(ev -> {
            progressBar.setVisible(false);
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            statusLabel.setText(dt.getMessage());
            Platform.runLater(() -> removeNodesFromTree(sel, wholeCat));
        });
        dt.setOnFailed(ev -> {
            progressBar.setVisible(false);
            statusLabel.textProperty().unbind();
            statusLabel.setText("Error al eliminar: " + dt.getException().getMessage());
        });
        new Thread(dt, "delete").start();
    }

    private void removeNodesFromTree(TreeItem<String> sel, boolean wholeCat) {
        if (wholeCat && !sel.isLeaf()) {
            sel.getParent().getChildren().remove(sel);
        } else {
            TreeItem<String> parent = sel.getParent();
            parent.getChildren().remove(sel);
            if (parent.getChildren().isEmpty() && parent.getParent() != null) {
                parent.getParent().getChildren().remove(parent);
            }
        }
        if (treeView.getRoot() != null && treeView.getRoot().getChildren().isEmpty()) {
            treeView.setRoot(null);
            mainSplit.setVisible(false);
            placeholderBox.setVisible(true);
            treemapPane.setRoot(null);
            topBar.getChildren().remove(driveBar);
            placeholderBox.getChildren().add(driveBar);
            topBar.setVisible(false);
            topBar.setManaged(false);
            autoCleanBtn.setVisible(false);
            autoCleanBtn.setManaged(false);
        }
    }

    private static Path extractPath(String disp) {
        int i = disp.lastIndexOf(" (");
        return Path.of((i < 0 ? disp : disp.substring(0, i)).trim());
    }

    private long bytesFromDisplay(String d) {
        int i1 = d.lastIndexOf('('), i2 = d.lastIndexOf(')');
        if (i1 < 0 || i2 < 0) return 0;
        String[] p = d.substring(i1 + 1, i2).split(" ");
        double v = Double.parseDouble(p[0].replace(',', '.'));
        return switch (p[1]) {
            case "KB" -> (long) (v * 1024);
            case "MB" -> (long) (v * 1024 * 1024);
            case "GB" -> (long) (v * 1024 * 1024 * 1024);
            case "TB" -> (long) (v * 1024L * 1024 * 1024 * 1024);
            default -> (long) v;
        };
    }
}
