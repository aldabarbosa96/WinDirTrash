module org.example.windirtrash {
    requires javafx.controls;
    requires javafx.fxml;

    requires org.controlsfx.controls;
    requires java.desktop;

    opens org.example.windirtrash to javafx.fxml;
    exports org.example.windirtrash;
    exports org.example.windirtrash.controller;
    opens org.example.windirtrash.controller to javafx.fxml;
}