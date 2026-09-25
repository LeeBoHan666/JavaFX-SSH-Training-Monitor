package com.gyu.det.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Live machine metrics and a short rolling trend window. Must be updated on the JavaFX thread. */
final class SystemStatusDashboard {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SAMPLES = 240;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final VBox root = new VBox(18);
    private final Label gpuValue = valueLabel();
    private final Label gpuMemoryValue = valueLabel();
    private final Label cpuValue = valueLabel();
    private final Label memoryValue = valueLabel();
    private final TrendCanvas trendChart = new TrendCanvas(List.of(
            new PlotLine("GPU", "#3cdda0", Sample::gpuUtilization),
            new PlotLine("CPU", "#a78bfa", Sample::cpuUtilization),
            new PlotLine("GPU 显存", "#55a9ff", Sample::gpuMemoryPercent),
            new PlotLine("系统内存", "#f4a85f", Sample::memoryPercent)));

    SystemStatusDashboard(String projectName) {
        root.setPadding(new Insets(20));
        root.getStyleClass().add("snapshot-page");
        Label title = new Label("整机实时状态");
        title.getStyleClass().add("snapshot-title");
        Label subtitle = new Label((projectName == null || projectName.isBlank() ? "服务器" : projectName)
                + "  ·  最近 60 秒实时趋势");
        subtitle.getStyleClass().add("snapshot-subtitle");
        VBox heading = new VBox(4, title, subtitle);

        HBox currentValues = new HBox(12,
                metricCard("GPU 占用", gpuValue, "计算核心", "system-metric-green"),
                metricCard("GPU 显存", gpuMemoryValue, "已用 / 总量", "system-metric-blue"),
                metricCard("CPU 占用", cpuValue, "整机处理器", "system-metric-purple"),
                metricCard("系统内存", memoryValue, "已用 / 总量", "system-metric-orange"));
        for (Node child : currentValues.getChildren()) HBox.setHgrow(child, Priority.ALWAYS);

        root.getChildren().addAll(heading, currentValues,
                chartCard("资源使用趋势 · 最近 60 秒", trendChart));
    }

    Node node() { return root; }

    void update(String rawJson) {
        try {
            JsonNode rootNode = JSON.readTree(rawJson);
            JsonNode system = first(rootNode.path("system"), rootNode.path("host"), rootNode.path("machine"));
            JsonNode gpu = rootNode.path("gpu");
            JsonNode cpu = first(system.path("cpu"), rootNode.path("cpu"));
            JsonNode memory = first(system.path("memory"), rootNode.path("memory"));

            double gpuUtil = gpuUtilization(gpu);
            double gpuUsed = number(first(gpu.path("memory_used_mib"), gpu.path("memory_used"), gpu.path("used")));
            double gpuTotal = number(first(gpu.path("memory_total_mib"), gpu.path("memory_total"), gpu.path("total")));
            if (gpu.isValueNode()) {
                String[] parts = gpu.asText().split(",", -1);
                if (parts.length >= 4) {
                    if (gpuUsed < 0 && parts.length >= 3) gpuUsed = number(JSON.getNodeFactory().textNode(parts[1].trim()));
                    if (gpuTotal < 0 && parts.length >= 3) gpuTotal = number(JSON.getNodeFactory().textNode(parts[2].trim()));
                    if (gpuUtil < 0) gpuUtil = number(JSON.getNodeFactory().textNode(parts[3].trim().replace("%", "")));
                }
            }

            JsonNode cpuPercentNode = first(cpu.path("percent"), cpu.path("usage_percent"), cpu.path("utilization"),
                    cpu.path("usage"), cpu.path("cpu_percent"), system.path("cpu_percent"),
                    system.path("cpu_usage_percent"), system.path("cpu_utilization"));
            if (!valid(cpuPercentNode) && cpu.isNumber()) cpuPercentNode = cpu;
            double cpuPercent = percent(cpuPercentNode);

            JsonNode memoryUsedNode = first(memory.path("used_mib"), memory.path("used_mb"),
                    memory.path("memory_used_mib"), memory.path("used"), system.path("memory_used_mib"), rootNode.path("memory_used_mib"));
            JsonNode memoryTotalNode = first(memory.path("total_mib"), memory.path("total_mb"),
                    memory.path("memory_total_mib"), memory.path("total"), system.path("memory_total_mib"), rootNode.path("memory_total_mib"));
            double memoryUsed = number(memoryUsedNode);
            double memoryTotal = number(memoryTotalNode);
            JsonNode memoryPercentNode = first(memory.path("percent"), memory.path("usage_percent"),
                    memory.path("utilization"), memory.path("memory_percent"), system.path("memory_percent"), system.path("memory_usage_percent"));
            double memoryPercent = percent(memoryPercentNode);
            if (memoryPercent < 0 && memoryUsed >= 0 && memoryTotal > 0) memoryPercent = memoryUsed * 100 / memoryTotal;
            double gpuMemoryPercent = gpuUsed >= 0 && gpuTotal > 0 ? gpuUsed * 100 / gpuTotal : -1;

            Sample sample = new Sample(System.currentTimeMillis(), gpuUtil, cpuPercent,
                    gpuMemoryPercent, memoryPercent, gpuUsed, gpuTotal, memoryUsed, memoryTotal);
            samples.addLast(sample);
            while (samples.size() > MAX_SAMPLES) samples.removeFirst();

            gpuValue.setText(percentText(gpuUtil));
            gpuMemoryValue.setText(memoryText(gpuUsed, gpuTotal));
            cpuValue.setText(percentText(cpuPercent));
            memoryValue.setText(memoryText(memoryUsed, memoryTotal));
            trendChart.redraw(List.copyOf(samples));
        } catch (Exception ignored) {
            // Keep the last valid live reading on transient or malformed snapshots.
        }
    }

