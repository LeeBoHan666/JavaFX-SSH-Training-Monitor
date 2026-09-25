# 训练监视快照 JSON 接口说明

这份说明用于交给生成训练快照脚本的 AI/开发者。快照脚本由训练监视器通过 SSH 周期执行：训练输出视图约每 0.5 秒一次，整机状态视图约每 0.25 秒一次；实际频率受网络与服务器响应时间影响。脚本应检查当前训练状态，并向 **标准输出 stdout 只打印一行完整 JSON**。提示语、调试日志和进度条文字不要混入 stdout，否则监视器无法可靠解析。诊断信息可以写到 stderr。由于轮询较频繁，脚本必须轻量，并为 GPU/系统信息查询设置超时。

## 监视器会显示什么

- GPU 是否可用、空闲、存在负载或正被训练任务使用，以及型号、利用率、显存、温度和功耗。
- 每个训练进程的 PID、状态、命令和对应运行目录。
- 每个正在运行任务的整体进度、当前 Epoch/Batch 进度条、最新一轮指标和历史最佳指标。
- 已完成任务摘要、失败任务和等待开始的任务。
- 最新训练日志片段。
- 可切换查看的整机状态：GPU 利用率/显存、CPU 利用率、系统内存使用量。

项目名、任务名、PID、轮数、Batch 数和指标都必须从服务器实际状态读取，不能写死示例值。取不到的字段用 `null` 或省略，不要猜测或伪造百分比。

## 推荐 JSON 格式

```json
{
  "timestamp": "2026-09-25T11:30:00+08:00",
  "status": "RUNNING",
  "state": {
    "status": "RUNNING",
    "current": "train-main",
    "queue": [
      {
        "name": "train-main",
        "status": "RUNNING",
        "pid": 4212,
        "command": "python -u train.py --epochs 100",
        "run": "/workspace/project/runs/train-main",
        "current_epoch": 20,
        "total_epochs": 100,
        "completed_epochs": 19,
        "current_step": 100,
        "total_steps": 112,
        "latest": {
          "epoch": 19,
          "train_loss": 1.3411,
          "precision": 0.3997,
          "recall": 0.3463,
          "mAP50": 0.5397,
          "mAP50-95": 0.1549
        },
        "best": {
          "epoch": 16,
          "mAP50": 0.5612,
          "mAP50-95": 0.1628
        }
      },
      {"name": "train-next", "status": "PENDING"}
    ],
    "completed": ["train-prepare"],
    "failed": null
  },
  "processes": [
    {
      "pid": 4212,
      "state": "RUNNING",
      "command": "python -u train.py --epochs 100",
      "run": "/workspace/project/runs/train-main"
    }
  ],
  "gpu": {
    "available": true,
    "name": "NVIDIA GPU",
    "utilization": 72,
    "memory_used_mib": 6009,
    "memory_total_mib": 24564,
    "temperature_c": 57,
    "power_w": 242.0
  },
  "system": {
    "cpu_percent": 38.5,
    "memory": {
      "used_mib": 32768,
      "total_mib": 65536,
      "percent": 50.0
    }
  },
  "epoch_progress": {
    "epoch": 20,
    "total_epochs": 100,
    "step": 100,
    "total_steps": 112
  },
  "progress": "epoch=20/100 step=100/112 loss=1.2450 pos=33 mem=3.6G"
}
```

上例中的数值和名称仅用于说明字段格式。生成脚本必须根据目标项目和服务器实时状态填充真实值。

## 字段定义

### 快照与状态

| 字段 | 类型 | 用途 |
|---|---|---|
| `timestamp` | 字符串 | 采样时间，建议 ISO 8601，含时区 |
| `status` | 字符串 | 总体状态，如 `RUNNING`、`IDLE`、`COMPLETED`、`FAILED` |
| `state.status` | 字符串 | 训练队列/当前训练状态，会显示在面板状态徽标 |
| `state.current` | 字符串 | 当前任务名称 |
| `state.queue` | 数组 | 按执行顺序排列的任务；每项可以是任务名字符串或下文所示对象 |
| `state.completed` | 数组 | 已完成任务名，或包含 `name`/`step` 的对象 |
| `state.failed` | 字符串或 null | 失败任务名 |
| `state.experiments` | 对象 | 可选；以任务名为键，保存该任务的状态、进度和指标，适合补充 `queue` 中的简略任务 |

### 队列任务对象

任务对象可放在 `state.queue` 数组中，也可放在 `state.experiments[任务名]` 中。推荐字段：

| 字段 | 类型 | 用途 |
|---|---|---|
| `name` | 字符串 | 稳定且唯一的任务名 |
| `status` | 字符串 | `RUNNING`、`COMPLETED`、`PENDING`、`FAILED` 或 `SKIPPED` |
| `pid` | 整数 | 对应训练进程 PID |
| `command` | 字符串 | 训练进程命令行 |
| `run` | 字符串 | 本次训练输出/运行目录 |
| `current_epoch` | 整数 | 正在进行的轮次；未知时为 null |
| `total_epochs` | 整数 | 实际配置的总轮数，不能使用固定默认值代替 |
| `completed_epochs` | 整数 | 已完整结束的轮数 |
| `current_step` | 整数 | 当前轮已完成的 Batch/Step 数 |
| `total_steps` | 整数 | 当前轮总 Batch/Step 数 |
| `progress_percent` | 数字 | 可选的任务整体进度，范围 0–100；仅在能准确计算时提供 |
| `latest` | 对象 | 最近一个已产生结果的 Epoch 指标 |
| `best` | 对象 | 历史最佳指标及其 Epoch |

