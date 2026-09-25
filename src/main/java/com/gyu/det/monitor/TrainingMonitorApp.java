package com.gyu.det.monitor;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import java.io.Reader;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

public final class TrainingMonitorApp extends Application {
    private final TextField sshCommand = new TextField("ssh user@192.168.1.100 \"nvidia-smi; tail -f /path/to/train.log\"");
    private static final String DEFAULT_PROJECT_NAME = "GYU-DET YOLO11-V2";
    private static final String DEFAULT_PROJECT_PATH = "/root/gyu-yolo11-v2";
    private static final String DEFAULT_PYTHON_PATH = "/root/gyu-paper-env/bin/python";
    private static final String DEFAULT_SNAPSHOT_SCRIPT = "monitor_snapshot.py";
    private final TextField projectName = new TextField(DEFAULT_PROJECT_NAME);
    private final TextField projectPath = new TextField(DEFAULT_PROJECT_PATH);
    private final TextField pythonPath = new TextField(DEFAULT_PYTHON_PATH);
    private final TextField snapshotScript = new TextField(DEFAULT_SNAPSHOT_SCRIPT);
    private final ComboBox<String> monitorMode = new ComboBox<>();
    private final TextArea bashCommand = new TextArea("watch -n 1 -t nvidia-smi");
    private final VBox modeEditor = new VBox(8);
    private final PasswordField password = new PasswordField();
    private final CheckBox trustUnknownHost = new CheckBox("信任未知主机");
    private final CheckBox autoScroll = new CheckBox("自动滚动");
    private final CheckBox autoStartMonitor = new CheckBox("连接后自动执行");
    private final Button connectButton = new Button("▶  连接并开始监视");
    private final Button saveButton = new Button("保存配置");
    private final Button clearButton = new Button("清空");
    private final Button sendButton = new Button("发送");
    private final Button trainingViewButton = new Button("训练输出");
    private final Button systemViewButton = new Button("整机状态");
    private final Label panelTitle = new Label("训练输出");
    private final Label panelDescription = new Label("远程训练输出与错误信息会实时显示在这里");
    private final TextField remoteInput = new TextField();
    private final Label status = new Label("未连接");
    private final Label connectionHint = new Label("输入 SSH 命令后即可开始");
    private final TextArea output = new TextArea();
    private final VBox snapshotOutput = new VBox(14);
    private SystemStatusDashboard systemStatusDashboard;
    private SystemNotificationService notificationService;
    private GpuIdleNotifier gpuIdleNotifier;
    private String latestSnapshotJson;
    private String latestSnapshotProjectName;
    private volatile boolean systemViewSelected;
    private final AtomicReference<SshMonitor> activeMonitor = new AtomicReference<>();
    private Thread monitorThread;
    private volatile boolean running;
    private boolean screenRefreshMode;
    private final StringBuilder screenBuffer = new StringBuilder();
    private static final Path CONFIG_FILE = Path.of(System.getProperty("user.home"), ".gyu-det", "training-monitor.properties");