    private static Node metricCard(String title, Label value, String detail, String accent) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("system-metric-title");
        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("system-metric-detail");
        VBox card = new VBox(7, titleLabel, value, detailLabel);
        card.getStyleClass().addAll("system-current-card", accent);
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static Node chartCard(String title, TrendCanvas chart) {
        Label heading = new Label(title);
        heading.getStyleClass().add("system-chart-title");
        StackPane plot = new StackPane(chart);
        plot.setMinHeight(205);
        plot.setPrefHeight(240);
        chart.widthProperty().bind(plot.widthProperty());
        chart.heightProperty().bind(plot.heightProperty());
        VBox card = new VBox(8, heading, plot);
        card.getStyleClass().add("system-chart-card");
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(plot, Priority.ALWAYS);
        return card;
    }

    private static Label valueLabel() {
        Label label = new Label("暂无数据");
        label.getStyleClass().add("system-metric-value");
        return label;
    }

    private static double gpuUtilization(JsonNode gpu) {
        JsonNode value = first(gpu.path("utilization"), gpu.path("utilization_gpu"), gpu.path("gpu_util"));
        if (valid(value)) return percent(value);
        if (gpu.isValueNode()) {
            Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%").matcher(gpu.asText());
            if (matcher.find()) return parse(matcher.group(1));
        }
        return -1;
    }

    private static double percent(JsonNode node) {
        double value = number(node);
        if (value < 0 && valid(node)) {
            Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(node.asText());
            if (matcher.find()) value = parse(matcher.group(1));
        }
        return value < 0 ? -1 : Math.max(0, Math.min(100, value));
    }

    private static double number(JsonNode node) {
        if (!valid(node)) return -1;
        if (node.isNumber()) return node.asDouble();
        return parse(node.asText().replace(",", "").trim());
    }

    private static double parse(String value) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static String percentText(double value) {
        return value < 0 ? "暂无数据" : String.format(Locale.ROOT, "%.0f%%", value);
    }

    private static String memoryText(double used, double total) {
        if (used < 0 || total < 0) return "暂无数据";
        if (used >= 1024 || total >= 1024) {
            return String.format(Locale.ROOT, "%.1f / %.1f GiB", used / 1024, total / 1024);
        }
        return String.format(Locale.ROOT, "%.0f / %.0f MiB", used, total);
    }

