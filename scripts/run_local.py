#!/usr/bin/env python3
"""Build and run all services directly on macOS/Linux; Ctrl+C stops them."""

import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

from init_local_mysql import read_env


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "web"
LOGS = ROOT / ".local-run"
SERVICES = (
    ("item-service", 8081, "item", "ITEM_DB_PASSWORD"),
    ("claim-service", 8082, "claim", "CLAIM_DB_PASSWORD"),
    ("verification-service", 8083, "verification", "VERIFICATION_DB_PASSWORD"),
    ("handover-service", 8084, "handover", "HANDOVER_DB_PASSWORD"),
)
PORTS = (8761, 8081, 8082, 8083, 8084, 8080, 5173)


def executable(name):
    path = shutil.which(name)
    if not path:
        raise RuntimeError(f"找不到 {name}；请先安装运行依赖并加入 PATH")
    return path


def java_executable():
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        path = Path(java_home) / "bin" / "java"
        if path.is_file():
            return str(path)
    return executable("java")


def check_ports():
    busy = []
    for port in PORTS:
        with socket.socket() as probe:
            probe.settimeout(0.3)
            if probe.connect_ex(("127.0.0.1", port)) == 0:
                busy.append(port)
    if busy:
        raise RuntimeError("这些端口已被占用：" + ", ".join(map(str, busy)) +
                           "。先停止 Docker Compose 或其他本地实例，再运行此脚本")


def wait_for(url, process, name, seconds=90):
    deadline = time.monotonic() + seconds
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"{name} 提前退出；查看 {LOGS / (name + '.log')}")
        try:
            with opener.open(url, timeout=2) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.5)
    raise RuntimeError(f"等待 {name} 启动超时；查看 {LOGS / (name + '.log')}")


def jar_for(module):
    jars = list((ROOT / module / "target").glob("*.jar"))
    if len(jars) != 1:
        raise RuntimeError(f"{module} 应有一个可执行 JAR；请先构建项目")
    return jars[0]


def catalog_ready():
    base = "http://127.0.0.1:8080"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    login = urllib.request.Request(base + "/api/auth/login", method="POST",
        data=json.dumps({"username": "admin1", "password": "admin123"}).encode(),
        headers={"Content-Type": "application/json"})
    with opener.open(login, timeout=5) as response:
        token = json.load(response)["token"]
    request = urllib.request.Request(base + "/api/admin/services",
        headers={"Authorization": f"Bearer {token}"})
    with opener.open(request, timeout=10) as response:
        rows = json.load(response)
    healthy = {row["serviceName"] for row in rows
               if row.get("registered") and row.get("enabled") and row.get("healthy")}
    return healthy == {name for name, *_ in SERVICES}


def wait_for_catalog(process, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"workflow-service 提前退出；查看 {LOGS / 'workflow-service.log'}")
        try:
            if catalog_ready():
                return
        except (OSError, KeyError, ValueError):
            pass
        time.sleep(2)
    raise RuntimeError("Eureka 尚未发现全部四个业务服务；查看 .local-run 下的日志")


def stop_all(processes, log_files):
    for process in reversed(processes):
        if process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
    for process in reversed(processes):
        if process.poll() is None:
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
    for file in log_files:
        file.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db-host", default="127.0.0.1")
    parser.add_argument("--db-port", type=int, default=3306)
    parser.add_argument("--skip-build", action="store_true", help="复用已构建的 JAR 与 web/node_modules")
    args = parser.parse_args()
    if os.name != "posix":
        raise RuntimeError("此脚本支持 macOS/Linux；Windows 请按 README 手动启动")
    values = read_env()
    if not values.get("LF_INTERNAL_TOKEN") or not values.get("LF_CLUE_KEY"):
        raise RuntimeError(".env 缺少 LF_INTERNAL_TOKEN 或 LF_CLUE_KEY")
    check_ports()
    with socket.create_connection((args.db_host, args.db_port), timeout=3):
        pass
    java = java_executable()
    if not args.skip_build:
        print("正在构建六个 Java 模块和 Vue 前端…", flush=True)
        subprocess.run([executable("mvn"), "-B", "-DskipTests", "package"], cwd=ROOT, check=True)
        subprocess.run([executable("npm"), "ci"], cwd=WEB, check=True)
    vite = WEB / "node_modules" / ".bin" / "vite"
    if not vite.is_file():
        raise RuntimeError("找不到 Vite；请运行 npm ci 或取消 --skip-build")
    LOGS.mkdir(exist_ok=True)
    processes = []
    log_files = []
    base_env = os.environ.copy()
    base_env.update({"DB_HOST": args.db_host, "DB_PORT": str(args.db_port),
                     "EUREKA_URL": "http://127.0.0.1:8761/eureka/",
                     "EUREKA_INSTANCE_PREFER_IP_ADDRESS": "false",
                     "EUREKA_INSTANCE_HOSTNAME": "localhost",
                     "SERVER_ADDRESS": "127.0.0.1",
                     "LF_INTERNAL_TOKEN": values["LF_INTERNAL_TOKEN"]})

    def start(name, command, env):
        log = open(LOGS / f"{name}.log", "w", encoding="utf-8")
        log_files.append(log)
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        processes.append(process)
        print(f"启动 {name}；日志：{LOGS / (name + '.log')}", flush=True)
        return process

    try:
        discovery = start("discovery-server", [java, "-jar", str(jar_for("discovery-server"))], base_env)
        wait_for("http://127.0.0.1:8761/actuator/health", discovery, "discovery-server")
        for name, port, db, key in SERVICES:
            env = dict(base_env, PORT=str(port), DB_NAME=f"{db}_db", DB_USER=f"{db}_user",
                       DB_PASSWORD=values[key])
            if name == "verification-service":
                env["LF_CLUE_KEY"] = values["LF_CLUE_KEY"]
            start(name, [java, "-jar", str(jar_for(name))], env)
        for name, port, *_ in SERVICES:
            process = processes[["discovery-server", *(row[0] for row in SERVICES)].index(name)]
            wait_for(f"http://127.0.0.1:{port}/actuator/health", process, name)
        workflow_env = dict(base_env, DB_NAME="workflow_db", DB_USER="workflow_user",
                            DB_PASSWORD=values["WORKFLOW_DB_PASSWORD"])
        workflow = start("workflow-service", [java, "-jar", str(jar_for("workflow-service"))], workflow_env)
        wait_for("http://127.0.0.1:8080/actuator/health", workflow, "workflow-service", seconds=120)
        wait_for_catalog(workflow)
        web_env = dict(base_env, API_PROXY_TARGET="http://127.0.0.1:8080")
        web = start("web", [str(vite), "--host", "127.0.0.1"], web_env)
        wait_for("http://127.0.0.1:5173/", web, "web", seconds=30)
        print("系统就绪：http://127.0.0.1:5173  （Ctrl+C 停止所有进程）", flush=True)
        while True:
            for process in processes:
                if process.poll() is not None:
                    raise RuntimeError("一个服务已退出；查看 .local-run 下的日志")
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n正在停止服务…", flush=True)
    finally:
        stop_all(processes, log_files)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        raise SystemExit(1)
