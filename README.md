# 训练监视器 · Version 1.0

# Training Monitor · Version 1.0

一个通用 JavaFX 桌面训练监视器：通过 SSH 连接训练服务器，支持结构化训练快照和传统 Bash 两种监视方式。

当前版本：**Version 1.0**

## 主要功能

- SSH 用户名、服务器地址、端口和密码连接
- SSH 登录名直接写在完整 SSH 命令中，密码单独填写
- 项目名称、项目路径、Python 环境路径和快照脚本可通过 TextField 自定义
- 选择传统 Bash 后，结构化配置 TextField 会切换为多行 Bash TextArea
- 监视方式下方先填写 SSH 命令和密码，再选择传统 Bash 或结构化训练配置
- 连接后自动执行服务器训练快照命令
- 结构化模式固定显示 P、R、mAP、batch/bench 等训练指标
- 传统 Bash 模式位于配置下方，可执行 `watch`、`tail`、`nvidia-smi` 或自定义脚本
- 每 1 秒原位刷新，不堆叠旧画面
- 支持连接失败后自动重试
- 支持交互式 Shell 输入
- 支持本地明文保存 SSH 命令、密码和监视配置
- 深色终端输出区，字体支持自动换行，不显示底部横向滚动条

## 环境

- JDK 17+
- Maven 3.9+

## 运行

```powershell
mvn clean compile
mvn javafx:run
```

## Windows EXE

已经生成的桌面程序位于：

```text
桌面\训练监视器\训练监视器.exe
```

运行时请保留整个 `训练监视器` 文件夹，不要单独移动 EXE 文件。

## SSH 命令示例

```text
ssh user@192.168.1.100 "nvidia-smi; tail -f /workspace/train/logs/train.log"
ssh -p 2222 user@server "cd /workspace/project && python train.py"
ssh -l user -p 2222 server "tail -f train.log"
```

SSH 命令必须包含登录名，例如：

```text
ssh user@your-server.example.com -p 2222
```

不带远程命令时，程序会进入交互式 Shell；可以在输出区下方的输入框发送 `nvidia-smi`、`watch`、`tail` 等命令。

默认选择“结构化训练监视”，连接后每 1 秒调用“项目路径 + 训练快照脚本”。默认配置对应 `/root/gyu-yolo11-v2/monitor_snapshot.py`，但可以通过界面 TextField 改成其他项目路径和脚本。结构化输出固定显示服务器任务、顺序状态、GPU、P、R、mAP、batch/bench、当前批次和预计剩余时间。

如果服务器路径不同，可以在项目路径、Python 环境路径和训练快照脚本输入框中修改。默认组合命令为：

```text
/root/gyu-paper-env/bin/python /root/gyu-yolo11-v2/monitor_snapshot.py
```

切换为“传统 Bash”后，可以在下方输入 Bash 命令，例如：

```bash
watch -n 1 -t nvidia-smi
tail -f train.log
python monitor.py
```

点击“保存配置”后，SSH 命令、密码和选项会以明文保存在本机：`%USERPROFILE%\.gyu-det\training-monitor.properties`，下次启动会自动加载。默认勾选“信任未知主机”是为了方便第一次连接；正式环境建议取消勾选并提前配置本机 SSH `known_hosts`。

界面采用 JavaFX，使用深色终端输出区、连接配置卡片、实时状态提示和自适应窗口布局。

## 项目结构

```text
src/main/java/com/gyu/det/monitor/
├─ TrainingMonitorApp.java   JavaFX 界面和连接控制
├─ SshCommand.java           SSH 命令解析
├─ SshMonitor.java            SSH 连接、轮询和 Shell 通道
└─ SnapshotFormatter.java     原始 V2 监视器格式化
src/main/resources/monitor.css  界面样式
```

## 注意事项

- 明文保存密码是按使用要求实现的，请确保本机账户安全。
- “信任未知主机”会关闭首次连接时的主机指纹校验；正式环境建议关闭，并配置 SSH `known_hosts`。
- 仓库中的 `target/` 是构建产物，不提交到 Git。

---

## English

GYU-DET Training Monitor is a general JavaFX desktop application for monitoring remote training jobs over SSH. It supports both structured training snapshots and traditional Bash commands.

### Features

- Configure SSH command, server password, project name and project path with text fields.
- Configure the Python interpreter path and snapshot script name for each project.
- Keep fixed training metrics such as P, R, mAP and batch/bench visible in structured mode.
- Use traditional Bash mode for commands such as `watch`, `tail`, `nvidia-smi` or a custom monitor script.
- Refresh structured training snapshots in place every second without stacking old frames.
- Automatically retry the connection after a temporary failure.
- Save the SSH command, password and monitor settings locally in plaintext, as requested.

### Monitor modes

The monitor mode selector is placed at the top of the connection panel.

In structured mode, project settings are shown as individual text fields. In traditional Bash mode, those fields are replaced by a large multi-line text area for training commands.

Structured training monitor:

```text
Python path + project path + snapshot script
```

Default command:

```text
/root/gyu-paper-env/bin/python /root/gyu-yolo11-v2/monitor_snapshot.py
```

Traditional Bash:

```bash
watch -n 1 -t nvidia-smi
tail -f train.log
python monitor.py
```

### Build and run

Requirements: JDK 17 or later and Maven 3.9 or later.

```powershell
mvn clean compile
mvn javafx:run
```

The packaged Windows application is located at:

```text
Desktop\训练监视器\训练监视器.exe
```

Keep the complete `训练监视器` folder together when moving or distributing the application.

### Local configuration

After clicking “保存配置 / Save Configuration”, settings are stored locally in plaintext at:

```text
%USERPROFILE%\.gyu-det\training-monitor.properties
```

The configuration is loaded automatically at startup. Disable “信任未知主机 / Trust Unknown Host” in production environments and configure SSH `known_hosts` when host verification is required.
