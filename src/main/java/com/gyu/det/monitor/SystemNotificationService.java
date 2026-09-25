package com.gyu.det.monitor;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/** Windows notification-area integration with a JavaFX dialog fallback. */
final class SystemNotificationService implements AutoCloseable {
    private final Stage stage;
    private TrayIcon trayIcon;

    SystemNotificationService(Stage stage) {
        this.stage = stage;
        installTrayIcon();
    }

    void notify(String title, String message) {
        TrayIcon icon = trayIcon;
        if (icon != null) {
            icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
            return;
        }
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(title);
            alert.setContentText(message);
            alert.show();
        });
    }

    private void installTrayIcon() {
        if (!SystemTray.isSupported()) return;
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("打开训练监视器");
            open.addActionListener(event -> showWindow());
            MenuItem exit = new MenuItem("退出");
            exit.addActionListener(event -> Platform.runLater(stage::close));
            menu.add(open);
            menu.addSeparator();
            menu.add(exit);

            trayIcon = new TrayIcon(createIcon(), "训练监视器", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(event -> showWindow());
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException | RuntimeException ignored) {
            trayIcon = null;
        }
    }

    private void showWindow() {
        Platform.runLater(() -> {
            if (stage.isIconified()) stage.setIconified(false);
            if (!stage.isShowing()) stage.show();
            stage.toFront();
            stage.requestFocus();
        });
    }

    private static Image createIcon() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(16, 31, 53));
            graphics.fillRoundRect(1, 1, 30, 30, 9, 9);
            graphics.setColor(new Color(55, 217, 144));
            graphics.setStroke(new java.awt.BasicStroke(2.7f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            graphics.drawPolyline(new int[]{6, 12, 17, 22, 27}, new int[]{21, 16, 19, 10, 7}, 5);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    @Override
    public void close() {
        TrayIcon icon = trayIcon;
        trayIcon = null;
        if (icon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(icon);
        }
    }
}
