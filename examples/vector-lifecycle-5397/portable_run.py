#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 justinblethrow-cloud
# SPDX-License-Identifier: Apache-2.0
"""Portable Linux/macOS runner for a small, disposable vector lifecycle fixture."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time
import uuid

PIN = "313f23e234468eb109da727d5bd88a446a009201"
HERE = Path(__file__).resolve().parent
GIB = 1024 ** 3


def sha(path):
    with Path(path).open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def save(path, value):
    with path.open("x") as f:
        json.dump(value, f, indent=2, sort_keys=True)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())


def guard(out):
    if shutil.disk_usage(out).free < 4 * GIB:
        raise RuntimeError("less than4GiB free disk")
    if sum(p.stat().st_size for p in (out / "database").rglob("*") if p.is_file()) > 4 * GIB:
        raise RuntimeError("disposable database exceeded4GiB")
    if Path("/proc/meminfo").exists():
        values = dict(line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines())
        if int(values["MemAvailable"].split()[0]) * 1024 < 4 * GIB:
            raise RuntimeError("less than4GiB available RAM")


def run_stage(java, cp, out, mode, n, c, rounds, nonce, deadline):
    stage = out / mode
    stage.mkdir()
    command = [java, "-Xms256m", "-Xmx2g", "-XX:ActiveProcessorCount=2",
               "--add-modules=jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED", "-cp", cp,
               "VectorLifecycleProbe", mode, str(out / "database"), str(stage), str(n), str(c), str(rounds), nonce]
    save(stage / "COMMAND.json", command)
    started = time.monotonic()
    forced = False
    last_guard = 0
    with (stage / "native.log").open("x") as log:
        child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        try:
            while child.poll() is None:
                elapsed = time.monotonic() - started
                if elapsed > deadline:
                    raise RuntimeError(f"{mode} exceeded its deadline")
                if elapsed - last_guard >= 1:
                    guard(out)
                    last_guard = elapsed
                ready = stage / "CRASH_READY.json"
                if mode == "crash_writer" and ready.exists() and "CRASH_READY " in (stage / "native.log").read_text():
                    marker = json.loads(ready.read_text())
                    if (marker["pid"] != child.pid or marker["nonce"] != nonce
                        or marker["databasePath"] != str(out / "database")
                        or marker["committedRounds"] != rounds + 1 or marker["expectedRecords"] != n + c * (rounds + 1)
                        or marker["walFlush"] != "YES_FULL" or marker["groupCommit"] is not False
                        or marker["identityManifestSha256"] != sha(out / f"IDENTITIES_{rounds + 1}.json")):
                        raise RuntimeError("crash marker does not identify this owned child/committed prefix")
                    save(stage / "PARENT_CRASH.json", {"marker": marker, "marker_sha256": sha(ready), "pid": child.pid})
                    # Popen owns this unreaped process. No name-based lookup or external process is signaled.
                    child.kill()
                    forced = True
                    break
                time.sleep(0.1)
            rc = child.wait(timeout=30)
            if rc != (-signal.SIGKILL if forced else 0) or (mode == "crash_writer" and not forced):
                raise RuntimeError(f"{mode} failed with exit{rc}; see{stage / 'native.log'}")
            if not forced:
                report = json.loads((stage / "REPORT.json").read_text())
                if report["status"] != "PASS" or report["nonce"] != nonce or report["phases"][-1]["phase"] != "closed":
                    raise RuntimeError("native close/report contract failed")
            receipt = {"mode": mode, "seconds": time.monotonic() - started, "exit_code": rc,
                       "native_log_sha256": sha(stage / "native.log"), "owned_child_reaped": True}
            save(stage / "LIFETIME.json", receipt)
            return receipt
        finally:
            if child.poll() is None:
                child.kill()
                child.wait(timeout=30)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--dependencies", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scale", choices=("smoke", "main"), default="smoke")
    parser.add_argument("--java", default=shutil.which("java"))
    args = parser.parse_args()
    source, out = args.source.resolve(), args.output.resolve()
    out.mkdir()  # Refuse all pre-existing output/database roots.
    started = time.monotonic()
    try:
        revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
        if revision != PIN:
            raise RuntimeError(f"expected source revision{PIN}; found{revision}")
        if subprocess.check_output(["git", "-C", str(source), "status", "--porcelain", "--untracked-files=no"], text=True).strip():
            raise RuntimeError("tracked source changes are not a pinned baseline")
        engine = source / "engine/target/arcadedb-engine-26.11.1-SNAPSHOT.jar"
        cp = str(engine) + os.pathsep + args.dependencies.read_text().strip()
        jar_hashes = {str(Path(name).resolve()): sha(name) for name in cp.split(os.pathsep)}
        java = str(Path(args.java).resolve())
        guard(out)
        classes = out / "classes"
        classes.mkdir()
        shutil.copy2(HERE / "VectorLifecycleProbe.java", out / "VectorLifecycleProbe.java")
        command = [java, "-XX:ActiveProcessorCount=2", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-proc:none",
                   "-cp", cp, "-d", str(classes), str(out / "VectorLifecycleProbe.java")]
        compiled = subprocess.run(command, capture_output=True, text=True, timeout=60)
        (out / "compile.log").write_text(compiled.stdout + compiled.stderr)
        compiled.check_returncode()
        nonce = uuid.uuid4().hex
        n, c, rounds = (1024, 64, 2) if args.scale == "smoke" else (8192, 512, 4)
        save(out / "PLAN.json", {"source_revision": revision, "java": subprocess.run([java, "-version"],
            capture_output=True, text=True).stderr, "jars_sha256": jar_hashes, "helper_sha256": sha(out / "VectorLifecycleProbe.java"),
            "runner_sha256": sha(__file__), "class_sha256": {p.name: sha(p) for p in classes.glob("*.class")},
            "initial": n, "chunk": c, "rounds": rounds, "nonce": nonce, "scale": args.scale})
        stages = []
        for mode in ("seed", "reopen_clean", "crash_writer", "recover", "reopen_after_recovery"):
            remaining = 1800 - (time.monotonic() - started)
            if remaining <= 0:
                raise RuntimeError("overall30minute deadline exceeded")
            print("START", mode, flush=True)
            stages.append(run_stage(java, str(classes) + os.pathsep + cp, out, mode, n, c, rounds, nonce, min(900, remaining)))
            print("DONE", mode, f"{stages[-1]['seconds']:.3f}s", flush=True)
        if jar_hashes != {str(Path(name).resolve()): sha(name) for name in cp.split(os.pathsep)}:
            raise RuntimeError("runtime JARs changed")
        save(out / "RESULT.json", {"status": "PASS", "seconds": time.monotonic() - started, "stages": stages})
        print("PASS", out, flush=True)
    except BaseException as error:
        save(out / "FAILURE.json", {"error": repr(error)})
        raise


if __name__ == "__main__":
    main()
