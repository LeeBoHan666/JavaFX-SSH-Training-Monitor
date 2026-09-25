"""Read a project's existing queue state when no monitor_snapshot.py is installed."""

import csv
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys


project = Path(sys.argv[1])


def read_json(path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def find_state():
    candidates = [project / "queue-state.json", project / "state.json"]
    candidates.extend(sorted(project.glob("*sequence/state.json")))
    for path in candidates:
        if path.is_file():
            return read_json(path, {}), path.parent
    return {}, project


def find_seed(state):
    names = [state.get("current")]
    names.extend(item.get("log") for item in state.get("completed", []) if isinstance(item, dict))
    for value in names:
        match = re.search(r"-s(\d+)(?:$|\W)", str(value or ""))
        if match:
            return match.group(1)
    return None


def queue_and_state(state):
    normalized = dict(state)
    seed = find_seed(state)
    queue = state.get("queue") or state.get("order")
    if not queue:
        experiments = state.get("experiments")
        if isinstance(experiments, list):
            queue = [f"{name}-s{seed}" if seed and not re.search(r"-s\d+$", str(name)) else name
                     for name in experiments]
        elif isinstance(experiments, dict):
            queue = list(experiments)
        else:
            queue = []
    completed = []
    for item in state.get("completed", []):
        if isinstance(item, dict):
            name = item.get("step") or item.get("name") or item.get("experiment")
            if name and item.get("experiment") and seed and not re.search(r"-s\d+$", str(name)):
                name = f"{name}-s{seed}"
            if name:
                completed.append({"step": name})
        elif isinstance(item, str):
            completed.append(item)
    normalized["completed"] = completed
    return queue, normalized


def find_run(current):
    if not current:
        return None
    candidate = Path(current)
    if candidate.is_dir():
        return candidate
    direct = project / "runs" / current
    if direct.is_dir():
        return direct
    for folder in project.glob("*/runs/*"):
        if folder.is_dir() and (current in folder.name or folder.name in current):
            return folder
    return None


def summarize_run(run_dir):
    if run_dir is None:
        return {}
    phase = read_json(run_dir / "phase.json", {})
    result = {"run": run_dir.name, "stage": phase.get("phase", "训练中"), "rows": 0}
    try:
        run_config = read_json(run_dir / "run.json", {})
        result["total_epochs"] = int(run_config.get("epochs", 0))
    except (TypeError, ValueError):
        pass
    try:
        for line in (run_dir / "args.yaml").read_text(encoding="utf-8").splitlines():
            if line.startswith("epochs:"):
                result["total_epochs"] = int(line.split(":", 1)[1].strip())
                break
    except (OSError, ValueError):
        pass
    try:
        with (run_dir / "results.csv").open(newline="", encoding="utf-8") as handle:
            rows = list(csv.DictReader(handle))
    except OSError:
        rows = []
    result["rows"] = len(rows)
    if rows:
        def value(row, *aliases):
            normalized = {str(key).strip().lower(): item for key, item in row.items()}
            for alias in aliases:
                try:
                    return float(normalized[alias.lower()])
                except (KeyError, TypeError, ValueError):
                    continue
            return None

        last = rows[-1]
        for output, aliases in (("last_p", ("metrics/precision(b)", "precision", "p")),
                                ("last_r", ("metrics/recall(b)", "recall", "r")),
                                ("last_50", ("metrics/map50(b)", "map50", "map_50")),
                                ("last_5095", ("metrics/map50-95(b)", "map50-95", "map50_95", "map_50_95"))):
            metric = value(last, *aliases)
            if metric is not None:
                result[output] = metric
        best_key = next((key for key in rows[-1] if str(key).strip().lower() in
                         ("metrics/map50-95(b)", "map50-95", "map50_95", "map_50_95")), None)
        if best_key:
            best_row = max(rows, key=lambda row: value(row, best_key) if value(row, best_key) is not None else float("-inf"))
            best = {"epoch": value(best_row, "epoch")}
            for output, aliases in (("mAP50", ("metrics/map50(b)", "map50", "map_50")),
                                    ("mAP50-95", ("metrics/map50-95(b)", "map50-95", "map50_95", "map_50_95"))):
                metric = value(best_row, *aliases)
                if metric is not None:
                    best[output] = metric
            result["best"] = best
    return result


def discover_processes():
    found = []
    try:
        entries = Path("/proc").iterdir()
    except OSError:
        return found
    for entry in entries:
        if not entry.name.isdigit():
            continue
        try:
            raw = (entry / "cmdline").read_bytes().decode(errors="replace")
            args = [part for part in raw.split("\0") if part]
            command = " ".join(args)
            cwd = Path(os.readlink(entry / "cwd"))
        except OSError:
            continue
        if str(project) not in command or not any("train_" in arg or "run_" in arg for arg in args):
            continue
        output = ""
        for index, arg in enumerate(args):
            if arg == "--output" and index + 1 < len(args):
                output = args[index + 1]
                break
            if arg.startswith("--output="):
                output = arg.split("=", 1)[1]
                break
        run_dir = Path(output) if output and Path(output).is_absolute() else cwd / output if output else None
        try:
            status_lines = (entry / "status").read_text(encoding="utf-8", errors="replace").splitlines()
            state = next((line.split(":", 1)[1].strip() for line in status_lines if line.startswith("State:")), "RUNNING")
        except OSError:
            state = "RUNNING"
        found.append({"pid": int(entry.name), "state": state, "command": command,
                      "run": str(run_dir) if run_dir else ""})
    return found


def recent_progress(state_dir, current, run, run_dir=None):
    if not current:
        return ""
    if run_dir is not None:
        try:
            raw = (run_dir / "train.log").read_bytes()[-30000:].decode(errors="replace")
            lines = [line.strip() for line in raw.replace("\r", "\n").splitlines() if line.strip()]
            live = [line for line in lines if re.search(r"epoch\s*[=:]\s*\d+\s*/\s*\d+.*step\s*[=:]\s*\d+\s*/\s*\d+", line, re.I)]
            if live:
                return live[-1]
            finished = [line for line in lines if re.search(r"epoch\s*=\s*\d+\s+done", line, re.I)]
            if finished:
                return finished[-1]
        except OSError:
            pass
    candidates = [state_dir / f"{current}.log", project / "queue-logs" / f"{current}.log"]
    for path in candidates:
        try:
            raw = path.read_bytes()[-12000:].decode(errors="replace")
        except OSError:
            continue
        lines = [re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", line).strip()
                 for line in raw.replace("\r", "\n").splitlines()]
        progress = [line for line in lines if "/" in line and "|" in line]
        if progress:
            return progress[-1][-260:]
    if run.get("total_epochs"):
        return f"{run.get('rows', 0)}/{run['total_epochs']} 轮"
    return current


def process_status(current):
    found = []
    try:
        processes = Path("/proc").iterdir()
        for entry in processes:
            if not entry.name.isdigit():
                continue
            try:
                command = (entry / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")
            except OSError:
                continue
            if str(project) in command and ("train_" in command or "run_" in command):
                found.append(entry.name)
    except OSError:
        pass
    return "RUNNING:" + ",".join(found[:8]) if found else "STOPPED"


def gpu_status():
    try:
        return subprocess.check_output(
            ["nvidia-smi", "--query-gpu=name,memory.used,memory.total,utilization.gpu,temperature.gpu,power.draw",
             "--format=csv,noheader,nounits"], text=True, stderr=subprocess.STDOUT, timeout=5,
        ).strip()
    except (OSError, subprocess.SubprocessError) as exc:
        return f"GPU unavailable: {exc}"


def system_status():
    cpu_percent = None
    try:
        values = [int(value) for value in Path("/proc/stat").read_text().splitlines()[0].split()[1:]]
        idle = values[3] + (values[4] if len(values) > 4 else 0)
        total = sum(values)
        key = hashlib.sha256(str(project.resolve()).encode()).hexdigest()[:16]
        sample_path = Path("/tmp") / f"gyu-det-system-{key}.json"
        try:
            previous = json.loads(sample_path.read_text(encoding="utf-8"))
            total_delta = total - int(previous.get("total", total))
            idle_delta = idle - int(previous.get("idle", idle))
            if total_delta > 0:
                cpu_percent = round(max(0.0, min(100.0, (total_delta - idle_delta) * 100.0 / total_delta)), 1)
        except (OSError, ValueError, TypeError):
            pass
        temporary = sample_path.with_suffix(f".{os.getpid()}.tmp")
        temporary.write_text(json.dumps({"total": total, "idle": idle}), encoding="utf-8")
        os.replace(temporary, sample_path)
    except (OSError, ValueError, IndexError):
        pass

    memory = {}
    try:
        values = {}
        for line in Path("/proc/meminfo").read_text(encoding="utf-8").splitlines():
            key, _, remainder = line.partition(":")
            fields = remainder.split()
            if fields:
                values[key] = int(fields[0])
        total_mib = values.get("MemTotal", 0) / 1024
        available_mib = values.get("MemAvailable", values.get("MemFree", 0)) / 1024
        if total_mib > 0:
            used_mib = max(0.0, total_mib - available_mib)
            memory = {"used_mib": round(used_mib, 1), "total_mib": round(total_mib, 1),
                      "percent": round(used_mib * 100 / total_mib, 1)}
    except (OSError, ValueError):
        pass

    return {"cpu_percent": cpu_percent, "memory": memory}


def main():
    state, state_dir = find_state()
    queue, normalized = queue_and_state(state)
    current = normalized.get("current")
    processes = discover_processes()
    run_dir = find_run(current)
    if run_dir is None:
        for process in processes:
            candidate = Path(process.get("run", ""))
            if process.get("run") and candidate.is_dir():
                run_dir = candidate
                break
    if not current and run_dir is not None:
        current = run_dir.name
        normalized["current"] = current
    run = summarize_run(run_dir)
    progress = recent_progress(state_dir, current, run, run_dir)
    epoch_progress = {}
    match = re.search(r"epoch\s*[=:]\s*(\d+)\s*/\s*(\d+).*?step\s*[=:]\s*(\d+)\s*/\s*(\d+)", progress, re.I)
    if match:
        epoch_progress = {"epoch": int(match.group(1)), "total_epochs": int(match.group(2)),
                          "step": int(match.group(3)), "total_steps": int(match.group(4))}
    process_text = process_status(current)
    if processes:
        process_text = "RUNNING:" + ",".join(str(item["pid"]) for item in processes)
    print(json.dumps({"state": normalized, "queue": queue, "process": process_text,
                      "processes": processes, "gpu": gpu_status(), "system": system_status(), "run": run,
                      "epoch_progress": epoch_progress, "progress": progress}, ensure_ascii=False))


if __name__ == "__main__":
    main()
