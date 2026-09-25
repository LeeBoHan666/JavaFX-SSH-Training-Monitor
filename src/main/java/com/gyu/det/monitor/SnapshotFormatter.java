package com.gyu.det.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Formats training snapshots as a single live terminal view. */
public final class SnapshotFormatter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String RULE = "=".repeat(72);
    private static final String SEPARATOR = "-".repeat(72);

    public record Frame(String text, List<TrainingQueue.Item> queue) {
    }

    private SnapshotFormatter() {
    }

    public static String format(String raw) throws Exception {
        return format(raw, "训练监视器");
    }

    public static String format(String raw, String projectName) throws Exception {
        return render(raw, projectName).text();
    }

    public static Frame render(String raw, String projectName) throws Exception {
        return render(raw, projectName, "");
    }

    public static Frame render(String raw, String projectName, String fallbackPlan) throws Exception {
        JsonNode snapshot;
        try {
            snapshot = JSON.readTree(raw);
        } catch (Exception parseError) {
            return new Frame(formatRawLog(raw, projectName), List.of());
        }
        if (snapshot == null || !snapshot.isObject()) {
            return new Frame(formatRawLog(raw, projectName), List.of());
        }
        List<TrainingQueue.Item> queue = TrainingQueue.from(snapshot, fallbackPlan);
        JsonNode state = snapshot.path("state");
        String title = title(projectName);
        StringBuilder text = new StringBuilder(1024);
        text.append(title).append(" 训练监视器\n")
                .append(RULE).append('\n')
                .append("本地时间: ").append(LocalDateTime.now().format(TIME)).append('\n');
        JsonNode phase = first(state.path("phase"), state.path("status"), snapshot.path("status"));
        append(text, "训练状态", phase);
        text.append(SEPARATOR).append('\n');
        if (queue.isEmpty()) {
            text.append("训练队列: 快照未提供队列信息\n");
            append(text, "服务器任务", snapshot.path("process"));
            append(text, "当前进度", snapshot.path("progress"));
        } else {
            long finished = queue.stream().filter(item -> item.status().equals("completed")).count();
            text.append("训练队列: ").append(finished).append('/').append(queue.size()).append(" 项已完成\n");
            for (int index = 0; index < queue.size(); index++) {
                TrainingQueue.Item item = queue.get(index);
                if (item.status().equals("running")) {
                    appendRunningTask(text, snapshot, state, item, index + 1);
                } else {
                    text.append(String.format(Locale.ROOT, "%2d. %-32s %s%n",
                            index + 1, item.name(), item.statusLabel()));
                }
            }
        }
        text.append(SEPARATOR).append('\n');
        text.append("训练快照每 0.5 秒刷新 · 整机状态每 0.25 秒刷新 · 关闭窗口不会停止服务器训练");
        return new Frame(text.toString(), queue);
    }

    public static String formatFailure(Exception error, int failures, String projectName) {
        return title(projectName) + " 训练监视器\n"
                + RULE + "\n"
                + "本地时间: " + LocalDateTime.now().format(TIME) + "\n"
                + "服务器连接失败，监视器不会退出。\n"
                + "原因: " + safeMessage(error) + "\n"
                + "已连续失败 " + failures + " 次，将自动重试。\n"
                + "训练完成后的自动关机也会表现为连接失败。";
    }

    public static String formatFailure(Exception error, int failures) {
        return formatFailure(error, failures, "训练监视器");
    }

    private static void appendRunningTask(StringBuilder text, JsonNode snapshot, JsonNode state,
                                          TrainingQueue.Item item, int position) {
        text.append(String.format(Locale.ROOT, "%2d. %s  %s%n", position, item.name(), item.statusLabel()));
        JsonNode process = snapshot.path("process");
        if (!process.isMissingNode() && !process.isNull()) {
            text.append("    进程: ").append(display(process)).append('\n');
        }
        text.append("    训练进度: ").append(item.progressLabel());
        if (item.fraction() >= 0) text.append("  ").append(progressBar(item.fraction()));
        text.append('\n');
        JsonNode detail = state.path("experiments").path(item.name());
        if (detail.isObject()) {
            appendIndented(text, "阶段", detail.path("stage"));
            appendIndented(text, "batch", detail.path("selected_batch"));
        }
        JsonNode active = activeRun(snapshot);
        if (active != null) {
            appendIndented(text, "运行目录", active.path("run"));
            appendMetrics(text, active);
        } else if (detail.isObject()) {
            appendMetrics(text, detail);
        }
        JsonNode gpu = snapshot.path("gpu");
        if (!gpu.isMissingNode() && !gpu.isNull()) {
            text.append("    GPU: ").append(formatGpu(display(gpu))).append('\n');
        }
        appendIndented(text, "实时日志", snapshot.path("progress"));
    }

    private static JsonNode activeRun(JsonNode snapshot) {
        JsonNode active = null;
        var fields = snapshot.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (field.getKey().startsWith("run") && field.getValue().isObject()
                    && field.getValue().path("rows").asInt(0) > 0) {
                active = field.getValue();
                break;
            }
        }
        return active;
    }

    private static void appendMetrics(StringBuilder text, JsonNode data) {
        Double precision = metric(data, "last_p", "precision", "p");
        Double recall = metric(data, "last_r", "recall", "r");
        Double map50 = metric(data, "last_50", "map50", "mAP50");
        Double map5095 = metric(data, "last_5095", "map50_95", "mAP50-95");
        if (precision != null || recall != null || map50 != null || map5095 != null) {
            text.append("    最近指标:");
            appendMetric(text, " P", precision);
            appendMetric(text, " R", recall);
            appendMetric(text, " mAP50", map50);
            appendMetric(text, " mAP50-95", map5095);
            text.append('\n');
        }
    }

    private static Double metric(JsonNode data, String... keys) {
        for (String key : keys) {
            JsonNode value = data.path(key);
            if (value.isNumber()) return value.asDouble();
        }
        return null;
    }

    private static void appendMetric(StringBuilder text, String label, Double value) {
        if (value != null) text.append(String.format(Locale.ROOT, "%s=%.4f", label, value));
    }

    private static void appendIndented(StringBuilder text, String label, JsonNode node) {
        if (!node.isMissingNode() && !node.isNull() && !display(node).isBlank()) {
            text.append("    ").append(label).append(": ").append(display(node)).append('\n');
        }
    }

    private static String progressBar(double fraction) {
        int filled = (int) Math.round(Math.min(1, Math.max(0, fraction)) * 20);
        return "[" + "█".repeat(filled) + "░".repeat(20 - filled) + "]";
    }

    private static void append(StringBuilder text, String label, JsonNode node) {
        if (node != null && !node.isMissingNode() && !node.isNull() && !display(node).isBlank()) {
            text.append(label).append(": ").append(display(node)).append('\n');
        }
    }

    private static JsonNode first(JsonNode... nodes) {
        for (JsonNode node : nodes) if (!node.isMissingNode() && !node.isNull()) return node;
        return nodes[0];
    }

    private static String display(JsonNode node) {
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static String title(String name) {
        return name == null || name.isBlank() ? "训练监视器" : name;
    }

    private static String formatRawLog(String raw, String projectName) {
        String cleaned = raw == null ? "" : raw.replace("\r", "").replaceAll("\\u001B\\[[;\\d]*m", "").trim();
        return title(projectName) + " 训练监视器\n" + RULE + "\n"
                + "本地时间: " + LocalDateTime.now().format(TIME) + "\n"
                + "远程监视输出:\n" + cleaned + "\n"
                + "每1秒原位刷新。关闭窗口即可停止本地监视，不影响服务器训练。";
    }

    private static String formatGpu(String raw) {
        String[] parts = raw.split(",");
        if (parts.length < 6) return raw;
        try {
            String name = parts[0].trim();
            double used = Double.parseDouble(parts[1].trim());
            double total = Double.parseDouble(parts[2].trim());
            String utilization = parts[3].trim();
            String temperature = parts[4].trim();
            double power = Double.parseDouble(parts[5].trim());
            double memoryPercent = total == 0 ? 0 : used / total * 100;
            return String.format(Locale.ROOT,
                    "%s | 显存 %s/%s MiB (%.1f%%) | GPU占用率 %s%% | 温度 %s°C | 功耗 %.1f W",
                    name, compact(used), compact(total), memoryPercent, utilization, temperature, power);
        } catch (NumberFormatException ignored) {
            return raw;
        }
    }

    private static String compact(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
