package org.example.windirtrash;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class MainApp extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(MainApp.class.getResource("/org/example/windirtrash/views/main-view.fxml"));
        Scene scene = new Scene(loader.load(), 1080, 720);
        scene.getStylesheets().add(MainApp.class.getResource("/org/example/windirtrash/css/theme.css").toExternalForm());

        stageConfig(stage, scene);
    }

    private void stageConfig(Stage stage, Scene scene) {
        stage.setTitle("WindirTrash");
        stage.initStyle(StageStyle.DECORATED);
        stage.setScene(scene);
        stage.show();
    }


    public static void main(String[] args) {
        launch();
    }
}