`latest` 与 `best` 中可提供 `epoch`、`train_loss`、`precision`、`recall`、`mAP50`、`mAP50-95`。监视器也兼容 `P`/`R`、`last_p`/`last_r`、`last_50`/`last_5095` 等常用别名。最佳指标请按训练脚本真实的选择标准计算，并在 `best.epoch` 写明对应轮次。

### 进程与 GPU

`processes` 是数组，每个训练 PID 一项。每项至少提供 `pid`、`state`、`command`；最好提供对应的 `run` 或任务 `name`，以便将进程与队列项关联。无训练进程时返回空数组 `[]`。

`gpu` 推荐为对象：

- `available`: 布尔值，GPU 查询是否成功。
- `name`: GPU 型号。
- `utilization`: GPU 利用率百分比数字。
- `memory_used_mib`、`memory_total_mib`: 显存使用量和总量，单位 MiB。
- `temperature_c`: 温度，摄氏度。
- `power_w`: 功耗，瓦特。

如果 GPU 不可用，返回 `{"available": false, "error": "原因"}`。不要把缺失的 GPU 数据伪装成 0%。

### 整机状态

训练输出面板顶部可以切换到“整机状态”。请在顶层 `system` 对象提供主机 CPU 和内存数据；GPU 利用率、显存、温度与功耗沿用顶层 `gpu` 对象。推荐格式：

```json
{
  "gpu": {
    "available": true,
    "name": "NVIDIA GPU",
    "utilization": 72,
    "memory_used_mib": 6009,
    "memory_total_mib": 24564,
    "temperature_c": 57,
    "power_w": 242.0
  },
  "system": {
    "cpu_percent": 38.5,
    "memory": {
      "used_mib": 32768,
      "total_mib": 65536,
      "percent": 50.0
    }
  }
}
```

`system.cpu_percent` 为整机 CPU 利用率百分比。`system.memory.used_mib`、`total_mib` 是已用/总系统内存，`percent` 是可选使用率；未提供时监视器会依据已用和总量计算。各字段应从服务器实时读取，暂时无法获取时用 `null` 或省略，不要用 0 代替未知值。监视器会在调用自定义快照脚本时自动补充 CPU 和内存；内置通用快照也会采集这些主机信息。

### Epoch 与 Batch 进度

有一个当前任务时，可以在顶层提供 `epoch_progress`；并行任务时，应把它放在各自的队列任务对象中。字段 `epoch`、`total_epochs`、`step`、`total_steps` 都是实际整数。监视器据此计算本轮 Batch 进度条以及整体训练进度。也可以提供 `progress` 字符串作为最新日志，例如：

```text
epoch=20/100 step=100/112 loss=1.2450 pos=33 mem=3.6G
```

`progress` 只用于显示日志和兼容旧快照；结构化字段优先。

## 可直接交给快照脚本生成 AI 的要求

```text
请为我的训练项目编写一个远程训练快照脚本。脚本每次运行时读取服务器当前真实状态，并且只向 stdout 输出一行 UTF-8 JSON，不输出 Markdown、代码围栏、说明文字、SSH 欢迎信息或调试内容。调试信息写入 stderr。

JSON 必须遵循 SNAPSHOT_JSON_SPEC.md：返回 status、state.queue、state.current、state.completed、state.failed、processes、gpu、epoch_progress、progress。每个队列任务应尽量包含 name、status、pid、command、run、current_epoch、total_epochs、completed_epochs、current_step、total_steps、latest、best。latest 是最近已完成轮次的指标；best 是历史最佳指标，并包含 best 所属 epoch。GPU 信息须包含 available、name、utilization、memory_used_mib、memory_total_mib、temperature_c、power_w。队列按实际执行顺序排列，任务名和配置从项目状态/训练进程/训练日志/结果 CSV 中读取。

所有数值必须来自实际配置或训练产物；不要写死项目名、任务名、总轮数、Batch 数、PID 或指标。无法读取的值填 null 或省略，不能猜测。正确处理文件暂时不存在、训练尚未开始、训练完成、失败和多个并行训练进程。请先检查我提供的项目文件和训练代码，再决定如何定位队列状态、训练日志、结果 CSV 和 GPU 信息。输出完整脚本，并说明安装路径和运行方式。
```

## 实现提示

- 训练状态最好由训练启动器维护一份原子更新的 `queue-state.json`，避免仅靠猜测进程命令判断任务完成。
- Epoch 结果通常从项目自己的 CSV/JSON 指标文件读取；读取 CSV 时按列名匹配，不要依赖列顺序。
- 当前 Batch 进度从训练日志或训练器实时状态读取；只有 Epoch 指标文件时，只能报告完整 Epoch 数，不能虚构当前 Batch 百分比。
- JSON 必须正确转义路径、命令和日志中的引号、反斜杠及换行。
- 标准输出必须保持纯 JSON；Python 脚本可使用 `json.dumps(data, ensure_ascii=False)`。
