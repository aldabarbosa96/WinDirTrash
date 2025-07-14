module org.example.windirtrash {
    requires javafx.controls;
    requires javafx.fxml;

    requires org.controlsfx.controls;
    requires java.desktop;

    opens org.example.windirtrash to javafx.fxml;
    opens org.example.windirtrash.controller to javafx.fxml;
    opens org.example.windirtrash.view to javafx.fxml;
    opens org.example.windirtrash.utils to javafx.fxml;
    exports org.example.windirtrash;
    exports org.example.windirtrash.controller;
}