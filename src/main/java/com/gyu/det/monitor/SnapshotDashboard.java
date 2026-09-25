package com.gyu.det.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a compact, readable view from each remote snapshot JSON frame. */
final class SnapshotDashboard {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern EPOCH_STEP = Pattern.compile("epoch\\s*[=:]\\s*(\\d+)\\s*/\\s*(\\d+).*?step\\s*[=:]\\s*(\\d+)\\s*/\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private SnapshotDashboard() { }

    static Node create(String raw, String projectName, String fallbackPlan) throws Exception {
        JsonNode root = JSON.readTree(raw);
        if (root == null || !root.isObject()) throw new IllegalArgumentException("快照不是 JSON 对象");
        JsonNode state = root.path("state");
        List<TrainingQueue.Item> queue = TrainingQueue.from(root, fallbackPlan);
        VBox page = new VBox(14);
        page.setPadding(new Insets(18));
        page.getStyleClass().add("snapshot-page");

        String title = projectName == null || projectName.isBlank() ? "训练监视器" : projectName;
        String phase = text(first(state.path("phase"), state.path("status"), root.path("status")), "状态未知");
        String current = text(first(state.path("current"), root.path("current")), "");

        HBox heading = new HBox(12);
        heading.setAlignment(Pos.CENTER_LEFT);
        VBox titleBlock = new VBox(4, label(title, "snapshot-title"),
                label("远程快照 · " + LocalDateTime.now().format(TIME), "snapshot-subtitle"));
        HBox.setHgrow(titleBlock, Priority.ALWAYS);
        Label badge = label(phase, "snapshot-badge");
        heading.getChildren().addAll(titleBlock, badge);
        page.getChildren().add(heading);

        TrainingQueue.Item active = queue.stream().filter(item -> item.status().equals("running")).findFirst().orElse(null);
        if (active != null && current.isBlank()) current = active.name();
        JsonNode run = activeRun(root);
        if (run == null) run = root.path("run");

        FlowPane summary = new FlowPane(10, 10);
        summary.getChildren().add(buildProcessCard(root));
        summary.getChildren().add(buildGpuCard(root.path("gpu"), root.path("processes")));
        page.getChildren().add(summary);

        List<TrainingQueue.Item> runningTasks = queue.stream().filter(item -> item.status().equals("running")).toList();
        if (!runningTasks.isEmpty()) {
            for (TrainingQueue.Item task : runningTasks) {
                JsonNode taskData = taskDetail(root, state, task.name());
                JsonNode taskRun = taskData.isObject() && !taskData.isEmpty() ? taskData : run;
                page.getChildren().add(buildActiveTask(task.name(), task, taskRun, root, state, taskData));
            }
        } else if (!current.isBlank() || active != null) {
            page.getChildren().add(buildActiveTask(current, active, run, root, state, run));
        }

        if (!queue.isEmpty()) page.getChildren().add(buildQueue(queue, root, state));
        else page.getChildren().add(infoCard("训练队列", "快照没有提供队列；当前任务和服务器状态仍会显示在上方。", "snapshot-section", false));

        String progress = text(root.path("progress"), "");
        if (!progress.isBlank()) {
            VBox log = new VBox(7, label("最新训练输出", "snapshot-section-title"), label(progress, "snapshot-log"));
            page.getChildren().add(log);
        }
        return page;
    }

    static Node createSystemStatus(String raw, String projectName) {
        JsonNode root;
        try {
            root = raw == null || raw.isBlank() ? JSON.createObjectNode() : JSON.readTree(raw);
        } catch (Exception ignored) {
            root = JSON.createObjectNode();
        }
        JsonNode system = first(root.path("system"), root.path("host"), root.path("machine"));
        JsonNode cpu = first(system.path("cpu"), root.path("cpu"));
        JsonNode memory = first(system.path("memory"), root.path("memory"));
        JsonNode gpu = root.path("gpu");

        JsonNode cpuPercent = metricNode(cpu, "percent", "usage_percent", "utilization", "usage", "cpu_percent");
        if (!valid(cpuPercent) && cpu.isNumber()) cpuPercent = cpu;
        if (!valid(cpuPercent)) cpuPercent = metricNode(system, "cpu_percent", "cpu_usage_percent", "cpu_utilization");
        double cpuValue = percentValue(cpuPercent);

        JsonNode memoryUsed = first(memory.path("used_mib"), memory.path("used_mb"),
                memory.path("memory_used_mib"), memory.path("used"), system.path("memory_used_mib"), root.path("memory_used_mib"));
        JsonNode memoryTotal = first(memory.path("total_mib"), memory.path("total_mb"),
                memory.path("memory_total_mib"), memory.path("total"), system.path("memory_total_mib"), root.path("memory_total_mib"));
        JsonNode memoryPercentNode = metricNode(memory, "percent", "usage_percent", "utilization", "memory_percent");
        if (!valid(memoryPercentNode)) memoryPercentNode = metricNode(system, "memory_percent", "memory_usage_percent");
        double memoryPercent = percentValue(memoryPercentNode);
        if (memoryPercent < 0 && numeric(memoryUsed) >= 0 && numeric(memoryTotal) > 0) {
            memoryPercent = numeric(memoryUsed) * 100 / numeric(memoryTotal);
        }

        JsonNode gpuPercentNode = gpuPercentNode(gpu);
        double gpuPercent = percentValue(gpuPercentNode);
        JsonNode gpuUsed = first(gpu.path("memory_used_mib"), gpu.path("memory_used"), gpu.path("used"));
        JsonNode gpuTotal = first(gpu.path("memory_total_mib"), gpu.path("memory_total"), gpu.path("total"));
        if (!valid(gpuUsed) && gpu.isValueNode()) {
            String[] parts = gpu.asText().split(",", -1);
            if (parts.length >= 3) {
                gpuUsed = JSON.getNodeFactory().textNode(parts[1].trim());
                gpuTotal = JSON.getNodeFactory().textNode(parts[2].trim());
            }
        }
        double gpuMemoryPercent = numeric(gpuUsed) >= 0 && numeric(gpuTotal) > 0
                ? numeric(gpuUsed) * 100 / numeric(gpuTotal) : -1;

        VBox page = new VBox(16);
        page.setPadding(new Insets(20));
        page.getStyleClass().add("snapshot-page");
        String title = projectName == null || projectName.isBlank() ? "整机状态" : projectName;
        HBox heading = new HBox(10, new VBox(4,
                label("整机运行状态", "snapshot-title"),
                label(title + "  ·  远程实时数据", "snapshot-subtitle")));
        heading.setAlignment(Pos.CENTER_LEFT);
        page.getChildren().add(heading);

        HBox gpuRow = metricRow(
                systemMetricCard("GPU 占用", percentText(gpuPercent), "GPU 计算核心利用率", gpuPercent, "system-metric-green"),
                systemMetricCard("GPU 显存", memoryText(gpuUsed, gpuTotal), "已用 / 总量", gpuMemoryPercent, "system-metric-blue"));
        HBox hostRow = metricRow(
                systemMetricCard("CPU 占用", percentText(cpuValue), "整机处理器利用率", cpuValue, "system-metric-purple"),
                systemMetricCard("内存占用", memoryText(memoryUsed, memoryTotal),
                        valid(memoryPercentNode) || memoryPercent >= 0 ? percentText(memoryPercent) : "已用 / 总量",
                        memoryPercent, "system-metric-orange"));
        page.getChildren().addAll(gpuRow, hostRow);

        JsonNode temperature = first(gpu.path("temperature_c"), gpu.path("temperature"), gpu.path("temperature_gpu"));
        JsonNode power = first(gpu.path("power_w"), gpu.path("power_draw_w"), gpu.path("power"), gpu.path("power_draw"));
        if (valid(temperature) || valid(power)) {
            List<String> details = new ArrayList<>();
            if (valid(temperature)) details.add("GPU 温度  " + temperature.asText() + " °C");
            if (valid(power)) details.add("GPU 功耗  " + power.asText() + " W");
            page.getChildren().add(infoCard("GPU 详情", String.join("     ·     ", details), "snapshot-section", true));
        }
        return page;
    }

    private static HBox metricRow(Node first, Node second) {
        HBox row = new HBox(14, first, second);
        HBox.setHgrow(first, Priority.ALWAYS);
        HBox.setHgrow(second, Priority.ALWAYS);
        return row;
    }

    private static Node systemMetricCard(String title, String value, String detail, double percent, String accent) {
        VBox card = new VBox(11);
        card.getStyleClass().addAll("system-metric-card", accent);
        card.getChildren().add(label(title, "system-metric-title"));
        HBox valueLine = new HBox(8, label(value, "system-metric-value"));
        valueLine.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(valueLine);
        if (percent >= 0) {
            ProgressBar bar = new ProgressBar(Math.max(0, Math.min(1, percent / 100)));
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.getStyleClass().add("system-metric-progress");
            card.getChildren().add(bar);
        }
        card.getChildren().add(label(detail, "system-metric-detail"));
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setPrefHeight(142);
        return card;
    }

    private static JsonNode gpuPercentNode(JsonNode gpu) {
        if (gpu.isObject()) return metricNode(gpu, "utilization", "utilization_gpu", "gpu_util");
        if (gpu.isValueNode()) {
            String[] parts = gpu.asText().split(",", -1);
            if (parts.length >= 4) {
                try { return JSON.getNodeFactory().numberNode(Double.parseDouble(parts[3].trim().replace("%", ""))); }
                catch (NumberFormatException ignored) { }
            }
            Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%").matcher(gpu.asText());
            if (matcher.find()) return JSON.getNodeFactory().numberNode(Double.parseDouble(matcher.group(1)));
        }
        return JSON.getNodeFactory().nullNode();
    }

    private static double percentValue(JsonNode node) {
        double value = numeric(node);
        if (value < 0 && valid(node)) {
            Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(node.asText());
            if (matcher.find()) {
                try { value = Double.parseDouble(matcher.group(1)); }
                catch (NumberFormatException ignored) { return -1; }
            }
        }
        return value < 0 ? -1 : Math.max(0, Math.min(100, value));
    }

    private static double numeric(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return -1;
        if (node.isNumber()) return node.asDouble();
        try { return Double.parseDouble(node.asText().replace(",", "").trim()); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static String percentText(double percent) {
        return percent < 0 ? "暂无数据" : String.format(Locale.ROOT, "%.0f%%", percent);
    }

    private static String memoryText(JsonNode used, JsonNode total) {
        double usedValue = numeric(used);
        double totalValue = numeric(total);
        if (usedValue < 0 || totalValue < 0) return "暂无数据";
        if (usedValue >= 1024 || totalValue >= 1024) {
            return String.format(Locale.ROOT, "%.1f / %.1f GiB", usedValue / 1024, totalValue / 1024);
        }
        return String.format(Locale.ROOT, "%.0f / %.0f MiB", usedValue, totalValue);
    }

    private static Node buildActiveTask(String current, TrainingQueue.Item active, JsonNode run,
                                       JsonNode root, JsonNode state, JsonNode detail) {
        String name = current.isBlank() ? active.name() : current;
        JsonNode epochProgress = epochProgress(root, run, detail);
        int epoch = integer(first(epochProgress.path("epoch"), detail.path("current_epoch"), run.path("current_epoch"), run.path("epoch")));
        int totalEpochs = integer(first(epochProgress.path("total_epochs"), detail.path("total_epochs"), run.path("total_epochs"), run.path("epochs")));
        int step = integer(first(epochProgress.path("step"), detail.path("current_step"), run.path("current_step")));
        int totalSteps = integer(first(epochProgress.path("total_steps"), detail.path("total_steps"), run.path("total_steps")));
        double epochFraction = totalSteps > 0 ? (double) step / totalSteps : -1;
        double fraction = active == null ? fraction(run) : active.fraction();
        if (totalEpochs > 0 && epoch > 0 && epochFraction >= 0) {
            fraction = Math.min(1, ((epoch - 1) + epochFraction) / totalEpochs);
        } else if (fraction < 0) fraction = fraction(root);
        VBox card = new VBox(11);
        card.getStyleClass().add("snapshot-active-card");
        HBox titleLine = new HBox(10, label("正在训练", "snapshot-active-kicker"), label(name, "snapshot-active-name"));
        titleLine.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(titleLine);

        if (epoch > 0 && totalEpochs > 0) {
            card.getChildren().add(label("最新一轮   Epoch " + epoch + " / " + totalEpochs
                    + (totalSteps > 0 ? "     Batch " + step + " / " + totalSteps : ""), "snapshot-epoch-label"));
            if (epochFraction >= 0) {
                ProgressBar epochBar = new ProgressBar(Math.min(1, Math.max(0, epochFraction)));
                epochBar.setMaxWidth(Double.MAX_VALUE);
                epochBar.getStyleClass().add("snapshot-epoch-progress");
                HBox epochLine = new HBox(10, epochBar,
                        label(String.format(Locale.ROOT, "本轮 %.0f%%", epochFraction * 100), "snapshot-muted"));
                epochLine.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(epochBar, Priority.ALWAYS);
                card.getChildren().add(epochLine);
            }
        }

        if (fraction >= 0) {
            HBox progressLine = new HBox(12);
            progressLine.setAlignment(Pos.CENTER_LEFT);
            ProgressBar bar = new ProgressBar(Math.min(1, Math.max(0, fraction)));
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.getStyleClass().add("snapshot-progress");
            HBox.setHgrow(bar, Priority.ALWAYS);
            progressLine.getChildren().addAll(bar, label(String.format(Locale.ROOT, "%.0f%%", fraction * 100), "snapshot-percent"));
            card.getChildren().add(progressLine);
        } else if (active != null) {
            card.getChildren().add(label(active.progressLabel(), "snapshot-muted"));
        }

        JsonNode latest = first(run.path("latest"), run.path("metrics"), run);
        JsonNode best = first(run.path("best"), run.path("best_metrics"));
        JsonNode bestEpoch = first(best.path("epoch"), run.path("best_epoch"));
        JsonNode lastEpoch = first(latest.path("epoch"), run.path("completed_epochs"), run.path("rows"));
        String bestRound = roundLabel(bestEpoch, first(best.path("total_epochs"), run.path("total_epochs"), run.path("epochs")));
        String lastRound = roundLabel(lastEpoch, first(run.path("total_epochs"), run.path("epochs"), detail.path("total_epochs")));
        card.getChildren().add(scoreRow("Best 轮次", bestRound
                + "   ·   mAP50 " + metricText(metricNode(best, "best_50", "mAP50", "map50"))
                + "   ·   mAP50-95 " + metricText(metricNode(best, "best_5095", "mAP50-95", "map50_95"))));
        card.getChildren().add(scoreRow("Last 最新轮次", lastRound
                + "   ·   P " + metricText(metricNode(latest, "last_p", "precision", "p"))
                + "   ·   R " + metricText(metricNode(latest, "last_r", "recall", "r"))
                + "   ·   mAP50 " + metricText(metricNode(latest, "last_50", "mAP50", "map50"))
                + "   ·   mAP50-95 " + metricText(metricNode(latest, "last_5095", "mAP50-95", "map50_95"))));
        return card;
    }

    private static Node scoreRow(String kind, String value) {
        HBox row = new HBox(12, label(kind, "snapshot-score-tag"), label(value, "snapshot-score-value"));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("snapshot-score-row");
        HBox.setHgrow(row.getChildren().get(1), Priority.ALWAYS);
        return row;
    }

    private static String roundLabel(JsonNode epoch, JsonNode total) {
        String current = valid(epoch) ? epoch.asText() : "—";
        return current + (valid(total) ? " / " + total.asText() : "");
    }

    private static String metricText(JsonNode metric) {
        return metric != null && metric.isNumber()
                ? String.format(Locale.ROOT, "%.4f", metric.asDouble()) : "—";
    }

    private static Node buildQueue(List<TrainingQueue.Item> queue, JsonNode root, JsonNode state) {
        List<String> completed = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (TrainingQueue.Item item : queue) {
            switch (item.status()) {
                case "completed" -> {
                    JsonNode detail = taskDetail(root, state, item.name());
                    JsonNode best = first(detail.path("best"), detail.path("best_metrics"));
                    JsonNode bestMap = metricNode(best, "best_5095", "mAP50-95", "map50_95", "map50-95");
                    String suffix = valid(bestMap) ? " · best mAP50-95 " + String.format(Locale.ROOT, "%.4f", bestMap.asDouble()) : "";
                    completed.add(item.name() + suffix);
                }
                case "pending" -> pending.add(item.name());
                default -> { }
            }
        }
        VBox card = new VBox(8);
        card.getStyleClass().add("snapshot-section");
        long done = queue.stream().filter(item -> item.status().equals("completed")).count();
        card.getChildren().add(label("训练队列    " + done + " / " + queue.size() + " 项完成", "snapshot-section-title"));
        if (!completed.isEmpty()) card.getChildren().add(label("✓ 已完成  " + String.join("、", completed), "snapshot-queue-done"));
        queue.stream().filter(item -> item.status().equals("failed") || item.status().equals("skipped"))
                .forEach(item -> card.getChildren().add(label("! " + item.name() + "  " + item.statusLabel(), "snapshot-queue-warning")));
        if (!pending.isEmpty()) card.getChildren().add(label("○ 待开始  " + String.join("、", pending), "snapshot-queue-pending"));
        return card;
    }

    private static Node buildProcessCard(JsonNode root) {
        VBox card = new VBox(7, label("训练进程", "snapshot-info-title"));
        card.getStyleClass().add("snapshot-info-card");
        JsonNode processes = root.path("processes");
        if (processes.isArray() && !processes.isEmpty()) {
            card.getChildren().add(label(processes.size() + " 个训练进程  ·  RUNNING", "snapshot-info-value"));
        } else {
            String process = text(root.path("process"), "");
            if (process.startsWith("RUNNING:")) {
                String pids = process.substring("RUNNING:".length()).trim();
                long count = pids.isBlank() ? 0 : pids.split(",").length;
                card.getChildren().add(label(count + " 个进程  ·  RUNNING", "snapshot-info-value"));
            } else {
                card.getChildren().add(label(process.isBlank() ? "暂无运行中的训练进程" : process, "snapshot-info-value"));
            }
        }
        card.setMinWidth(240);
        card.setPrefWidth(360);
        return card;
    }

    private static Node buildGpuCard(JsonNode gpu, JsonNode processes) {
        VBox card = new VBox(7);
        card.getStyleClass().add("snapshot-info-card");
        HBox heading = new HBox(9);
        heading.setAlignment(Pos.CENTER_LEFT);
        heading.getChildren().add(label("GPU 状态", "snapshot-info-title"));
        String details = formatGpu(gpu);
        String status;
        String style;
        if (gpu == null || gpu.isMissingNode() || gpu.isNull()
                || (gpu.isValueNode() && gpu.asText().isBlank())
                || gpu.path("available").asBoolean(true) == false
                || details.toLowerCase(Locale.ROOT).contains("unavailable")
                || details.contains("不可用")) {
            status = "不可用";
            style = "snapshot-gpu-unavailable";
        } else {
            double utilization = gpuUtilization(gpu);
            boolean hasTrainingProcess = processes.isArray() && !processes.isEmpty();
            status = hasTrainingProcess ? (utilization > 0 ? "训练占用中" : "训练进程运行中")
                    : utilization > 0 ? "有负载" : "空闲";
            style = utilization > 0 || hasTrainingProcess ? "snapshot-gpu-active" : "snapshot-gpu-idle";
        }
        heading.getChildren().add(label("● " + status, style));
        card.getChildren().addAll(heading, label(details, "snapshot-info-value"));
        card.setMinWidth(240);
        card.setPrefWidth(360);
        return card;
    }

    private static JsonNode taskDetail(JsonNode root, JsonNode state, String name) {
        JsonNode detail = first(state.path("experiments").path(name), root.path("experiments").path(name),
                root.path("tasks").path(name), root.path("runs").path(name));
        if (detail.isObject() && !detail.isEmpty()) return detail;
        for (JsonNode parent : List.of(root, state)) {
            for (String listName : List.of("queue", "tasks", "processes")) {
                JsonNode list = parent.path(listName);
                if (!list.isArray()) continue;
                for (JsonNode item : list) {
                    if (name.equals(text(first(item.path("name"), item.path("id"), item.path("task")), ""))) return item;
                }
            }
        }
        return root.path("missing");
    }

    private static JsonNode epochProgress(JsonNode root, JsonNode run, JsonNode detail) {
        JsonNode supplied = first(detail.path("epoch_progress"), run.path("epoch_progress"), root.path("epoch_progress"));
        if (supplied.isObject()) return supplied;
        Matcher matcher = EPOCH_STEP.matcher(text(first(detail.path("progress"), run.path("progress"), root.path("progress")), ""));
        if (!matcher.find()) return root.path("missing");
        com.fasterxml.jackson.databind.node.ObjectNode result = JSON.createObjectNode();
        result.put("epoch", parseInt(matcher.group(1)));
        result.put("total_epochs", parseInt(matcher.group(2)));
        result.put("step", parseInt(matcher.group(3)));
        result.put("total_steps", parseInt(matcher.group(4)));
        return result;
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static int integer(JsonNode node) {
        return node != null && node.canConvertToInt() ? node.asInt() : 0;
    }

    private static JsonNode metricNode(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (valid(value) && value.isNumber()) return value;
        }
        return JSON.getNodeFactory().nullNode();
    }

    private static double gpuUtilization(JsonNode gpu) {
        if (gpu.isObject()) {
            JsonNode value = first(gpu.path("utilization"), gpu.path("utilization_gpu"), gpu.path("gpu_util"));
            return value != null && value.isNumber() ? value.asDouble() : 0;
        }
        String[] parts = gpu.asText().split(",");
        if (parts.length >= 4) {
            try { return Double.parseDouble(parts[3].trim().replace("%", "")); }
            catch (NumberFormatException ignored) { }
        }
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%").matcher(gpu.asText());
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0;
    }

    private static Node infoCard(String title, String value, String style, boolean grow) {
        VBox card = new VBox(7, label(title, "snapshot-info-title"), label(value, "snapshot-info-value"));
        card.getStyleClass().add(style);
        if (grow) {
            card.setMinWidth(240);
            card.setPrefWidth(360);
            card.setMaxWidth(Double.MAX_VALUE);
        }
        return card;
    }

    private static void addMetric(FlowPane parent, String title, JsonNode value, JsonNode total, String format) {
        if (value == null || value.isMissingNode() || value.isNull() || value.asText().isBlank()) return;
        String shown = value.asText();
        if (total != null && !total.isMissingNode() && !total.isNull() && !total.asText().isBlank()) shown += " / " + total.asText();
        else if (!format.isBlank()) {
            try { shown = String.format(Locale.ROOT, "%.4f", value.asDouble()); }
            catch (RuntimeException ignored) { }
        }
        VBox metric = new VBox(4, label(title, "snapshot-metric-title"), label(shown, "snapshot-metric-value"));
        metric.getStyleClass().add("snapshot-metric");
        parent.getChildren().add(metric);
    }

    private static JsonNode activeRun(JsonNode root) {
        var fields = root.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (field.getKey().startsWith("run") && field.getValue().isObject()
                    && (field.getValue().has("rows") || field.getValue().has("last_50") || field.getValue().has("mAP50"))) {
                return field.getValue();
            }
        }
        return null;
    }

    private static double fraction(JsonNode node) {
        JsonNode value = first(node.path("progress_percent"), node.path("percent"), node.path("progress"));
        if (value == null || !value.isNumber()) return -1;
        double number = value.asDouble();
        return Math.max(0, Math.min(1, number > 1 ? number / 100 : number));
    }

    private static String formatGpu(JsonNode gpu) {
        if (gpu == null || gpu.isMissingNode() || gpu.isNull()) return "暂无 GPU 信息";
        if (gpu.isObject()) {
            List<String> values = new ArrayList<>();
            put(values, "型号", first(gpu.path("name"), gpu.path("gpu_name")));
            put(values, "利用率", first(gpu.path("utilization"), gpu.path("utilization_gpu"), gpu.path("gpu_util")));
            JsonNode used = first(gpu.path("memory_used"), gpu.path("memory_used_mib"), gpu.path("used"));
            JsonNode total = first(gpu.path("memory_total"), gpu.path("memory_total_mib"), gpu.path("total"));
            if (valid(used)) values.add("显存 " + used.asText() + (valid(total) ? " / " + total.asText() : ""));
            put(values, "温度", first(gpu.path("temperature_c"), gpu.path("temperature"), gpu.path("temperature_gpu")));
            put(values, "功耗", first(gpu.path("power_w"), gpu.path("power_draw_w"), gpu.path("power"), gpu.path("power_draw")));
            put(values, "信息", gpu.path("error"));
            return values.isEmpty() ? gpu.toString() : String.join("   ·   ", values);
        }
        String raw = gpu.asText();
        String[] parts = raw.split(",", -1);
        if (parts.length >= 6) {
            return "型号 " + parts[0].trim()
                    + "   ·   利用率 " + parts[3].trim() + "%"
                    + "   ·   显存 " + parts[1].trim() + "/" + parts[2].trim() + " MiB"
                    + "   ·   温度 " + parts[4].trim() + "°C"
                    + "   ·   功耗 " + parts[5].trim() + " W";
        }
        return raw;
    }

    private static void put(List<String> values, String label, JsonNode node) {
        if (valid(node)) values.add(label + " " + node.asText());
    }

    private static boolean valid(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull()
                && (!node.isValueNode() || !node.asText().isBlank());
    }

    private static JsonNode first(JsonNode... nodes) {
        for (JsonNode node : nodes) if (node != null && !node.isMissingNode() && !node.isNull()) return node;
        return nodes.length == 0 ? null : nodes[0];
    }

    private static String text(JsonNode node, String fallback) {
        return valid(node) ? node.isValueNode() ? node.asText() : node.toString() : fallback;
    }

    private static Label label(String value, String style) {
        Label label = new Label(value);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        return label;
    }
}
