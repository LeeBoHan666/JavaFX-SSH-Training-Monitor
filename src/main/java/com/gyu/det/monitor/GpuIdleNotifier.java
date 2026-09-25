package com.gyu.det.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sends one completion reminder after an observed training run exits and the GPU stays idle. */
final class GpuIdleNotifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long IDLE_CONFIRM_MILLIS = 30_000;
    private static final double IDLE_UTILIZATION_LIMIT = 5.0;

    private boolean trainingWasSeen;
    private boolean notified;
    private long idleSince;

    String observe(String rawJson, long nowMillis) {
        final JsonNode root;
        try {
            root = JSON.readTree(rawJson);
        } catch (Exception ignored) {
            resetIdleTimer();
            return null;
        }

        JsonNode state = root.path("state");
        List<JsonNode> processLists = List.of(root.path("processes"), state.path("processes"));
        List<JsonNode> queueLists = List.of(root.path("queue"), state.path("queue"),
                root.path("tasks"), state.path("tasks"));
        boolean processListKnown = processLists.stream().anyMatch(JsonNode::isArray);
        boolean queueKnown = queueLists.stream().anyMatch(JsonNode::isArray);
        boolean processActive = false;
        for (JsonNode processList : processLists) {
            if (!processList.isArray()) continue;
            for (JsonNode process : processList) processActive |= processActive(process);
        }
        String processSummary = scalar(root.path("process"));
        if (processSummary.toUpperCase(Locale.ROOT).startsWith("RUNNING:")) {
            processActive |= !processSummary.substring("RUNNING:".length()).isBlank();
        }

        QueueState queueState = inspectQueues(queueLists);
        List<TrainingQueue.Item> normalizedQueue = TrainingQueue.from(root);
        if (!normalizedQueue.isEmpty()) {
            queueKnown = true;
            queueState = new QueueState(queueState.running(), queueState.pending()
                    || normalizedQueue.stream().anyMatch(item -> item.status().equals("pending")));
        }
        boolean trainingActive = processActive || queueState.running();
        if (trainingActive) {
            trainingWasSeen = true;
            notified = false;
            resetIdleTimer();
            return null;
        }
        if (!trainingWasSeen) return null;

        boolean stoppedKnown = (processListKnown || isStopped(processSummary) || queueKnown)
                && !queueState.pending();
        double gpuUtilization = gpuUtilization(root.path("gpu"));
        if (!stoppedKnown || gpuUtilization < 0 || gpuUtilization >= IDLE_UTILIZATION_LIMIT) {
            resetIdleTimer();
            return null;
        }

        if (idleSince == 0) idleSince = nowMillis;
        if (!notified && nowMillis - idleSince >= IDLE_CONFIRM_MILLIS) {
            notified = true;
            return "训练进程已停止，GPU 已连续空闲 30 秒。请确认训练是否完成。";
        }
        return null;
    }

    void resetIdleTimer() {
        idleSince = 0;
    }

    private static boolean processActive(JsonNode process) {
        String state = scalar(first(process.path("status"), process.path("state"), process.path("phase")))
                .trim().toUpperCase(Locale.ROOT);
        if (isTerminal(state)) return false;
        return process.has("pid") || state.isBlank() || !isTerminal(state);
    }

    private static boolean isTerminal(String state) {
        return List.of("Z", "X", "STOPPED", "COMPLETED", "COMPLETE", "FINISHED", "EXITED", "FAILED", "ERROR", "SKIPPED")
                .contains(state);
    }

    private static boolean isStopped(String summary) {
        String normalized = summary.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("STOPPED") || normalized.equals("IDLE") || normalized.equals("COMPLETED")
                || normalized.equals("FINISHED") || normalized.equals("NO TRAINING PROCESS");
    }

    private static QueueState inspectQueues(List<JsonNode> lists) {
        boolean running = false;
        boolean pending = false;
        for (JsonNode list : lists) {
            if (!list.isArray()) continue;
            for (JsonNode item : list) {
                String status = item.isObject()
                        ? scalar(first(item.path("status"), item.path("state"), item.path("phase")))
                        : "";
                String normalized = status.trim().toUpperCase(Locale.ROOT);
                if (normalized.equals("RUNNING") || normalized.equals("TRAINING") || normalized.equals("ACTIVE")) {
                    running = true;
                } else if (normalized.equals("PENDING") || normalized.equals("QUEUED")
                        || normalized.equals("WAITING") || normalized.equals("WAITING_TO_START")) {
                    pending = true;
                }
            }
        }
        return new QueueState(running, pending);
    }

    private static double gpuUtilization(JsonNode gpu) {
        JsonNode value = first(gpu.path("utilization"), gpu.path("utilization_gpu"), gpu.path("gpu_util"));
        if (value != null && value.isNumber()) return value.asDouble();
        if (value != null && value.isValueNode()) {
            try { return Double.parseDouble(value.asText().replace("%", "").trim()); }
            catch (NumberFormatException ignored) { }
        }
        if (gpu.isValueNode()) {
            String raw = gpu.asText();
            String[] parts = raw.split(",", -1);
            if (parts.length >= 4) {
                try { return Double.parseDouble(parts[3].trim().replace("%", "")); }
                catch (NumberFormatException ignored) { }
            }
            Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%").matcher(raw);
            if (matcher.find()) {
                try { return Double.parseDouble(matcher.group(1)); }
                catch (NumberFormatException ignored) { }
            }
        }
        return -1;
    }

    private static JsonNode first(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull()) return node;
        }
        return null;
    }

    private static String scalar(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull() && node.isValueNode()
                ? node.asText() : "";
    }

    private record QueueState(boolean running, boolean pending) { }
}
