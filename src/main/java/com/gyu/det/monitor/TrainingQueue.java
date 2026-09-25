package com.gyu.det.monitor;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a training queue from a snapshot without assuming project names or image sizes. */
public final class TrainingQueue {
    private static final Pattern FRACTION = Pattern.compile("(?<!\\d)(\\d+)\\s*/\\s*(\\d+)(?!\\d)");

    public record Item(String name, String status, int completed, int total, double fraction) {
        public String statusLabel() {
            return switch (status) {
                case "completed" -> "已完成";
                case "running" -> "训练中";
                case "failed" -> "失败";
                case "skipped" -> "已跳过";
                default -> "未开始";
            };
        }

        public String progressLabel() {
            if (status.equals("completed")) return "100%";
            if (total > 0) return completed + "/" + total + " · " + Math.round(fraction * 100) + "%";
            return fraction >= 0 ? Math.round(fraction * 100) + "%" : "进度未知";
        }
    }

    private TrainingQueue() {
    }

    public static List<Item> from(JsonNode snapshot) {
        return from(snapshot, "");
    }

    public static List<Item> from(JsonNode snapshot, String fallbackPlan) {
        if (snapshot == null || !snapshot.isObject()) return List.of();
        JsonNode state = object(snapshot, "state");
        JsonNode sequence = object(snapshot, "sequence");
        if (sequence.isMissingNode()) sequence = object(state, "sequence");

        LinkedHashMap<String, JsonNode> entries = new LinkedHashMap<>();
        addPlan(entries, first(snapshot.path("queue"), state.path("queue"), sequence.path("queue")));
        if (entries.isEmpty()) addPlan(entries, first(snapshot.path("order"), state.path("order"), sequence.path("order")));
        if (entries.isEmpty() && fallbackPlan != null) {
            for (String line : fallbackPlan.split("\\R")) {
                String name = line.trim();
                if (!name.isBlank()) entries.putIfAbsent(name, null);
            }
        }

        JsonNode experiments = first(state.path("experiments"), sequence.path("experiments"), snapshot.path("experiments"));
        if (entries.isEmpty() && experiments.isObject()) {
            experiments.fields().forEachRemaining(entry -> entries.put(entry.getKey(), entry.getValue()));
        }

        JsonNode completedNode = first(state.path("completed"), sequence.path("completed"), snapshot.path("completed"));
        Set<String> completed = new HashSet<>();
        if (completedNode.isArray()) {
            for (JsonNode node : completedNode) {
                String name = name(node);
                if (!name.isBlank()) {
                    completed.add(name);
                    entries.putIfAbsent(name, node);
                }
            }
        }
        String current = scalar(first(state.path("current"), sequence.path("current"), snapshot.path("current")));
        if (!current.isBlank()) entries.putIfAbsent(current, null);
        String failed = scalar(first(state.path("failed"), sequence.path("failed"), snapshot.path("failed")));
        if (!failed.isBlank()) entries.putIfAbsent(failed, null);

        // Older snapshots expose runs directly. Use them only when no queue or
        // state-derived tasks exist; their keys are data, not a fixed schedule.
        if (entries.isEmpty()) {
            Iterator<Map.Entry<String, JsonNode>> fields = snapshot.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getKey().startsWith("run") && field.getValue().isObject()
                        && field.getValue().has("rows")) {
                    entries.put(field.getKey().substring(3), field.getValue());
                }
            }
        }

        List<Item> result = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : entries.entrySet()) {
            String name = entry.getKey();
            JsonNode data = entry.getValue();
            JsonNode experiment = experiments.path(name);
            JsonNode detail = data != null && data.isObject() ? data : experiment;
            if ((!detail.isObject() || detail.isEmpty()) && experiment.isObject()) detail = experiment;
            String stage = scalar(first(detail.path("status"), detail.path("stage"), detail.path("phase")));
            String status = status(name, stage, completed, current, failed);

            int done = integer(detail, "rows", "completed_epochs", "current_epoch", "completed", "epoch");
            int total = integer(detail, "total_epochs", "epochs", "epoch_total", "max_epochs", "total");
            double fraction = directFraction(detail);

            if (name.equals(current)) {
                JsonNode activeRun = findActiveRun(snapshot);
                if (done == 0) done = integer(activeRun, "rows", "completed_epochs", "current_epoch", "epoch");
                if (total == 0) total = integer(activeRun, "total_epochs", "epochs", "epoch_total", "max_epochs");
                if (total == 0) {
                    Matcher match = FRACTION.matcher(scalar(snapshot.path("progress")));
                    if (match.find()) {
                        done = Integer.parseInt(match.group(1));
                        total = Integer.parseInt(match.group(2));
                    }
                }
            }
            if (status.equals("completed")) fraction = 1;
            else if (total > 0) fraction = Math.min(1, Math.max(0, (double) done / total));
            else if (status.equals("pending")) fraction = 0;
            result.add(new Item(name, status, done, total, fraction));
        }
        return List.copyOf(result);
    }

    private static void addPlan(LinkedHashMap<String, JsonNode> entries, JsonNode plan) {
        if (plan.isArray()) {
            for (JsonNode node : plan) {
                String name = name(node);
                if (!name.isBlank()) entries.putIfAbsent(name, node);
            }
        } else if (plan.isObject()) {
            plan.fields().forEachRemaining(entry -> entries.putIfAbsent(entry.getKey(), entry.getValue()));
        }
    }

    private static String name(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isValueNode()) return node.asText();
        return scalar(first(node.path("name"), node.path("step"), node.path("id"), node.path("task")));
    }

    private static String status(String name, String stage, Set<String> completed, String current, String failed) {
        if (completed.contains(name) || matches(stage, "COMPLETE", "COMPLETED", "DONE", "FINISHED", "VALIDATED", "SUCCESS")) return "completed";
        if (name.equals(failed) || matches(stage, "FAILED", "ERROR")) return "failed";
        if (matches(stage, "SKIPPED", "SKIP", "CANCELLED")) return "skipped";
        if (name.equals(current) || matches(stage, "RUNNING", "TRAINING", "ACTIVE", "IN_PROGRESS")) return "running";
        return "pending";
    }

    private static boolean matches(String value, String... options) {
        for (String option : options) if (option.equalsIgnoreCase(value)) return true;
        return false;
    }

    private static JsonNode findActiveRun(JsonNode snapshot) {
        Iterator<Map.Entry<String, JsonNode>> fields = snapshot.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey().startsWith("run") && entry.getValue().path("rows").asInt(0) > 0) return entry.getValue();
        }
        return snapshot.path("missing");
    }

    private static int integer(JsonNode node, String... fields) {
        if (node == null) return 0;
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.canConvertToInt() || value.isTextual()) {
                int parsed = value.asInt(0);
                if (parsed > 0) return parsed;
            }
        }
        return 0;
    }

    private static double directFraction(JsonNode node) {
        if (node == null) return -1;
        JsonNode value = first(node.path("progress_percent"), node.path("percent"), node.path("progress"));
        if (!value.isNumber()) return -1;
        double amount = value.asDouble();
        if (amount > 1) amount /= 100;
        return Math.min(1, Math.max(0, amount));
    }

    private static JsonNode object(JsonNode parent, String field) {
        return parent.path(field);
    }

    private static JsonNode first(JsonNode... values) {
        for (JsonNode value : values) if (value != null && !value.isMissingNode() && !value.isNull()) return value;
        return values[0];
    }

    private static String scalar(JsonNode value) {
        return value != null && value.isValueNode() && !value.isNull() ? value.asText() : "";
    }
}
