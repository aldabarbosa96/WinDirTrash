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
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class MainController {

    /* ────────────────  FXML ──────────────── */
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

    /* índice categoría → TreeItem */
    private final Map<String, TreeItem<String>> catItems = new HashMap<>();

    /* ═════════════════════  INIT  ═════════════════════ */
    @FXML
    private void initialize() {

        /* selector de unidades */
        driveChoice.getItems().setAll(File.listRoots());
        if (!driveChoice.getItems().isEmpty()) driveChoice.getSelectionModel().selectFirst();

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

        /* estado inicial */
        progressBar.setVisible(false);
        statusLabel.setText("Listo.");
        mainSplit.setVisible(false);
        placeholderBox.managedProperty().bind(placeholderBox.visibleProperty());
        mainSplit.managedProperty().bind(mainSplit.visibleProperty());

        /* TreeView con iconos riesgo */
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

        /* sync tree <-> treemap */
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
                return;
            }
            String cat = n.isLeaf() ? n.getParent().getValue().split(" \\(")[0] : n.getValue().split(" \\(")[0];
            treemapPane.setHighlightCategory(cat);
        });
    }

    /* ═════════════  Menú  ═════════════ */
    @FXML
    private void onSalir() {
        ((Stage) treeView.getScene().getWindow()).close();
    }

    @FXML
    private void onAcercaDe() {
        new Alert(Alert.AlertType.INFORMATION, "WinDirTrash v0.1\nDesarrollado por Manito").showAndWait();
    }

    /* ═════════════  Escanear  ═════════════ */
    @FXML
    private void onEscanear() {

        File selDrive = driveChoice.getValue();
        if (selDrive == null) return;

        if (!topBar.getChildren().contains(driveBar)) {
            placeholderBox.getChildren().remove(driveBar);
            topBar.getChildren().add(driveBar);
            topBar.setVisible(true);
            topBar.setManaged(true);
        }

        if (selDrive.toPath().getParent() == null) {
            Alert w = new Alert(Alert.AlertType.CONFIRMATION, "Escanear toda la unidad " + selDrive.getPath() + " puede tardar bastante.\n¿Continuar?");
            w.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            if (w.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) return;
        }

        placeholderBox.setVisible(false);
        mainSplit.setVisible(true);
        Platform.runLater(() -> mainSplit.setDividerPositions(0.55));

        catItems.clear();
        treemapPane.setRoot(null);
        treemapPane.setHighlightCategory(null);
        TreeItem<String> rootTI = new TreeItem<>("Basura encontrada");
        rootTI.setExpanded(true);
        treeView.setRoot(rootTI);
        treeView.setShowRoot(false);

        /* --- tarea de escaneo --- */
        FileScannerTask scanTask = new FileScannerTask(selDrive.toPath());
        progressBar.progressProperty().bind(scanTask.progressProperty());
        statusLabel.textProperty().bind(scanTask.messageProperty());
        progressBar.setVisible(true);

        scanTask.setOnSucceeded(ev -> {
            FileNode root = scanTask.getValue();
            List<FileNode> junk = scanTask.getJunkFound();

            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            statusLabel.setText("Construyendo vista…");
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

            Task<Void> paintTask = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    final int BATCH = 25;
                    for (int i = 0; i < junk.size(); i += BATCH) {
                        int end = Math.min(i + BATCH, junk.size());
                        final List<FileNode> batch = List.copyOf(junk.subList(i, end));
                        Platform.runLater(() -> batch.forEach(MainController.this::addJunkToUI));
                        updateProgress(end, junk.size());
                        Thread.sleep(8);
                    }
                    return null;
                }
            };
            progressBar.progressProperty().bind(paintTask.progressProperty());

            paintTask.setOnSucceeded(e2 -> {
                progressBar.setVisible(false);
                statusLabel.setText("Listo.");
                treemapPane.setRoot(root);
            });
            paintTask.setOnFailed(e2 -> {
                progressBar.setVisible(false);
                statusLabel.setText("Error vista: " + paintTask.getException().getMessage());
            });
            new Thread(paintTask, "paint-ui").start();
        });

        scanTask.setOnFailed(ev -> {
            progressBar.setVisible(false);
            statusLabel.textProperty().unbind();
            statusLabel.setText("Error: " + scanTask.getException().getMessage());
        });
        new Thread(scanTask, "scan").start();
    }

    /* ═════════════  Árbol incremental  ═════════════ */
    private void addJunkToUI(FileNode n) {
        String cat = n.getCategory() == null ? "Otros" : n.getCategory();

        TreeItem<String> catItem = catItems.computeIfAbsent(cat, c -> {
            TreeItem<String> ti = new TreeItem<>(c);
            treeView.getRoot().getChildren().add(ti);
            return ti;
        });

        String label = n.getFile().getAbsolutePath() + " (" + FileNode.convertToHumanReadable(n.getSize()) + ')';
        catItem.getChildren().add(new TreeItem<>(label));

        long total = catItem.getChildren().stream().map(TreeItem::getValue).mapToLong(this::bytesFromDisplay).sum();
        catItem.setValue(cat + " (" + FileNode.convertToHumanReadable(total) + ')');
    }

    /* ═════════════  Eliminar  ═════════════ */
    @FXML
    private void onLimpiar() {
        deleteSelected(false);
    }

    private void deleteSelected(boolean wholeCat) {
        TreeItem<String> sel = treeView.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        List<Path> tgt = new ArrayList<>();
        if (wholeCat && !sel.isLeaf()) sel.getChildren().forEach(c -> tgt.add(extractPath(c.getValue())));
        else if (sel.isLeaf()) tgt.add(extractPath(sel.getValue()));
        else {
            statusLabel.setText("Selecciona un archivo o categoría.");
            return;
        }

        String cat = sel.isLeaf() ? sel.getParent().getValue().split(" \\(")[0] : sel.getValue().split(" \\(")[0];
        boolean review = CategoryInfo.get(cat).risk() == CategoryInfo.Risk.REVIEW;

        Alert dlg = new Alert(Alert.AlertType.CONFIRMATION);
        dlg.setHeaderText(review ? "⚠️ Algunos elementos conviene revisarlos." : "✅ Elementos seguros de borrar.");
        dlg.setContentText("Mover a Papelera permite recuperar.\nEliminar borra definitivamente.");
        ButtonType btTrash = new ButtonType("Mover a Papelera"), btDel = new ButtonType("Eliminar");
        dlg.getButtonTypes().setAll(btTrash, btDel, ButtonType.CANCEL);
        ButtonType choice = dlg.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == ButtonType.CANCEL) return;

        boolean toTrash = (choice == btTrash);
        boolean trashOK = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        if (toTrash && !trashOK) {
            Alert w = new Alert(Alert.AlertType.WARNING, "Tu sistema no soporta la Papelera.\nSe usará eliminación permanente.", ButtonType.OK, ButtonType.CANCEL);
            if (w.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) return;
            toTrash = false;
        }
        runDeleteTask(tgt, toTrash, sel, wholeCat);
    }

    /* tarea de borrado */
    private void runDeleteTask(List<Path> tgt, boolean toTrash, TreeItem<String> sel, boolean wholeCat) {

        DeleteTask delTask = new DeleteTask(tgt, toTrash);
        progressBar.progressProperty().bind(delTask.progressProperty());
        statusLabel.textProperty().bind(delTask.messageProperty());
        progressBar.setVisible(true);

        delTask.setOnSucceeded(ev -> {
            progressBar.setVisible(false);
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            statusLabel.setText(delTask.getMessage());
            Platform.runLater(() -> removeNodesFromTree(sel, wholeCat));
        });
        delTask.setOnFailed(ev -> {
            progressBar.setVisible(false);
            statusLabel.textProperty().unbind();
            statusLabel.setText("Error al eliminar: " + delTask.getException().getMessage());
        });
        new Thread(delTask, "delete").start();
    }

    private void removeNodesFromTree(TreeItem<String> sel, boolean wholeCat) {
        if (wholeCat && !sel.isLeaf()) sel.getParent().getChildren().remove(sel);
        else {
            TreeItem<String> parent = sel.getParent();
            parent.getChildren().remove(sel);
            if (parent.getChildren().isEmpty() && parent.getParent() != null)
                parent.getParent().getChildren().remove(parent);
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
        }
    }

    /* ═════════════  util  ═════════════ */
    private static Path extractPath(String disp) {
        int i = disp.lastIndexOf(" (");
        return Paths.get((i < 0 ? disp : disp.substring(0, i)).trim());
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
