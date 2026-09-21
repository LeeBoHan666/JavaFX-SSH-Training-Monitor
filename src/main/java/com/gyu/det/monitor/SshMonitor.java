package com.gyu.det.monitor;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Owns one SSH connection and streams the remote command output. */
public final class SshMonitor implements AutoCloseable {
    private final SshCommand command;
    private final char[] password;
    private final boolean trustUnknownHost;
    private Session session;
    private Channel channel;
    private OutputStream shellOutput;
    private volatile boolean closed;

    public SshMonitor(SshCommand command, char[] password, boolean trustUnknownHost) {
        this.command = command;
        this.password = password.clone();
        this.trustUnknownHost = trustUnknownHost;
    }

    public void stream(Consumer<String> onOutput, Consumer<String> onStatus) throws Exception {
        connectSession(onStatus);

        InputStream input;
        InputStream error;
        if (command.interactiveShell()) {
            ChannelShell shell = (ChannelShell) session.openChannel("shell");
            shell.setPty(true);
            shell.setPtySize(140, 45, 1400, 900);
            shell.setInputStream(null);
            channel = shell;
        } else {
            ChannelExec exec = (ChannelExec) session.openChannel("exec");
            exec.setCommand(command.remoteCommand());
            exec.setInputStream(null);
            channel = exec;
        }
        channel.connect(15_000);
        input = channel.getInputStream();
        error = command.interactiveShell() ? null : ((ChannelExec) channel).getErrStream();
        if (command.interactiveShell()) {
            shellOutput = channel.getOutputStream();
            onStatus.accept("已连接，进入交互式 Shell");
        } else {
            onStatus.accept("已连接，正在实时读取训练输出");
        }

        byte[] buffer = new byte[8192];
        while (!closed && !channel.isClosed()) {
            readAvailable(input, buffer, onOutput);
            if (error != null) readAvailable(error, buffer, onOutput);
            Thread.sleep(100);
        }
        readAvailable(input, buffer, onOutput);
        if (error != null) readAvailable(error, buffer, onOutput);
        int exit = channel.getExitStatus();
        if (!closed) onStatus.accept("远程命令已结束，退出码：" + exit);
    }

    public void poll(String remoteCommand, long intervalMillis,
                     Consumer<String> onFrame, Consumer<String> onStatus) throws Exception {
        connectSession(onStatus);
        onStatus.accept("已连接，正在读取 GYU-DET 训练快照");
        while (!closed) {
            long cycleStarted = System.nanoTime();
            ChannelExec exec = (ChannelExec) session.openChannel("exec");
            channel = exec;
            exec.setCommand(remoteCommand);
            exec.setInputStream(null);
            InputStream output = exec.getInputStream();
            InputStream error = exec.getErrStream();
            exec.connect(15_000);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!closed && !exec.isClosed()) {
                readAvailable(output, buffer, stdout);
                readAvailable(error, buffer, stderr);
                Thread.sleep(20);
            }
            readAvailable(output, buffer, stdout);
            readAvailable(error, buffer, stderr);
            int exit = exec.getExitStatus();
            exec.disconnect();
            channel = null;
            if (closed) break;
            String errorText = stderr.toString(StandardCharsets.UTF_8).trim();
            if (exit != 0) {
                throw new IllegalStateException(errorText.isBlank()
                        ? "训练快照命令退出码：" + exit : errorText);
            }
            String frame = stdout.toString(StandardCharsets.UTF_8).trim();
            if (!frame.isBlank()) onFrame.accept(frame);

            long elapsedMillis = (System.nanoTime() - cycleStarted) / 1_000_000;
            long remaining = Math.max(0, intervalMillis - elapsedMillis);
            while (!closed && remaining > 0) {
                long step = Math.min(remaining, 100);
                Thread.sleep(step);
                remaining -= step;
            }
        }
    }

    private void connectSession(Consumer<String> onStatus) throws Exception {
        JSch jsch = new JSch();
        session = jsch.getSession(command.user(), command.host(), command.port());
        session.setPassword(new String(password));
        if (trustUnknownHost) {
            session.setConfig("StrictHostKeyChecking", "no");
        }
        session.setTimeout(15_000);
        session.setServerAliveInterval(15_000);
        onStatus.accept("正在连接 " + command.user() + "@" + command.host() + ":" + command.port() + " …");
        session.connect();
    }

    public synchronized void sendInput(String text) throws Exception {
        if (shellOutput == null || channel == null || channel.isClosed()) {
            throw new IllegalStateException("当前不是可交互的 Shell 连接");
        }
        shellOutput.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        shellOutput.flush();
    }

    private static void readAvailable(InputStream input, byte[] buffer, Consumer<String> onOutput) throws Exception {
        while (input.available() > 0) {
            int available = Math.min(buffer.length, input.available());
            int count = input.read(buffer, 0, available);
            if (count < 0) return;
            onOutput.accept(new String(buffer, 0, count, StandardCharsets.UTF_8));
        }
    }

    private static void readAvailable(InputStream input, byte[] buffer, ByteArrayOutputStream output) throws Exception {
        while (input.available() > 0) {
            int available = Math.min(buffer.length, input.available());
            int count = input.read(buffer, 0, available);
            if (count < 0) return;
            output.write(buffer, 0, count);
        }
    }

    @Override
    public void close() {
        closed = true;
        if (channel != null) channel.disconnect();
        if (session != null) session.disconnect();
        shellOutput = null;
        java.util.Arrays.fill(password, '\0');
    }
}
