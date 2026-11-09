#!/usr/bin/env python3
"""Create the five project schemas and users in a locally installed MySQL 8 server."""

import argparse
import getpass
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
PASSWORDS = {
    "item": "ITEM_DB_PASSWORD",
    "claim": "CLAIM_DB_PASSWORD",
    "verification": "VERIFICATION_DB_PASSWORD",
    "handover": "HANDOVER_DB_PASSWORD",
    "workflow": "WORKFLOW_DB_PASSWORD",
}


def read_env():
    path = ROOT / ".env"
    if not path.exists():
        raise RuntimeError("缺少 .env；先运行 python3 scripts/init_env.py")
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    for key in PASSWORDS.values():
        value = values.get(key, "")
        if not re.fullmatch(r"[A-Za-z0-9_-]+", value):
            raise RuntimeError(f"{key} 必须是非空的字母、数字、下划线或连字符；可重新生成 .env")
    return values


def mysql_args(mysql, host, port, user):
    return [mysql, "--protocol=tcp", "--host", host, "--port", str(port),
            "--user", user, "--default-character-set=utf8mb4", "--batch"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", help="本机 MySQL 地址，默认 127.0.0.1")
    parser.add_argument("--port", type=int, default=3306, help="本机 MySQL 端口，默认 3306")
    parser.add_argument("--admin-user", default="root", help="有建库、建用户和授权权限的 MySQL 用户")
    args = parser.parse_args()
    mysql = shutil.which("mysql")
    if not mysql:
        raise RuntimeError("找不到 mysql 命令；请先安装 MySQL 8 和命令行客户端")
    values = read_env()
    admin_password = os.environ.get("LF_MYSQL_ADMIN_PASSWORD")
    if admin_password is None:
        admin_password = getpass.getpass(f"本机 MySQL 管理员 {args.admin_user} 的密码：")
    admin_env = os.environ.copy()
    admin_env["MYSQL_PWD"] = admin_password
    sql = []
    for name, key in PASSWORDS.items():
        sql.append(f"CREATE DATABASE IF NOT EXISTS {name}_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
        sql.append(f"CREATE USER IF NOT EXISTS '{name}_user'@'%' IDENTIFIED BY '{values[key]}';")
        sql.append(f"GRANT ALL PRIVILEGES ON {name}_db.* TO '{name}_user'@'%';")
    sql.append("FLUSH PRIVILEGES;")
    result = subprocess.run(mysql_args(mysql, args.host, args.port, args.admin_user),
                            input="\n".join(sql) + "\n", text=True, capture_output=True, env=admin_env)
    if result.returncode:
        raise RuntimeError("MySQL 初始化失败：" + result.stderr.strip())
    for name, key in PASSWORDS.items():
        user_env = os.environ.copy()
        user_env["MYSQL_PWD"] = values[key]
        check = subprocess.run(mysql_args(mysql, args.host, args.port, f"{name}_user") +
                               ["--database", f"{name}_db", "--execute", "SELECT 1"],
                               capture_output=True, text=True, env=user_env)
        if check.returncode:
            raise RuntimeError(f"{name}_user 无法登录；若该账号已存在，请检查它的密码是否与 .env 一致")
    print("五个本地 MySQL schema 与服务账号已就绪")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError) as error:
        print(error, file=sys.stderr)
        raise SystemExit(1)
