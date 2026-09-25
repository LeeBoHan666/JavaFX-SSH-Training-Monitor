# 训练监视器 · Version 1.0

# Training Monitor · Version 1.0

一个使用 JavaFX 编写的桌面训练监视器，通过 SSH 查看远程训练输出、训练队列、GPU 与整机资源状态。支持结构化 JSON 快照和传统 Bash 两种监视方式。

GYU-DET Training Monitor is a JavaFX desktop application for monitoring remote training output, task queues, GPU metrics, and whole-machine resources over SSH. It supports structured JSON snapshots and traditional Bash monitoring.

## 功能 Features

- 在应用中填写完整 SSH 命令（包括登录名和端口）及连接密码。
- Configure the full SSH command, including login name and port, plus the connection password.
- 结构化模式可配置项目名称、项目路径、Python 路径和快照脚本；训练队列由服务器快照提供，不需要在界面手工填写。
- Structured mode lets you configure the project name/path, Python interpreter, and snapshot script. The task queue comes from the server snapshot; there is no manual queue field.
- 传统 Bash 模式提供多行命令输入，适合 `watch`、`tail`、`nvidia-smi` 或项目自带监视命令。
- Traditional Bash mode provides a multi-line command area for `watch`, `tail`, `nvidia-smi`, or project-specific monitor commands.
- 训练输出中按服务器返回的队列顺序展示任务；已完成和待开始任务简洁显示，运行任务展示进程、轮次进度、最新/最佳指标和 GPU 状态。
- Training output follows the queue order returned by the server. Completed and pending tasks are compact; running tasks show process, epoch progress, latest/best metrics, and GPU status.
- 右侧可在训练输出和整机状态间切换，两者不会并排显示。整机状态包括 GPU 利用率/显存、CPU 和系统内存以及实时折线图。
- Switch the right panel between training output and whole-machine status. The views are exclusive. System status includes GPU utilization/VRAM, CPU and memory usage, and a live trend chart.
- 选择“整机状态”时以约 0.25 秒间隔读取快照；训练输出视图约每 0.5 秒刷新。实际频率受 SSH 和服务器响应时间影响。
- Snapshot polling is approximately every 0.25 seconds in whole-machine view and every 0.5 seconds in training-output view. Actual timing depends on SSH and server response latency.
- 自定义快照缺失时尝试使用内置通用读取器；连接中断后自动重试。
- If a custom snapshot script is missing, the app can fall back to its built-in generic reader. Disconnected sessions are retried automatically.
- 训练进程退出、队列没有待执行项且 GPU 持续空闲时发送系统通知。
- A system notification is sent when training processes have exited, no queued tasks remain, and the GPU stays idle.
- 连接前自动保存配置到本机；命令、密码和设置按用户要求以明文保存在本地。
- Configuration is saved locally before connecting. SSH commands, passwords, and settings are stored in plaintext as requested.

## 环境 Requirements

- JDK 26（推荐；用于当前 JavaFX 26 版本）/ JDK 26 (recommended for the current JavaFX 26 dependency).
- Maven 3.9 或更新版本 / Maven 3.9 or later.
- Windows 运行打包程序时无需单独安装 JDK；构建独立 EXE/app-image 时需要安装带 `jpackage` 的 JDK。
- The packaged Windows app includes its runtime. Building a Windows app-image requires a JDK that provides `jpackage`.

## 运行与构建 Build and run

在项目根目录执行 / Run from the project root:

```powershell
mvn clean package
mvn javafx:run
```

`mvn clean package` 生成 `target/training-monitor-1.0.0-shaded.jar`。依赖和 JavaFX 版本配置见 [pom.xml](pom.xml)。此 GitHub 源码包不包含 EXE、JRE、构建缓存或个人配置。

`mvn clean package` creates `target/training-monitor-1.0.0-shaded.jar`. See [pom.xml](pom.xml) for dependency versions. This GitHub source bundle intentionally excludes executables, runtimes, build caches, and personal settings.

