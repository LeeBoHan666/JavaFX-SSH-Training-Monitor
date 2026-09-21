package com.gyu.det.monitor;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
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
    private final TextField remoteInput = new TextField();
    private final Label status = new Label("未连接");
    private final Label connectionHint = new Label("输入 SSH 命令后即可开始");
    private final TextArea output = new TextArea();
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

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setTop(buildHeader());
        root.setCenter(buildMainContent());
        loadConfig();

        Scene scene = new Scene(root, 1360, 860);
        scene.getStylesheets().add(getClass().getResource("/monitor.css").toExternalForm());
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> stopMonitor());
        stage.show();
    }

    private HBox buildHeader() {
        Label logo = new Label("▣");
        logo.getStyleClass().add("logo-mark");
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
                new Label("默认每 1 秒按原 V2 监视器格式刷新"),
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
        Label title = new Label("训练输出");
        title.getStyleClass().add("section-title");
        Label description = new Label("远程标准输出和错误输出会实时显示在这里");
        description.getStyleClass().add("muted-text");
        VBox heading = new VBox(3, eyebrow, title, description);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        clearButton.getStyleClass().add("secondary-button");
        clearButton.setOnAction(event -> output.clear());
        HBox titleRow = new HBox(10, heading, spacer, clearButton);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        output.setEditable(false);
        output.setWrapText(true);
        output.setPromptText("连接服务器后，训练日志会显示在这里…");
        output.getStyleClass().add("terminal");
        StackPane terminal = new StackPane(output);
        terminal.getStyleClass().add("terminal-shell");
        VBox.setVgrow(terminal, Priority.ALWAYS);

        Label outputTitle = new Label("TERMINAL  /  LIVE STREAM");
        outputTitle.getStyleClass().add("terminal-title");
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

        VBox card = new VBox(14, titleRow, outputTitle, terminal, inputRow, footer);
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
        boolean structuredMode = "结构化训练监视".equals(monitorMode.getValue());
        String startupMonitorCommand = structuredMode ? buildSnapshotCommand() : bashCommand.getText().trim();
        boolean startMonitorAutomatically = autoStartMonitor.isSelected() && !startupMonitorCommand.isBlank();
        boolean snapshotMode = parsed.interactiveShell() && structuredMode && startMonitorAutomatically;

        running = true;
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
                    runSnapshotMonitor(parsed, secret, trust, startupMonitorCommand, projectName.getText().trim());
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
        return interpreter + " " + path + "/" + script;
    }

    private void runSnapshotMonitor(SshCommand parsed, char[] secret, boolean trust,
                                    String snapshotCommand, String configuredProjectName) {
        int failures = 0;
        while (running && !Thread.currentThread().isInterrupted()) {
            try (SshMonitor monitor = new SshMonitor(parsed, secret, trust)) {
                activeMonitor.set(monitor);
                monitor.poll(snapshotCommand, 1_000,
                        raw -> publishSnapshot(raw, configuredProjectName),
                        text -> Platform.runLater(() -> setStatus(text, true)));
                failures = 0;
            } catch (Exception ex) {
                if (!running || Thread.currentThread().isInterrupted()) break;
                int currentFailures = ++failures;
                Platform.runLater(() -> {
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

    private void publishSnapshot(String rawJson, String configuredProjectName) {
        try {
            String frame = SnapshotFormatter.format(rawJson, configuredProjectName);
            Platform.runLater(() -> {
                output.setText(frame);
                output.positionCaret(0);
                connectionHint.setText("按原 V2 监视器格式运行，每 1 秒原位刷新");
                setStatus("训练监视中", true);
            });
        } catch (Exception ex) {
            Platform.runLater(() -> {
                output.setText("训练快照格式解析失败：\n" + safeMessage(ex) + "\n\n原始输出：\n" + rawJson);
                output.positionCaret(0);
                setStatus("训练快照解析失败", false);
            });
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

    private void saveConfig() {
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
        } catch (Exception ex) {
            showError("配置保存失败：" + safeMessage(ex));
        }
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
