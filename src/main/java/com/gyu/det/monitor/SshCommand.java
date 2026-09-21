package com.gyu.det.monitor;

import java.util.ArrayList;
import java.util.List;

/** Parsed connection information extracted from a normal ssh command. */
public record SshCommand(String user, String host, int port, String remoteCommand) {

    public static SshCommand parse(String command) {
        List<String> args = tokenize(command == null ? "" : command.trim());
        if (args.isEmpty()) {
            throw new IllegalArgumentException("请输入 SSH 命令，例如：ssh user@server \"nvidia-smi\"");
        }

        int index = 0;
        String executable = args.get(index++);
        if (executable.contains("\\")) {
            executable = executable.substring(executable.lastIndexOf('\\') + 1);
        }
        if (!executable.equalsIgnoreCase("ssh") && !executable.equalsIgnoreCase("ssh.exe")) {
            throw new IllegalArgumentException("SSH 命令必须以 ssh 开头");
        }

        int port = 22;
        String userFromOption = null;
        String destination = null;
        List<String> remoteParts = new ArrayList<>();

        while (index < args.size()) {
            String arg = args.get(index++);
            if (arg.equals("--")) {
                if (index < args.size()) {
                    destination = args.get(index++);
                }
                break;
            }
            if (arg.equals("-p") || arg.equals("-P")) {
                if (index >= args.size()) {
                    throw new IllegalArgumentException("-p 后面缺少端口号");
                }
                try {
                    port = Integer.parseInt(args.get(index++));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("SSH 端口号无效");
                }
                continue;
            }
            if (arg.startsWith("-p") && arg.length() > 2) {
                try {
                    port = Integer.parseInt(arg.substring(2));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("SSH 端口号无效");
                }
                continue;
            }
            if (arg.equals("-l")) {
                if (index >= args.size()) {
                    throw new IllegalArgumentException("-l 后面缺少用户名");
                }
                userFromOption = args.get(index++);
                continue;
            }
            if (arg.startsWith("-l") && arg.length() > 2) {
                userFromOption = arg.substring(2);
                continue;
            }
            if (arg.startsWith("-")) {
                // Common SSH flags. They are local-client options and do not belong in the remote command.
                if (arg.equals("-i") || arg.equals("-F") || arg.equals("-J") || arg.equals("-o")) {
                    if (index < args.size()) index++;
                }
                continue;
            }
            if (destination == null) {
                destination = arg;
            } else {
                remoteParts.add(arg);
            }
        }

        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("SSH 命令中缺少 user@host");
        }
        String user = userFromOption;
        String host = destination;
        int at = destination.lastIndexOf('@');
        if (at >= 0) {
            user = destination.substring(0, at);
            host = destination.substring(at + 1);
        }
        if (host.isBlank()) {
            throw new IllegalArgumentException("SSH 命令中缺少服务器地址");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SSH 端口必须在 1 到 65535 之间");
        }

        String remote = String.join(" ", remoteParts).trim();
        return new SshCommand(user, host, port, remote);
    }

    public boolean interactiveShell() {
        return remoteCommand == null || remoteCommand.isBlank();
    }

    private static List<String> tokenize(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean single = false;
        boolean doubleQuote = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                token.append(c);
                escaped = false;
            } else if (c == '\\' && !single) {
                escaped = true;
            } else if (c == '\'' && !doubleQuote) {
                single = !single;
            } else if (c == '"' && !single) {
                doubleQuote = !doubleQuote;
            } else if (Character.isWhitespace(c) && !single && !doubleQuote) {
                if (token.length() > 0) {
                    result.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(c);
            }
        }
        if (escaped) token.append('\\');
        if (single || doubleQuote) throw new IllegalArgumentException("SSH 命令中的引号没有闭合");
        if (token.length() > 0) result.add(token.toString());
        return result;
    }
}