    private static JsonNode first(JsonNode... nodes) {
        for (JsonNode node : nodes) if (node != null && !node.isMissingNode() && !node.isNull()) return node;
        return nodes.length == 0 ? null : nodes[0];
    }

    private static boolean valid(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull()
                && (!node.isValueNode() || !node.asText().isBlank());
    }

    private record Sample(long timestamp, double gpuUtilization, double cpuUtilization,
                          double gpuMemoryPercent, double memoryPercent,
                          double gpuMemoryUsed, double gpuMemoryTotal,
                          double memoryUsed, double memoryTotal) { }

    @FunctionalInterface
    private interface SampleValue { double get(Sample sample); }

    private record PlotLine(String name, String color, SampleValue value) { }

    private static final class TrendCanvas extends Canvas {
        private final List<PlotLine> lines;
        private List<Sample> samples = List.of();

        private TrendCanvas(List<PlotLine> lines) {
            this.lines = lines;
            setHeight(240);
            widthProperty().addListener((obs, oldValue, newValue) -> draw());
            heightProperty().addListener((obs, oldValue, newValue) -> draw());
        }

        void redraw(List<Sample> values) {
            samples = values;
            draw();
        }

        private void draw() {
            double width = getWidth();
            double height = getHeight();
            if (width <= 0 || height <= 0) return;
            GraphicsContext gc = getGraphicsContext2D();
            gc.clearRect(0, 0, width, height);
            double left = 43, right = 12, top = 30, bottom = 34;
            double plotWidth = Math.max(1, width - left - right);
            double plotHeight = Math.max(1, height - top - bottom);
            long latestTime = samples.isEmpty() ? 0 : samples.get(samples.size() - 1).timestamp();
            long oldestTime = samples.isEmpty() ? latestTime : samples.get(0).timestamp();
            long windowMillis = Math.max(1000, Math.min(60_000, latestTime - oldestTime));
            long windowStart = latestTime - windowMillis;
            int elapsedSeconds = (int) Math.max(1, Math.round(windowMillis / 1000.0));

            gc.setFont(javafx.scene.text.Font.font("Segoe UI", 10));
            gc.setLineWidth(1);
            for (int tick = 0; tick <= 100; tick += 25) {
                double y = top + plotHeight * (100 - tick) / 100.0;
                gc.setStroke(Color.web("#25364b"));
                gc.strokeLine(left, y, left + plotWidth, y);
                gc.setFill(Color.web("#8298b1"));
                gc.fillText(tick + "%", 3, y + 3);
            }
            gc.setFill(Color.web("#8298b1"));
            gc.fillText("-" + elapsedSeconds + "s", left, height - 9);
            gc.fillText("-" + Math.max(1, elapsedSeconds / 2) + "s", left + plotWidth / 2 - 12, height - 9);
            gc.fillText("现在", left + plotWidth - 24, height - 9);

            double legendX = left;
            for (PlotLine line : lines) {
                gc.setFill(Color.web(line.color()));
                gc.fillRoundRect(legendX, 8, 13, 3, 2, 2);
                gc.setFont(javafx.scene.text.Font.font("Segoe UI", 10));
                gc.fillText(line.name(), legendX + 18, 12);
                legendX += Math.max(65, line.name().length() * 12 + 33);
            }

            gc.setLineWidth(2.2);
            gc.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            gc.setLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
            for (PlotLine line : lines) {
                gc.setStroke(Color.web(line.color()));
                boolean drawing = false;
                for (int index = 0; index < samples.size(); index++) {
                    double value = line.value().get(samples.get(index));
                    if (value < 0) {
                        if (drawing) gc.stroke();
                        drawing = false;
                        continue;
                    }
                    long pointTime = samples.get(index).timestamp();
                    double x = left + plotWidth * Math.max(0, Math.min(1,
                            (double) (pointTime - windowStart) / windowMillis));
                    double y = top + plotHeight * (100 - Math.max(0, Math.min(100, value))) / 100.0;
                    if (!drawing) {
                        gc.beginPath();
                        gc.moveTo(x, y);
                        drawing = true;
                    } else {
                        gc.lineTo(x, y);
                    }
                }
                if (drawing) gc.stroke();
            }
        }
    }
}
