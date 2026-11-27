package com.jwh.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.List;

/**
 * 通用无装饰圆角弹窗：替代 JavaFX Alert，避免系统白色标题栏。
 * 深色/浅色随 Ui.DARK 自适应；面板圆角 16，场景透明，标题行自绘，按钮区右对齐。
 *
 * @param <R> 按钮返回值类型
 */
public class JwhDialog<R> {

    /** 弹窗按钮：文案、对应返回值、是否为取消/关闭动作。 */
    public record ButtonDef<R>(String label, R value, boolean cancelClose) {
    }

    private final Stage stage;
    private R result;

    public JwhDialog(String title, Node body, List<ButtonDef<R>> buttons) {
        boolean dark = Ui.DARK;
        String bg = dark ? "#1b1f26" : "#ffffff";
        String text = dark ? "#f0f2f5" : "#1f2328";
        String border = dark ? "rgba(255,255,255,0.14)" : "rgba(31,35,40,0.18)";
        String btnBg = dark ? "rgba(255,255,255,0.10)" : "rgba(31,35,40,0.06)";
        String btnHover = dark ? "rgba(255,255,255,0.18)" : "rgba(31,35,40,0.12)";

        stage = new Stage(StageStyle.TRANSPARENT);

        javafx.scene.control.Label titleLabel = new javafx.scene.control.Label(title == null ? "" : title);
        titleLabel.setStyle("-fx-font-size:15px; -fx-font-weight:bold; -fx-text-fill:" + text + ";");

        HBox titleRow = new HBox(titleLabel);
        titleRow.setPadding(new Insets(18, 20, 0, 20));

        HBox btnRow = new HBox(10);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        for (ButtonDef<R> b : buttons) {
            Button btn = new Button(b.label());
            btn.setStyle("-fx-background-color:" + btnBg + "; -fx-text-fill:" + text
                    + "; -fx-background-radius:6; -fx-padding:6 18; -fx-cursor:hand;");
            btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color:" + btnHover + "; -fx-text-fill:" + text
                    + "; -fx-background-radius:6; -fx-padding:6 18; -fx-cursor:hand;"));
            btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color:" + btnBg + "; -fx-text-fill:" + text
                    + "; -fx-background-radius:6; -fx-padding:6 18; -fx-cursor:hand;"));
            btn.setOnAction(e -> {
                result = b.value();
                stage.close();
            });
            btnRow.getChildren().add(btn);
        }

        VBox box = new VBox(14, titleRow, body, btnRow);
        box.setPadding(new Insets(0, 20, 18, 20));
        box.setPrefWidth(520);
        box.setMinWidth(520);
        box.setStyle("-fx-background-color:" + bg + "; -fx-background-radius:16; -fx-border-radius:16;"
                + " -fx-border-color:" + border + ";");

        Scene scene = new Scene(box);
        scene.setFill(Color.TRANSPARENT);
        Ui.applyStylesheet(scene);
        stage.setScene(scene);
        stage.setResizable(false);
        stage.initModality(Modality.WINDOW_MODAL);

        Window owner = Ui.dialogOwner;
        if (owner == null) {
            for (Window w : Window.getWindows()) {
                if (w instanceof Stage s && s.isShowing()) {
                    owner = w;
                    break;
                }
            }
        }
        if (owner != null && owner.isShowing()) {
            stage.initOwner(owner);
        }
        stage.setOnShown(ev -> {
            Window o = stage.getOwner();
            if (o != null) {
                stage.setX(o.getX() + (o.getWidth() - stage.getWidth()) / 2);
                stage.setY(o.getY() + (o.getHeight() - stage.getHeight()) / 2);
            }
        });
    }

    /** 阻塞显示，返回被点击按钮对应的返回值。 */
    public R showAndWait() {
        stage.showAndWait();
        return result;
    }
}