## SSH 与监视方式 SSH and monitor modes

SSH 命令示例 / SSH command examples:

```text
ssh user@server.example.com -p 2222
ssh -p 2222 user@server.example.com
ssh -l user -p 2222 server.example.com
```

SSH 命令必须包含登录名。仅提供登录命令时，程序进入交互式 Shell；结构化模式会执行配置的快照读取命令。

The SSH command must include a login name. A login-only command opens an interactive shell; structured mode runs the configured snapshot command.

结构化模式运行逻辑：

```text
SSH 登录 → Python 路径 + 项目路径 + 快照脚本 → 单行 JSON → 训练/整机状态视图
```

The structured-mode flow is:

```text
SSH login → Python path + project path + snapshot script → one-line JSON → training/system views
```

完整字段、格式示例及异常值处理规则见 [SNAPSHOT_JSON_SPEC.md](SNAPSHOT_JSON_SPEC.md)。可将随仓库附带的 Skill 提供给 AI，让它根据具体训练项目生成或修复快照脚本：

See [SNAPSHOT_JSON_SPEC.md](SNAPSHOT_JSON_SPEC.md) for the complete schema, examples, and unknown-value rules. The bundled Skill can guide an AI to create or repair a snapshot script for a particular training project:

```text
.agents/skills/gyu-training-monitor-snapshot/SKILL.md
```

服务器快照建议包含 `state.queue`（按真实执行顺序）、当前/已完成/失败任务、相关进程、GPU 数据和当前训练进度。没有可靠数据时请返回 `null` 或省略字段，不要猜测；单次调用的标准输出必须只有一行 JSON。整机状态模式轮询更快，因此快照脚本应轻量并为系统查询设置超时。

The server snapshot should include `state.queue` in actual execution order, current/completed/failed tasks, relevant processes, GPU readings, and live training progress. Use `null` or omit unavailable values—never guess. Each invocation must write exactly one JSON line to stdout. Since whole-machine view polls more frequently, keep the script lightweight and bound system-query timeouts.

传统 Bash 示例 / Traditional Bash examples:

```bash
watch -n 1 -t nvidia-smi
tail -f train.log
python monitor.py
```

## 本地配置 Local configuration

程序将配置保存在 `%USERPROFILE%\.gyu-det\training-monitor.properties`，其中可能包含明文 SSH 密码。该路径已从 Git 忽略规则排除；请勿把配置文件、个人 SSH 命令或密码提交到仓库。

Settings are stored at `%USERPROFILE%\.gyu-det\training-monitor.properties` and may include the SSH password in plaintext. This path is excluded by Git ignore rules. Never commit this file, personal SSH commands, or passwords.

生产环境建议关闭“信任未知主机”，并正确配置本机 SSH `known_hosts`。

For production use, disable “Trust Unknown Host” and configure SSH `known_hosts` correctly.

## 项目结构 Project layout

```text
src/main/java/com/gyu/det/monitor/   JavaFX UI, SSH client, snapshot parser, dashboards, notifications
src/main/resources/                  CSS and bundled Python snapshot helpers
.agents/skills/                      GYU training snapshot authoring Skill
SNAPSHOT_JSON_SPEC.md                Snapshot JSON contract for server-side scripts
pom.xml                              Maven build and dependency configuration
README.md                            Chinese and English project documentation
```

## 上传 GitHub 前 Before publishing

- 只上传本目录内的源码、文档和配置模板；不要上传 `target/`、运行时目录、`.properties` 配置、SSH 密码或个人连接信息。
- Upload source, docs, and build configuration only. Do not upload `target/`, runtime folders, `.properties` settings, SSH passwords, or personal connection details.
- `.gitignore` 已忽略常见构建产物和本地配置文件。上传前仍请人工检查提交文件列表。
- `.gitignore` excludes common build outputs and local configuration files. Review the upload list manually before publishing.
