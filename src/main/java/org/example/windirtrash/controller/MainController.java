package org.example.windirtrash.controller;

import javafx.application.Platform;
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
import org.example.windirtrash.task.FileScannerTask;
import org.example.windirtrash.utils.CategoryInfo;
import org.example.windirtrash.view.TreemapPane;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Controlador principal – selector de unidad, árbol por **categorías**
 * y treemap sincronizado (estilo WinDirStat).
 */
public class MainController {

    /* ──────────────────────────── FXML ──────────────────────────── */
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

    /* Índice categoría → TreeItem (para saltar rápido) */
    private final Map<String, TreeItem<String>> catItems = new HashMap<>();

    /* ============================================================= */
    /*  initialize()                                                 */
    /* ============================================================= */
    @FXML
    private void initialize() {

        /* 1‒ Selector de unidades ------------------------------------ */
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

        /* 2‒ Estado inicial de paneles ------------------------------- */
        progressBar.setVisible(false);
        statusLabel.setText("Listo.");
        mainSplit.setVisible(false);
        placeholderBox.managedProperty().bind(placeholderBox.visibleProperty());
        mainSplit.managedProperty().bind(mainSplit.visibleProperty());

        /* 3‒ TreeView con icono de riesgo ---------------------------- */
        treeView.setCellFactory(tv -> {
            Image imgSafe = new Image(MainApp.class.getResource("/org/example/windirtrash/icons/good.png").toExternalForm(), 16, 16, true, true);
            Image imgRisk = new Image(MainApp.class.getResource("/org/example/windirtrash/icons/risk.png").toExternalForm(), 16, 16, true, true);
            ImageView ivSafe = new ImageView(imgSafe), ivRisk = new ImageView(imgRisk);
            Tooltip tt = new Tooltip();

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
                    if (!getTreeItem().isLeaf()) {           // categoría
                        String cat = item.split(" \\(")[0];
                        var meta = CategoryInfo.get(cat);
                        tt.setText(meta.desc());
                        setTooltip(tt);
                        setGraphic(meta.risk() == CategoryInfo.Risk.SAFE ? ivSafe : ivRisk);
                    } else {                                  // archivo
                        setGraphic(null);
                        setTooltip(null);
                        setStyle("-fx-text-fill: crimson;");
                    }
                }
            };
        });

        /* 4‒ Menú contextual eliminar -------------------------------- */
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

        /* 5‒ Sincronía Treemap ↔ TreeView --------------------------- */
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

    /* ============================================================= */
    /*  Menú “Archivo / Ayuda”                                       */
    /* ============================================================= */
    @FXML
    private void onSalir() {
        ((Stage) treeView.getScene().getWindow()).close();
    }

    @FXML
    private void onAcercaDe() {
        new Alert(Alert.AlertType.INFORMATION, "WinDirTrash v0.1\nDesarrollado por Manito").showAndWait();
    }

    /* ============================================================= */
    /*  Escaneo                                                      */
    /* ============================================================= */
    @FXML
    private void onEscanear() {

        File selDrive = driveChoice.getValue();
        if (selDrive == null) return;

        /* mover selector arriba la 1ª vez */
        if (!topBar.getChildren().contains(driveBar)) {
            placeholderBox.getChildren().remove(driveBar);
            topBar.getChildren().add(driveBar);
            topBar.setVisible(true);
            topBar.setManaged(true);
        }

        /* advertencia si es unidad raíz */
        if (selDrive.toPath().getParent() == null) {
            Alert w = new Alert(Alert.AlertType.CONFIRMATION, "Escanear toda la unidad " + selDrive.getPath() + " puede tardar bastante y requerir permisos.\n¿Continuar?");
            w.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            if (w.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) return;
        }

        /* preparar UI */
        placeholderBox.setVisible(false);
        mainSplit.setVisible(true);
        Platform.runLater(() -> mainSplit.setDividerPositions(0.55));

        catItems.clear();
        treemapPane.setRoot(null);
        treemapPane.setHighlightCategory(null);

        /* ▲ crear raíz para evitar NPE en addJunkToUI() */
        TreeItem<String> rootTI = new TreeItem<>("Basura encontrada");
        rootTI.setExpanded(true);
        treeView.setRoot(rootTI);
        treeView.setShowRoot(false);

        /* tarea de escaneo */
        FileScannerTask task = new FileScannerTask(selDrive.toPath(), this::addJunkToUI);

        progressBar.progressProperty().bind(task.progressProperty());
        progressBar.setVisible(true);
        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> {
            FileNode rootNode = task.getValue();
            treemapPane.setRoot(rootNode);
            finishScan("Escaneo completado");
        });
        task.setOnFailed(e -> finishScan("Error: " + task.getException().getMessage()));

        new Thread(task, "scan").start();
    }

    private void finishScan(String msg) {
        progressBar.progressProperty().unbind();
        progressBar.setVisible(false);
        statusLabel.textProperty().unbind();
        statusLabel.setText(msg);

        if (treeView.getRoot() != null && treeView.getRoot().getChildren().isEmpty()) {
            /* sin basura encontrada */
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

    /* ============================================================= */
    /*  Árbol incremental por categorías                             */
    /* ============================================================= */
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

    /* ============================================================= */
    /*  Eliminación                                                  */
    /* ============================================================= */
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

        /* aviso de seguridad --------------------------------------- */
        String cat = sel.isLeaf() ? sel.getParent().getValue().split(" \\(")[0] : sel.getValue().split(" \\(")[0];
        boolean review = CategoryInfo.get(cat).risk() == CategoryInfo.Risk.REVIEW;

        Alert dlg = new Alert(Alert.AlertType.CONFIRMATION);
        dlg.setHeaderText(review ? "⚠️ Hay elementos que conviene revisar antes de borrar." : "✅ Los elementos seleccionados son seguros de borrar.");
        dlg.setContentText("Mover a Papelera permite recuperar.\nEliminar borra definitivamente.");

        ButtonType btTrash = new ButtonType("Mover a Papelera"), btDel = new ButtonType("Eliminar");
        dlg.getButtonTypes().setAll(btTrash, btDel, ButtonType.CANCEL);
        ButtonType choice = dlg.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == ButtonType.CANCEL) return;

        boolean toTrash = choice == btTrash;
        boolean trashOK = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        if (toTrash && !trashOK) {
            Alert w = new Alert(Alert.AlertType.WARNING, "Tu sistema no soporta la Papelera.\nSe usará eliminación permanente.", ButtonType.OK, ButtonType.CANCEL);
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
            } catch (Exception ex) {
                fail++;
            }
        }
        statusLabel.setText((toTrash ? "A Papelera: " : "Eliminados: ") + ok + (fail > 0 ? " | Fallos: " + fail : ""));

        /* limpiar árbol -------------------------------------------- */
        if (whole && !sel.isLeaf()) sel.getParent().getChildren().remove(sel);
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
        }
    }

    /* ============================================================= */
    /*  Utilidades                                                   */
    /* ============================================================= */
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
        int i = disp.lastIndexOf(" (");
        return Paths.get((i < 0 ? disp : disp.substring(0, i)).trim());
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
}
