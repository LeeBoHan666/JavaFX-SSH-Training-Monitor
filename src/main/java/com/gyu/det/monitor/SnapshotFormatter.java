package com.gyu.det.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formats monitor_snapshot.py JSON using the layout from GYU_V2_Monitor.py. */
public final class SnapshotFormatter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String RULE = "=".repeat(72);
    private static final String SEPARATOR = "-".repeat(72);

    private SnapshotFormatter() {
    }

    public static String format(String rawJson) throws Exception {
        return format(rawJson, "GYU-DET YOLO11-V2");
    }

    public static String format(String rawJson, String projectName) throws Exception {
        JsonNode snapshot = JSON.readTree(rawJson);
        JsonNode state = snapshot.path("state");
        JsonNode experiments = state.path("experiments");

        StringBuilder text = new StringBuilder(1024);
        text.append(projectName == null || projectName.isBlank() ? "训练监视器" : projectName)
                .append(" 训练监视器\n");
        text.append(RULE).append('\n');
        text.append("本地时间: ").append(LocalDateTime.now().format(TIME)).append('\n');
        text.append("服务器任务: ").append(display(snapshot.path("process"))).append('\n');
        text.append("顺序状态: ").append(value(state, "status", "未知")).append('\n');
        text.append("GPU: ").append(formatGpu(display(snapshot.path("gpu")))).append('\n');
        text.append(SEPARATOR).append('\n');
        appendRun(text, "V2-640", snapshot.path("run640"), experiments.path("640").path("selected_batch"));
        text.append('\n');
        appendRun(text, "V2-960", snapshot.path("run960"), experiments.path("960").path("selected_batch"));
        text.append(SEPARATOR).append('\n');
        text.append("当前批次: ").append(display(snapshot.path("progress"))).append('\n');
        text.append("每1秒原位刷新。关闭窗口即可停止本地监视，不影响服务器训练。");
        return text.toString();
    }

    public static String formatFailure(Exception error, int failures) {
        return formatFailure(error, failures, "GYU-DET YOLO11-V2");
    }

    public static String formatFailure(Exception error, int failures, String projectName) {
        return (projectName == null || projectName.isBlank() ? "训练监视器" : projectName) + " 训练监视器\n"
                + RULE + "\n"
                + "本地时间: " + LocalDateTime.now().format(TIME) + "\n"
                + "服务器连接失败，监视器不会退出。\n"
                + "原因: " + safeMessage(error) + "\n"
                + "已连续失败 " + failures + " 次，将自动重试。\n"
                + "训练完成后的自动关机也会表现为连接失败。";
    }

    private static void appendRun(StringBuilder text, String title, JsonNode data, JsonNode selectedBatch) {
        String batch = selectedBatch.isMissingNode() || selectedBatch.isNull()
                ? "待测试" : numberOrText(selectedBatch);
        text.append(title)
                .append("  阶段: ").append(value(data, "stage", "等待开始"))
                .append("  batch: ").append(batch)
                .append('\n');

        int rows = data.path("rows").asInt(0);
        if (rows <= 0) return;
        text.append(String.format(Locale.ROOT,
                "  已完成 %d/250轮  最近: P=%.4f R=%.4f mAP50=%.4f mAP50-95=%.4f%n",
                rows, number(data, "last_p"), number(data, "last_r"),
                number(data, "last_50"), number(data, "last_5095")));
        text.append(String.format(Locale.ROOT,
                "  最佳第%d轮: mAP50=%.4f mAP50-95=%.4f  预计剩余≈%.1f小时%n",
                data.path("best_epoch").asInt(), number(data, "best_50"),
                number(data, "best_5095"), number(data, "remaining_hours")));
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

    private static double number(JsonNode node, String field) {
        return node.path(field).asDouble(0);
    }

    private static String value(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : display(value);
    }

    private static String display(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return "";
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static String numberOrText(JsonNode node) {
        if (node.isIntegralNumber()) return Long.toString(node.asLong());
        if (node.isFloatingPointNumber() && node.asDouble() == Math.rint(node.asDouble())) {
            return Long.toString((long) node.asDouble());
        }
        return node.asText();
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
