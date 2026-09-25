"""Run a project snapshot script and add host CPU/memory measurements."""

import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import runpy
import sys


script_path = Path(sys.argv[1])
if not script_path.is_file():
    sys.stderr.write(f"python: can't open file '{script_path}': [Errno 2] No such file or directory\n")
    sys.exit(2)
os.chdir(script_path.parent)

captured = io.StringIO()
with contextlib.redirect_stdout(captured):
    runpy.run_path(str(script_path), run_name="__main__")
lines = [line.strip() for line in captured.getvalue().splitlines() if line.strip()]
if not lines:
    raise RuntimeError("snapshot script produced no JSON on stdout")
snapshot = json.loads(lines[-1])
if not isinstance(snapshot, dict):
    raise ValueError("snapshot output must be a JSON object")

system = snapshot.get("system")
if not isinstance(system, dict):
    system = {}
    snapshot["system"] = system

try:
    cpu_values = [int(value) for value in Path("/proc/stat").read_text().splitlines()[0].split()[1:]]
    idle = cpu_values[3] + (cpu_values[4] if len(cpu_values) > 4 else 0)
    total = sum(cpu_values)
    key = hashlib.sha256(str(script_path.parent.resolve()).encode()).hexdigest()[:16]
    sample_path = Path("/tmp") / f"gyu-det-system-{key}.json"
    try:
        previous = json.loads(sample_path.read_text(encoding="utf-8"))
        total_delta = total - int(previous.get("total", total))
        idle_delta = idle - int(previous.get("idle", idle))
        if total_delta > 0:
            system["cpu_percent"] = round(max(0.0, min(100.0,
                (total_delta - idle_delta) * 100.0 / total_delta)), 1)
    except (OSError, ValueError, TypeError):
        pass
    temporary = sample_path.with_suffix(f".{os.getpid()}.tmp")
    temporary.write_text(json.dumps({"total": total, "idle": idle}), encoding="utf-8")
    os.replace(temporary, sample_path)
except (OSError, ValueError, IndexError):
    pass

try:
    values = {}
    for line in Path("/proc/meminfo").read_text(encoding="utf-8").splitlines():
        name, _, remainder = line.partition(":")
        fields = remainder.split()
        if fields:
            values[name] = int(fields[0])
    total_mib = values.get("MemTotal", 0) / 1024
    available_mib = values.get("MemAvailable", values.get("MemFree", 0)) / 1024
    if total_mib > 0:
        used_mib = max(0.0, total_mib - available_mib)
        system["memory"] = {"used_mib": round(used_mib, 1), "total_mib": round(total_mib, 1),
                            "percent": round(used_mib * 100 / total_mib, 1)}
except (OSError, ValueError):
    pass

print(json.dumps(snapshot, ensure_ascii=False, separators=(",", ":")))
