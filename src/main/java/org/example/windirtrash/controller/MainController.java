package org.example.windirtrash.controller;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.stage.Stage;
import org.example.windirtrash.model.FileNode;
import org.example.windirtrash.task.FileScannerTask;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class MainController {

    /* ---------- UI -------------------------------------------------------- */
    @FXML private TreeView<String> treeView;
    @FXML private Label            statusLabel;
    @FXML private Label            emptyLabel;
    @FXML private ProgressBar      progressBar;

    /* ---------- Inicialización ------------------------------------------- */
    @FXML
    private void initialize() {

        progressBar.setVisible(false);
        emptyLabel.setVisible(true);
        statusLabel.setText("Listo.");

        treeView.setCellFactory(tv -> new TreeCell<>() {
            @Override protected void updateItem(String t, boolean empty) {
                super.updateItem(t, empty);
                if (empty || t == null) { setText(null); setStyle(""); }
                else { setText(t); setStyle(getTreeItem().isLeaf()? "-fx-text-fill: crimson;" : ""); }
            }
        });

        MenuItem miDel = new MenuItem("Eliminar seleccionado");
        MenuItem miCat = new MenuItem("Eliminar categoría completa");
        miDel.setOnAction(e -> deleteSelected(false));
        miCat.setOnAction(e -> deleteSelected(true));
        ContextMenu ctx = new ContextMenu(miDel, miCat);

        treeView.setOnMouseClicked(e -> {
            if (e.getButton()==MouseButton.SECONDARY && treeView.getRoot()!=null)
                ctx.show(treeView, e.getScreenX(), e.getScreenY());
            else ctx.hide();
        });
    }

    /* ---------- Menú principal ------------------------------------------- */
    @FXML private void onSalir() { ((Stage) treeView.getScene().getWindow()).close(); }

    @FXML
    private void onEscanear() {

        Path home = Paths.get(System.getProperty("user.home"));
        FileScannerTask t = new FileScannerTask(home);

        progressBar.progressProperty().bind(t.progressProperty());
        progressBar.setVisible(true);
        statusLabel.textProperty().bind(t.messageProperty());

        t.setOnSucceeded(e -> {
            progressBar.progressProperty().unbind(); progressBar.setVisible(false);
            statusLabel.textProperty().unbind();     statusLabel.setText("Escaneo completado");
            buildCategoryTree(t.getValue());
        });
        t.setOnFailed(e -> {
            progressBar.progressProperty().unbind(); progressBar.setVisible(false);
            statusLabel.textProperty().unbind();
            statusLabel.setText("Error: "+t.getException().getMessage());
        });

        new Thread(t,"scan").start();
    }

    @FXML private void onLimpiar()  { deleteSelected(false); }
    @FXML private void onAcercaDe() { new Alert(Alert.AlertType.INFORMATION,"WindirTrash v0.1\nDesarrollado por Manito").showAndWait(); }

    /* ---------- Construir árbol por categorías --------------------------- */
    private void buildCategoryTree(FileNode root) {

        Map<String,List<FileNode>> catMap = new LinkedHashMap<>();
        Deque<FileNode> st = new ArrayDeque<>(); st.push(root);
        while (!st.isEmpty()) {
            FileNode n = st.pop();
            if (n.isJunk())
                catMap.computeIfAbsent(n.getCategory()==null?"Otros":n.getCategory(),
                        k->new ArrayList<>()).add(n);
            n.getChildren().forEach(st::push);
        }

        if (catMap.isEmpty()) {
            treeView.setRoot(null);
            emptyLabel.setText("¡No se encontró basura!");
            emptyLabel.setVisible(true);
            return;
        }
        emptyLabel.setVisible(false);

        TreeItem<String> rootItem = new TreeItem<>("Basura encontrada");
        for (var ent : catMap.entrySet()) {
            String cat = ent.getKey();
            long total = ent.getValue().stream().mapToLong(FileNode::getSize).sum();
            TreeItem<String> catItem = new TreeItem<>(cat+" ("+FileNode.convertToHumanReadable(total)+")");
            ent.getValue().forEach(fn ->
                    catItem.getChildren().add(new TreeItem<>(
                            fn.getFile().getAbsolutePath()+" ("+
                                    FileNode.convertToHumanReadable(fn.getSize())+")")));
            rootItem.getChildren().add(catItem);
        }
        rootItem.setExpanded(true);
        treeView.setRoot(rootItem);
        treeView.setShowRoot(false);
    }

    /* ---------- Eliminación / papelera ----------------------------------- */
    private void deleteSelected(boolean wholeCategory) {

        TreeItem<String> sel = treeView.getSelectionModel().getSelectedItem();
        if (sel == null) return;

        List<Path> targets = new ArrayList<>();
        if (wholeCategory && !sel.isLeaf())
            sel.getChildren().forEach(c -> targets.add(extractPath(c.getValue())));
        else if (sel.isLeaf())
            targets.add(extractPath(sel.getValue()));
        else { statusLabel.setText("Selecciona un archivo o categoría."); return; }

        Alert conf = new Alert(Alert.AlertType.CONFIRMATION);
        conf.setHeaderText("¿Qué deseas hacer?");
        conf.setContentText("Mover a Papelera te permitirá recuperar el archivo.\nEliminar lo borra permanentemente.");
        ButtonType btTrash = new ButtonType("Mover a Papelera");
        ButtonType btDel   = new ButtonType("Eliminar");
        conf.getButtonTypes().setAll(btTrash, btDel, ButtonType.CANCEL);

        ButtonType choice = conf.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == ButtonType.CANCEL) return;

        boolean toTrash = (choice == btTrash);
        boolean desktopTrash = Desktop.isDesktopSupported() &&
                Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        if (toTrash && !desktopTrash) {
            Alert w = new Alert(Alert.AlertType.WARNING,
                    "Tu sistema no soporta mover a la Papelera.\nSe realizará eliminación permanente.",
                    ButtonType.OK, ButtonType.CANCEL);
            if (w.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) return;
            toTrash = false;
        }

        int ok=0, fail=0;
        for (Path p : targets) {
            try {
                if (toTrash) {
                    if (Desktop.getDesktop().moveToTrash(p.toFile())) ok++; else fail++;
                } else {
                    recursiveDelete(p); ok++;
                }
            } catch (IOException ex) { fail++; }
        }
        statusLabel.setText((toTrash?"Enviados a Papelera: ":"Eliminados: ")+ok+
                (fail>0?" | Fallos: "+fail:""));

        /* refrescar árbol */
        if (wholeCategory && !sel.isLeaf()) {
            sel.getParent().getChildren().remove(sel);
        } else {
            TreeItem<String> cat = sel.getParent();
            cat.getChildren().remove(sel);
            if (cat.getChildren().isEmpty())
                cat.getParent().getChildren().remove(cat);
        }
        if (treeView.getRoot() != null && treeView.getRoot().getChildren().isEmpty()) {
            treeView.setRoot(null);
            emptyLabel.setText("¡No queda basura!");
            emptyLabel.setVisible(true);
        }
    }

    /* ---------- utilidades ----------------------------------------------- */
    private static void recursiveDelete(Path p) throws IOException {
        if (Files.notExists(p)) return;
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(p, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    Files.deleteIfExists(f); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.deleteIfExists(d); return FileVisitResult.CONTINUE; }
            });
        } else Files.deleteIfExists(p);
    }

    private static Path extractPath(String disp) {
        int idx = disp.lastIndexOf(" (");
        return Paths.get((idx==-1?disp:disp.substring(0,idx)).trim());
    }
}
