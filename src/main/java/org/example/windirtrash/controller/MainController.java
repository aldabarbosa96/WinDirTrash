package org.example.windirtrash.controller;

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
    @FXML
    private ChoiceBox<File> driveChoice;
    @FXML
    private Button scanBtn;
    @FXML
    private HBox driveBar;
    @FXML
    private HBox topBar;

    private final Map<String, TreeItem<String>> catItems = new HashMap<>();

    /* ------------------------------------------------------------------------- */
    /*  initialize()                                                             */
    /* ------------------------------------------------------------------------- */
    @FXML
    private void initialize() {

        /* ---------- 1) poblar lista de unidades / montajes ---------- */
        driveChoice.getItems().setAll(File.listRoots());           // C:\, D:\, / …
        if (!driveChoice.getItems().isEmpty()) driveChoice.getSelectionModel().selectFirst();

        // Texto amigable "C:\  (120 GB libres de 237 GB)"
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

        // Desactivar botón si no hay selección
        scanBtn.disableProperty().bind(driveChoice.getSelectionModel().selectedItemProperty().isNull());

        /* ---------- 2) configuración de la UI existente ------------- */
        progressBar.setVisible(false);
        statusLabel.setText("Listo.");
        mainSplit.setVisible(false);
        placeholderBox.managedProperty().bind(placeholderBox.visibleProperty());
        mainSplit.managedProperty().bind(mainSplit.visibleProperty());

        /* TreeView con iconos de riesgo (sin cambios) */
        treeView.setCellFactory(tv -> {
            Image imgSafe = new Image(Objects.requireNonNull(MainApp.class.getResource("/org/example/windirtrash/icons/good.png")).toExternalForm(), 16, 16, true, true);
            Image imgRisk = new Image(Objects.requireNonNull(MainApp.class.getResource("/org/example/windirtrash/icons/risk.png")).toExternalForm(), 16, 16, true, true);
            ImageView ivSafe = new ImageView(imgSafe);
            ImageView ivRisk = new ImageView(imgRisk);

            Tooltip tt = new Tooltip();
            return new TreeCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setTooltip(null);
                        setGraphic(null);
                        setStyle("");
                        return;
                    }
                    setText(item);
                    if (!getTreeItem().isLeaf()) {
                        String cat = item.split(" \\(")[0];
                        var meta = CategoryInfo.get(cat);
                        tt.setText(meta.desc());
                        setTooltip(tt);
                        setGraphic(meta.risk() == CategoryInfo.Risk.SAFE ? ivSafe : ivRisk);
                    } else {
                        setTooltip(null);
                        setGraphic(null);
                        setStyle("-fx-text-fill: crimson;");
                    }
                }
            };
        });

        /* Menú contextual eliminar ------------------------------ */
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

    /* ------------------------------------------------------------------------- */
    /*  onEscanear()                                                             */
    /* ------------------------------------------------------------------------- */
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

        /* Advertencia si escanea la raíz completa (puede tardar) */
        if (selDrive.toPath().getParent() == null) {
            Alert warn = new Alert(Alert.AlertType.CONFIRMATION, "Escanear toda la unidad " + selDrive.getPath() + " puede tardar bastante y requerir permisos.\n¿Deseas continuar?");
            warn.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            if (warn.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) return;
        }

        /* Preparar UI ----------------------------------------------------- */
        placeholderBox.setVisible(false);
        mainSplit.setVisible(true);

        catItems.clear();
        TreeItem<String> rootTI = new TreeItem<>("Basura encontrada");
        rootTI.setExpanded(true);
        treeView.setRoot(rootTI);
        treeView.setShowRoot(false);

        /* Lanzar tarea de escaneo en la unidad elegida -------------------- */
        Path rootPath = selDrive.toPath();
        FileScannerTask task = new FileScannerTask(rootPath, this::addJunkToUI);

        progressBar.progressProperty().bind(task.progressProperty());
        progressBar.setVisible(true);
        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> {
            finishScan("Escaneo completado");
            treemapPane.setRoot(task.getValue());            // solo una vez
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
        if (treeView.getRoot() == null) {
            topBar.getChildren().remove(driveBar);
            placeholderBox.getChildren().add(driveBar);
            topBar.setVisible(false);
            topBar.setManaged(false);
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

        /* 1. Selección -------------------------------------------------------- */
        TreeItem<String> sel = treeView.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        /* 2. Construir lista de rutas a eliminar ----------------------------- */
        List<Path> tgt = new ArrayList<>();
        if (whole && !sel.isLeaf()) {
            // categoría completa
            sel.getChildren().forEach(c -> tgt.add(extractPath(c.getValue())));
        } else if (sel.isLeaf()) {
            // archivo individual
            tgt.add(extractPath(sel.getValue()));
        } else {
            statusLabel.setText("Selecciona un archivo o categoría.");
            return;
        }

        /* 3. ¿Hay algo que convenga revisar? --------------------------------- */
        // Averiguamos las categorías implicadas
        Set<String> cats = new HashSet<>();
        if (whole && !sel.isLeaf()) {
            cats.add(sel.getValue());                     // la propia categoría
        } else {
            cats.add(sel.getParent().getValue());         // padre del archivo
        }
        boolean anyReview = cats.stream().map(lbl -> lbl.split(" \\(")[0])         // quitar “ (10 MB)”
                .anyMatch(c -> CategoryInfo.get(c).risk() == CategoryInfo.Risk.REVIEW);

        /* 4. Diálogo de confirmación ----------------------------------------- */
        Alert dlg = new Alert(Alert.AlertType.CONFIRMATION);
        dlg.setHeaderText(anyReview ? "⚠️ Hay elementos que conviene revisar antes de borrar." : "✅ Los elementos seleccionados son seguros de borrar.");
        dlg.setContentText("Mover a Papelera permite recuperar.\nEliminar borra definitivamente.");

        ButtonType btTrash = new ButtonType("Mover a Papelera");
        ButtonType btDel = new ButtonType("Eliminar");
        dlg.getButtonTypes().setAll(btTrash, btDel, ButtonType.CANCEL);

        ButtonType choice = dlg.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == ButtonType.CANCEL) return;

        /* 5. Papelera vs eliminación permanente ------------------------------ */
        boolean toTrash = choice == btTrash;
        boolean trashOK = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);

        if (toTrash && !trashOK) {
            Alert w = new Alert(Alert.AlertType.WARNING, "Tu sistema no soporta la Papelera.\nSe usará eliminación permanente.", ButtonType.OK, ButtonType.CANCEL);
            if (w.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) return;
            toTrash = false;
        }

        /* 6. Borrar ----------------------------------------------------------- */
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

        /* 7. Limpiar el árbol ------------------------------------------------- */
        if (whole && !sel.isLeaf()) {                      // se quitó una categoría entera
            sel.getParent().getChildren().remove(sel);
        } else {                                           // archivo individual
            TreeItem<String> cat = sel.getParent();
            cat.getChildren().remove(sel);
            if (cat.getChildren().isEmpty()) cat.getParent().getChildren().remove(cat);
        }

        /* 8. Si ya no queda nada, volver al placeholder ----------------------- */
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