    @Override
    public void start(Stage stage) {
        stage.setTitle("训练监视器");
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        stage.getIcons().add(createWindowIcon());

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setTop(buildHeader());
        root.setCenter(buildMainContent());
        loadConfig();

        Scene scene = new Scene(root, 1360, 860);
        scene.getStylesheets().add(getClass().getResource("/monitor.css").toExternalForm());
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            stopMonitor();
            if (notificationService != null) notificationService.close();
        });
        stage.show();
        notificationService = new SystemNotificationService(stage);
    }

    private HBox buildHeader() {
        StackPane logo = new StackPane();
        logo.getStyleClass().add("logo-mark");
        Rectangle screen = new Rectangle(21, 16);
        screen.setArcWidth(3);
        screen.setArcHeight(3);
        screen.setFill(Color.web("#092218"));
        Rectangle screenFill = new Rectangle(17, 12);
        screenFill.setArcWidth(1);
        screenFill.setArcHeight(1);
        screenFill.setFill(Color.web("#baf6d8"));
        Polyline activity = new Polyline(-6.5, 1, -2.5, 1, 0, -3, 3, 3, 6.5, -2);
        activity.setStroke(Color.web("#159c68"));
        activity.setStrokeWidth(1.8);
        activity.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        StackPane display = new StackPane(screen, screenFill, activity);
        display.setTranslateY(-1);
        Rectangle stand = new Rectangle(3, 3);
        stand.setFill(Color.web("#092218"));
        stand.setTranslateY(10);
        Rectangle base = new Rectangle(11, 2);
        base.setArcWidth(2);
        base.setArcHeight(2);
        base.setFill(Color.web("#092218"));
        base.setTranslateY(13);
        logo.getChildren().addAll(display, stand, base);
        Label title = new Label("训练监视器");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("SSH 远程训练状态实时查看");
        subtitle.getStyleClass().add("app-subtitle");
        VBox text = new VBox(2, title, subtitle);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label live = new Label("●  LIVE");
        live.getStyleClass().add("live-badge");
        HBox header = new HBox(12, logo, text, spacer, live);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("top-header");
        return header;
    }

    private WritableImage createWindowIcon() {
        Canvas canvas = new Canvas(32, 32);
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.setFill(Color.web("#39d98a"));
        graphics.fillRoundRect(1, 1, 30, 30, 9, 9);
        graphics.setFill(Color.web("#092218"));
        graphics.fillRoundRect(7, 7, 18, 14, 3, 3);
        graphics.setStroke(Color.web("#baf6d8"));
        graphics.setLineWidth(2);
        graphics.strokePolyline(new double[]{9, 16, 13, 16, 16, 11, 19, 17, 23, 12},
                new double[]{16, 16, 12, 18, 13, 17, 11, 15, 15}, 5);
        graphics.setFill(Color.web("#092218"));
        graphics.fillRoundRect(14, 22, 4, 4, 1, 1);
        graphics.fillRoundRect(11, 26, 10, 2, 1, 1);
        WritableImage icon = new WritableImage(32, 32);
        canvas.snapshot(null, icon);
        return icon;
    }

    private HBox buildMainContent() {
        VBox sideCard = buildConnectionCard();
        javafx.scene.control.ScrollPane side = new javafx.scene.control.ScrollPane(sideCard);
        side.setFitToWidth(true);
        side.setPrefWidth(370);
        side.setMinWidth(330);
        side.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        side.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        side.getStyleClass().add("side-scroll");
        VBox monitor = buildMonitorCard();
        HBox.setHgrow(monitor, Priority.ALWAYS);
        HBox content = new HBox(18, side, monitor);
        content.setPadding(new Insets(18, 22, 22, 22));
        content.getStyleClass().add("main-content");
        return content;
    }

    private VBox buildConnectionCard() {
        Label eyebrow = new Label("CONNECTION");
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("服务器连接");
        title.getStyleClass().add("section-title");
        Label description = new Label("配置 SSH 连接信息，连接后实时接收远程终端输出。");
        description.getStyleClass().add("muted-text");
        description.setWrapText(true);

        Label commandLabel = fieldLabel("SSH 命令");
        sshCommand.setPromptText("ssh user@host \"训练监视命令\"");
        sshCommand.setPrefHeight(40);

        Label passwordLabel = fieldLabel("连接密码");
        password.setPromptText("输入服务器 SSH 密码");
        password.setPrefHeight(40);

        Label modeLabel = fieldLabel("监视方式");
        monitorMode.getItems().setAll("结构化训练监视", "传统 Bash");
        monitorMode.getSelectionModel().selectFirst();
        monitorMode.setMaxWidth(Double.MAX_VALUE);
        monitorMode.setPrefHeight(36);
        bashCommand.setPromptText("例如：\nwatch -n 1 -t nvidia-smi\ntail -f train.log\npython monitor.py");
        bashCommand.setPrefRowCount(10);
        bashCommand.setWrapText(true);
        monitorMode.valueProperty().addListener((obs, oldValue, newValue) -> refreshModeEditor());
        refreshModeEditor();

        trustUnknownHost.setSelected(true);
        autoScroll.setSelected(true);
        autoStartMonitor.setSelected(true);
        HBox checks = new HBox(16, trustUnknownHost, autoScroll, autoStartMonitor);
        checks.getStyleClass().add("check-row");

        saveButton.getStyleClass().add("secondary-button");
        saveButton.setPrefHeight(44);
        saveButton.setOnAction(event -> saveConfig());
        connectButton.setPrefHeight(44);
        connectButton.getStyleClass().add("primary-button");
        connectButton.setOnAction(event -> toggleMonitor());
        HBox actions = new HBox(8, saveButton, connectButton);
        HBox.setHgrow(connectButton, Priority.ALWAYS);
        connectButton.setMaxWidth(Double.MAX_VALUE);
        connectionHint.getStyleClass().add("connection-hint");
        connectionHint.setWrapText(true);

        VBox tips = new VBox(8,
                new Label("支持的格式"),
                new Label("ssh user@host \"远程命令\""),
                new Label("ssh -p 2222 user@host \"远程命令\""),
                new Label("ssh -l user -p 2222 host \"远程命令\""),
                new Label("训练快照每 0.5 秒刷新；整机状态每 0.25 秒刷新"),
                new Label("快照脚本缺失时自动读取项目状态"),
                new Label("命令和密码会以明文离线保存"));
        tips.getStyleClass().add("tips-box");
        tips.getChildren().get(0).getStyleClass().add("tips-title");
        for (int i = 1; i < tips.getChildren().size(); i++) tips.getChildren().get(i).getStyleClass().add("tip-line");

        VBox card = new VBox(12, eyebrow, title, description, modeLabel, monitorMode,
                commandLabel, sshCommand, passwordLabel, password, new Separator(), modeEditor,
                checks, actions, connectionHint, tips);
        card.getStyleClass().add("connection-card");
        card.setPrefWidth(340);
        card.setMinWidth(300);
        return card;
    }

    private VBox buildMonitorCard() {
        Label eyebrow = new Label("MONITOR OUTPUT");
        eyebrow.getStyleClass().add("eyebrow");
        panelTitle.getStyleClass().add("section-title");
        panelDescription.getStyleClass().add("muted-text");
        VBox heading = new VBox(3, eyebrow, panelTitle, panelDescription);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        clearButton.getStyleClass().add("secondary-button");
        clearButton.setOnAction(event -> {
            output.clear();
            snapshotOutput.getChildren().clear();
            latestSnapshotJson = null;
        });
        HBox titleRow = new HBox(10, heading, spacer, clearButton);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        output.setEditable(false);
        output.setWrapText(true);
        output.setPromptText("连接服务器后，训练日志会显示在这里…");
        output.getStyleClass().add("terminal");
        snapshotOutput.getStyleClass().add("snapshot-output");
        snapshotOutput.setVisible(false);
        snapshotOutput.setManaged(false);
        javafx.scene.control.ScrollPane snapshotScroll = new javafx.scene.control.ScrollPane(snapshotOutput);
        snapshotScroll.setFitToWidth(true);
        snapshotScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        snapshotScroll.getStyleClass().add("snapshot-scroll");
        StackPane terminal = new StackPane(output, snapshotScroll);
        terminal.getStyleClass().add("terminal-shell");
        VBox.setVgrow(terminal, Priority.ALWAYS);

        Label outputTitle = new Label("TERMINAL  /  LIVE STREAM");
        outputTitle.getStyleClass().add("terminal-title");
        trainingViewButton.getStyleClass().add("view-toggle");
        systemViewButton.getStyleClass().add("view-toggle");
        trainingViewButton.setOnAction(event -> selectRightView(false));
        systemViewButton.setOnAction(event -> selectRightView(true));
        HBox viewToggles = new HBox(4, trainingViewButton, systemViewButton);
        viewToggles.getStyleClass().add("view-toggle-group");
        updateViewToggleStyles();
        Region outputSpacer = new Region();
        HBox.setHgrow(outputSpacer, Priority.ALWAYS);
        HBox outputToolbar = new HBox(10, outputTitle, outputSpacer, viewToggles);
        outputToolbar.setAlignment(Pos.CENTER_LEFT);
        remoteInput.setPromptText("交互式 Shell 输入（例如 watch -n 1 nvidia-smi）");
        remoteInput.setPrefHeight(36);
        remoteInput.setDisable(true);
        sendButton.getStyleClass().add("secondary-button");
        sendButton.setPrefHeight(36);
        sendButton.setDisable(true);
        sendButton.setOnAction(event -> sendRemoteInput());
        remoteInput.setOnAction(event -> sendRemoteInput());
        HBox inputRow = new HBox(8, remoteInput, sendButton);
        HBox.setHgrow(remoteInput, Priority.ALWAYS);

        status.getStyleClass().add("status-label");
        HBox footer = new HBox(8, status);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("monitor-footer");

        VBox card = new VBox(14, titleRow, outputToolbar, terminal, inputRow, footer);
        card.getStyleClass().add("monitor-card");
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private void refreshModeEditor() {
        boolean bash = "传统 Bash".equals(monitorMode.getValue());
        modeEditor.getChildren().clear();
        if (bash) {
            Label label = fieldLabel("Bash 命令（支持多行）");
            modeEditor.getChildren().addAll(label, bashCommand);
            bashCommand.setDisable(false);
            return;
        }

        projectName.setPrefHeight(36);
        projectPath.setPrefHeight(36);
        pythonPath.setPrefHeight(36);
        snapshotScript.setPrefHeight(36);
        modeEditor.getChildren().addAll(
                fieldLabel("项目名称"), projectName,
                fieldLabel("项目路径"), projectPath,
                fieldLabel("Python 环境路径"), pythonPath,
                fieldLabel("训练快照脚本"), snapshotScript);
        bashCommand.setDisable(true);
    }

    private Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    private void toggleMonitor() {
        if (running) stopMonitor(); else startMonitor();
    }

    private void startMonitor() {
        final SshCommand parsed;
        try {
            parsed = SshCommand.parse(sshCommand.getText());
        } catch (IllegalArgumentException ex) {
            showError(ex.getMessage());
            return;
        }
        if (parsed.user().isBlank()) {
            showError("SSH 命令中请包含登录名，例如 ssh root@server -p 2222");
            return;
        }
        char[] secret = password.getText().toCharArray();
        if (secret.length == 0) {
            showError("请输入连接密码");
            return;
        }
        if (!saveConfig()) {
            java.util.Arrays.fill(secret, '\0');
            return;
        }
        boolean structuredMode = "结构化训练监视".equals(monitorMode.getValue());
        String startupMonitorCommand = structuredMode ? buildSnapshotCommand() : bashCommand.getText().trim();
        String fallbackSnapshotCommand = structuredMode ? buildGenericSnapshotCommand() : "";
        String configuredProjectName = projectName.getText().trim();
        boolean startMonitorAutomatically = autoStartMonitor.isSelected() && !startupMonitorCommand.isBlank();
        boolean snapshotMode = parsed.interactiveShell() && structuredMode && startMonitorAutomatically;

        latestSnapshotJson = null;
        latestSnapshotProjectName = configuredProjectName;
        systemStatusDashboard = new SystemStatusDashboard(configuredProjectName);
        gpuIdleNotifier = structuredMode ? new GpuIdleNotifier() : null;
        selectRightView(false);

        running = true;
        showTerminalText();
        connectButton.setText("■  停止监视");
        connectButton.getStyleClass().remove("primary-button");
        connectButton.getStyleClass().add("stop-button");
        sshCommand.setDisable(true);
        password.setDisable(true);
        trustUnknownHost.setDisable(true);
        saveButton.setDisable(true);
        remoteInput.setDisable(snapshotMode || !parsed.interactiveShell());
        sendButton.setDisable(snapshotMode || !parsed.interactiveShell());
        connectionHint.setText(startMonitorAutomatically
                ? "正在连接，并准备执行：" + startupMonitorCommand
                : "正在连接 " + parsed.user() + "@" + parsed.host() + "…");
        setStatus("连接中", true);
        appendOutput("\n[连接] " + parsed.user() + "@" + parsed.host() + ":" + parsed.port() + "\n");
        boolean trust = trustUnknownHost.isSelected();
        monitorThread = new Thread(() -> {
            try {
                if (snapshotMode) {
                    runSnapshotMonitor(parsed, secret, trust, startupMonitorCommand,
                            fallbackSnapshotCommand, configuredProjectName);
                } else {
                    runStreamingMonitor(parsed, secret, trust, startMonitorAutomatically, startupMonitorCommand);
                }
            } finally {
                activeMonitor.set(null);
                java.util.Arrays.fill(secret, '\0');
                Platform.runLater(this::resetControlsAfterMonitor);
            }
        }, "ssh-training-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private String buildSnapshotCommand() {
        String interpreter = pythonPath.getText().trim();
        String path = projectPath.getText().trim().replaceAll("/+$", "");
        String script = snapshotScript.getText().trim().replaceAll("^/+", "");
        if (interpreter.isBlank() || path.isBlank() || script.isBlank()) return "";
        try (InputStream source = getClass().getResourceAsStream("/snapshot_wrapper.py")) {
            if (source == null) return shellQuote(interpreter) + " " + shellQuote(path + "/" + script);
            String encoded = Base64.getEncoder().encodeToString(source.readAllBytes());
            String runner = "import base64;exec(base64.b64decode(\"" + encoded + "\"))";
            return shellQuote(interpreter) + " -c " + shellQuote(runner) + " " + shellQuote(path + "/" + script);
        } catch (Exception ignored) {
            return shellQuote(interpreter) + " " + shellQuote(path + "/" + script);
        }
    }

    private String buildGenericSnapshotCommand() {
        String interpreter = pythonPath.getText().trim();
        String path = projectPath.getText().trim();
        if (interpreter.isBlank() || path.isBlank()) return "";
        try (InputStream source = getClass().getResourceAsStream("/generic_snapshot.py")) {
            if (source == null) return "";
            String encoded = Base64.getEncoder().encodeToString(source.readAllBytes());
            return shellQuote(interpreter)
                    + " -c 'import base64;exec(base64.b64decode(\"" + encoded + "\"))' "
                    + shellQuote(path);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void runSnapshotMonitor(SshCommand parsed, char[] secret, boolean trust,
                                    String snapshotCommand, String fallbackCommand,
                                    String configuredProjectName) {
        int failures = 0;
        String activeCommand = snapshotCommand;
        while (running && !Thread.currentThread().isInterrupted()) {
            try (SshMonitor monitor = new SshMonitor(parsed, secret, trust)) {
                activeMonitor.set(monitor);
                monitor.poll(activeCommand, () -> systemViewSelected ? 250L : 500L,
                        raw -> publishSnapshot(raw, configuredProjectName),
                        text -> Platform.runLater(() -> setStatus(text, true)));
                failures = 0;
            } catch (Exception ex) {
                if (!running || Thread.currentThread().isInterrupted()) break;
                if (activeCommand.equals(snapshotCommand) && !fallbackCommand.isBlank()
                        && isMissingSnapshotScript(ex)) {
                    activeCommand = fallbackCommand;
                    failures = 0;
                    Platform.runLater(() -> {
                        connectionHint.setText("远程快照脚本不存在，已自动切换为通用状态读取");
                        setStatus("正在读取项目状态", true);
                    });
                    continue;
                }
                int currentFailures = ++failures;
                Platform.runLater(() -> {
                    if (gpuIdleNotifier != null) gpuIdleNotifier.resetIdleTimer();
                    showTerminalText();
                    output.setText(SnapshotFormatter.formatFailure(ex, currentFailures, configuredProjectName));
                    output.positionCaret(0);
                    setStatus("连接失败，1 秒后自动重试", false);
                });
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } finally {
                activeMonitor.set(null);
            }
        }
    }

    private static boolean isMissingSnapshotScript(Exception error) {
        String message = error.getMessage();
        return message != null && message.contains("can't open file")
                && message.contains("No such file or directory");
    }

    private void publishSnapshot(String rawJson, String configuredProjectName) {
        try {
            SnapshotFormatter.Frame frame = SnapshotFormatter.render(rawJson, configuredProjectName);
            Platform.runLater(() -> {
                showSnapshot(rawJson, configuredProjectName, frame.text());
                if (running && gpuIdleNotifier != null) {
                    String notification = gpuIdleNotifier.observe(rawJson, System.currentTimeMillis());
                    if (notification != null && notificationService != null) {
                        notificationService.notify("GPU 已空闲", notification);
                    }
                }
                connectionHint.setText(systemViewSelected
                        ? "整机状态每 0.25 秒刷新；训练快照每 0.5 秒刷新"
                        : "训练快照每 0.5 秒刷新；选择整机状态可提高到 0.25 秒");
                setStatus("训练监视中", true);
            });
        } catch (Exception ex) {
            Platform.runLater(() -> {
                showTerminalText();
                output.setText("训练快照格式解析失败：\n" + safeMessage(ex) + "\n\n原始输出：\n" + rawJson);
                output.positionCaret(0);
                setStatus("训练快照解析失败", false);
            });
        }
    }

    private void showSnapshot(String rawJson, String configuredProjectName, String fallbackText) {
        try {
            latestSnapshotJson = rawJson;
            latestSnapshotProjectName = configuredProjectName;
            if (systemStatusDashboard == null) systemStatusDashboard = new SystemStatusDashboard(configuredProjectName);
            systemStatusDashboard.update(rawJson);
            renderSelectedSnapshot();
            output.setVisible(false);
            output.setManaged(false);
            snapshotOutput.setVisible(true);
            snapshotOutput.setManaged(true);
            snapshotScrollToggle(true);
        } catch (Exception ex) {
            showTerminalText();
            output.setText(fallbackText);
            output.positionCaret(0);
        }
    }

    private void showTerminalText() {
        systemViewSelected = false;
        panelTitle.setText("训练输出");
        panelDescription.setText("远程训练输出与错误信息会实时显示在这里");
        updateViewToggleStyles();
        output.setVisible(true);
        output.setManaged(true);
        snapshotOutput.setVisible(false);
        snapshotOutput.setManaged(false);
        snapshotScrollToggle(false);
    }

    private void selectRightView(boolean systemView) {
        systemViewSelected = systemView;
        panelTitle.setText(systemView ? "整机状态" : "训练输出");
        panelDescription.setText(systemView ? "GPU、CPU 与系统内存的实时使用情况" : "远程训练输出与错误信息会实时显示在这里");
        updateViewToggleStyles();
        if (latestSnapshotJson != null) {
            renderSelectedSnapshot();
            output.setVisible(false);
            output.setManaged(false);
            snapshotOutput.setVisible(true);
            snapshotOutput.setManaged(true);
            snapshotScrollToggle(true);
        } else if (systemView) {
            if (systemStatusDashboard == null) systemStatusDashboard = new SystemStatusDashboard(latestSnapshotProjectName);
            snapshotOutput.getChildren().setAll(systemStatusDashboard.node());
            output.setVisible(false);
            output.setManaged(false);
            snapshotOutput.setVisible(true);
            snapshotOutput.setManaged(true);
            snapshotScrollToggle(true);
        } else {
            showTerminalText();
        }
    }

    private void renderSelectedSnapshot() {
        if (latestSnapshotJson == null) return;
        try {
            Node view;
            if (systemViewSelected) {
                if (systemStatusDashboard == null) systemStatusDashboard = new SystemStatusDashboard(latestSnapshotProjectName);
                view = systemStatusDashboard.node();
                if (snapshotOutput.getChildren().size() == 1 && snapshotOutput.getChildren().get(0) == view) return;
            } else {
                view = SnapshotDashboard.create(latestSnapshotJson, latestSnapshotProjectName, "");
            }
            snapshotOutput.getChildren().setAll(view);
        } catch (Exception ex) {
            Label error = new Label("快照解析失败：" + safeMessage(ex));
            error.getStyleClass().add("snapshot-queue-warning");
            error.setWrapText(true);
            snapshotOutput.getChildren().setAll(error);
        }
    }

    private void updateViewToggleStyles() {
        trainingViewButton.getStyleClass().remove("view-toggle-selected");
        systemViewButton.getStyleClass().remove("view-toggle-selected");
        (systemViewSelected ? systemViewButton : trainingViewButton).getStyleClass().add("view-toggle-selected");
    }

    private void snapshotScrollToggle(boolean visible) {
        if (output.getParent() instanceof StackPane stack) {
            for (javafx.scene.Node child : stack.getChildren()) {
                if (child instanceof javafx.scene.control.ScrollPane scroll && child != null) {
                    scroll.setVisible(visible);
                    scroll.setManaged(visible);
                }
            }
        }
    }

    private void runStreamingMonitor(SshCommand parsed, char[] secret, boolean trust,
                                     boolean startMonitorAutomatically, String startupMonitorCommand) {
        try (SshMonitor monitor = new SshMonitor(parsed, secret, trust)) {
            activeMonitor.set(monitor);
            monitor.stream(text -> Platform.runLater(() -> appendOutput(text)),
                    text -> Platform.runLater(() -> {
                        setStatus(text, true);
                        if (startMonitorAutomatically && parsed.interactiveShell()
                                && text.startsWith("已连接，进入交互式 Shell")) {
                            sendStartupMonitorCommand(startupMonitorCommand);
                        }
                    }));
        } catch (Exception ex) {
            if (!running) return;
            Platform.runLater(() -> {
                appendOutput("\n[错误] " + safeMessage(ex) + "\n");
                setStatus("连接失败或已断开", false);
            });
        }
    }

    private void stopMonitor() {
        SshMonitor monitor = activeMonitor.getAndSet(null);
        if (monitor != null) monitor.close();
        if (monitorThread != null) monitorThread.interrupt();
        screenRefreshMode = false;
        if (running) appendOutput("\n[系统] 已停止监视\n");
        running = false;
        resetControlsAfterMonitor();
        setStatus("已停止", false);
    }

    private void resetControlsAfterMonitor() {
        running = false;
        connectButton.setText("▶  连接并开始监视");
        connectButton.getStyleClass().remove("stop-button");
        if (!connectButton.getStyleClass().contains("primary-button")) connectButton.getStyleClass().add("primary-button");
        sshCommand.setDisable(false);
        password.setDisable(false);
        trustUnknownHost.setDisable(false);
        saveButton.setDisable(false);
        remoteInput.clear();
        remoteInput.setDisable(true);
        sendButton.setDisable(true);
        screenRefreshMode = false;
        screenBuffer.setLength(0);
        connectionHint.setText("输入 SSH 命令后即可开始");
    }

    private void sendRemoteInput() {
        String text = remoteInput.getText().trim();
        if (text.isBlank()) return;
        SshMonitor monitor = activeMonitor.get();
        if (monitor == null) {
            showError("交互式 Shell 尚未连接");
            return;
        }
        try {
            configureScreenMode(text);
            monitor.sendInput(text);
            remoteInput.clear();
        } catch (Exception ex) {
            appendOutput("\n[错误] " + safeMessage(ex) + "\n");
        }
    }

    private void sendStartupMonitorCommand(String command) {
        SshMonitor monitor = activeMonitor.get();
        if (monitor == null) return;
        try {
            configureScreenMode(command);
            monitor.sendInput(command);
            connectionHint.setText("监视命令已启动，刷新间隔 0.5 秒");
        } catch (Exception ex) {
            appendOutput("\n[自动监视命令错误] " + safeMessage(ex) + "\n");
            setStatus("自动监视命令执行失败", false);
        }
    }

    private void appendOutput(String text) {
        if (screenRefreshMode) {
            appendScreenOutput(text);
            return;
        }
        output.appendText(text);
        if (autoScroll.isSelected()) output.positionCaret(output.getLength());
    }

    private void configureScreenMode(String command) {
        String lower = command.toLowerCase(java.util.Locale.ROOT);
        screenRefreshMode = lower.contains("watch ") || lower.contains("top") || lower.contains("htop");
        screenBuffer.setLength(0);
        if (screenRefreshMode) output.clear();
    }

    private void appendScreenOutput(String text) {
        screenBuffer.append(text);
        String esc = String.valueOf((char) 27);
        String[] resetSequences = {esc + "[2J", esc + "[1;1H", esc + "[1;1f", esc + "[H"};
        int lastIndex = -1;
        String lastSequence = null;
        for (String sequence : resetSequences) {
            int index = screenBuffer.lastIndexOf(sequence);
            if (index > lastIndex) {
                lastIndex = index;
                lastSequence = sequence;
            }
        }
        if (lastIndex >= 0 && lastSequence != null) {
            screenBuffer.delete(0, lastIndex + lastSequence.length());
        }
        if (screenBuffer.length() > 200_000) {
            screenBuffer.delete(0, screenBuffer.length() - 200_000);
        }
        String clean = stripAnsi(screenBuffer.toString())
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        output.setText(clean);
        if (autoScroll.isSelected()) output.positionCaret(output.getLength());
    }

    private static String stripAnsi(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != 27) {
                result.append(current);
                continue;
            }
            if (i + 1 >= value.length()) break;
            char next = value.charAt(++i);
            if (next == '[') {
                while (++i < value.length()) {
                    char end = value.charAt(i);
                    if (end >= 0x40 && end <= 0x7e) break;
                }
            } else if (next == ']') {
                while (++i < value.length()) {
                    char end = value.charAt(i);
                    if (end == 7) break;
                    if (end == 27 && i + 1 < value.length() && value.charAt(i + 1) == '\\') {
                        i++;
                        break;
                    }
                }
            }
        }
        return result.toString();
    }

    private void showError(String message) {
        setStatus(message, false);
        connectionHint.setText(message);
    }

    private void setStatus(String text, boolean success) {
        status.setText(text == null ? "" : text);
        status.getStyleClass().removeAll("status-ok", "status-error");
        status.getStyleClass().add(success ? "status-ok" : "status-error");
    }

    private void loadConfig() {
        if (!Files.isRegularFile(CONFIG_FILE)) return;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE, StandardCharsets.UTF_8)) {
            properties.load(reader);
            sshCommand.setText(properties.getProperty("ssh.command", sshCommand.getText()));
            password.setText(properties.getProperty("ssh.password", ""));
            projectName.setText(properties.getProperty("project.name", projectName.getText()));
            projectPath.setText(properties.getProperty("project.path", projectPath.getText()));
            pythonPath.setText(properties.getProperty("project.python", pythonPath.getText()));
            snapshotScript.setText(properties.getProperty("project.snapshotScript", snapshotScript.getText()));
            String savedMode = properties.getProperty("monitor.mode", "结构化训练监视");
            monitorMode.getSelectionModel().select("传统 Bash".equals(savedMode) ? "传统 Bash" : "结构化训练监视");
            bashCommand.setText(properties.getProperty("bash.command", bashCommand.getText()));
            trustUnknownHost.setSelected(Boolean.parseBoolean(properties.getProperty("ssh.trustUnknownHost", "true")));
            autoScroll.setSelected(Boolean.parseBoolean(properties.getProperty("ui.autoScroll", "true")));
            autoStartMonitor.setSelected(Boolean.parseBoolean(properties.getProperty("monitor.autoStart", "true")));
            connectionHint.setText("已加载本地配置");
        } catch (Exception ex) {
            connectionHint.setText("本地配置读取失败：" + safeMessage(ex));
        }
    }

    private boolean saveConfig() {
        Properties properties = new Properties();
        properties.setProperty("ssh.command", sshCommand.getText());
        properties.setProperty("ssh.password", password.getText());
        properties.setProperty("project.name", projectName.getText());
        properties.setProperty("project.path", projectPath.getText());
        properties.setProperty("project.python", pythonPath.getText());
        properties.setProperty("project.snapshotScript", snapshotScript.getText());
        properties.setProperty("monitor.mode", monitorMode.getValue());
        properties.setProperty("bash.command", bashCommand.getText());
        properties.setProperty("ssh.trustUnknownHost", Boolean.toString(trustUnknownHost.isSelected()));
        properties.setProperty("ui.autoScroll", Boolean.toString(autoScroll.isSelected()));
        properties.setProperty("monitor.autoStart", Boolean.toString(autoStartMonitor.isSelected()));
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE, StandardCharsets.UTF_8)) {
                properties.store(writer, "GYU-DET Training Monitor local configuration - plaintext by user request");
            }
            connectionHint.setText("配置已保存到本机：" + CONFIG_FILE);
            setStatus("配置已保存", true);
            return true;
        } catch (Exception ex) {
            showError("配置保存失败：" + safeMessage(ex));
            return false;
        }
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
