#!/usr/bin/env python3
"""Create one idempotent sample item in the local demonstration system."""

import datetime as dt
import json
import os
import time
import urllib.error
import urllib.request


BASE = os.environ.get("LF_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
TITLE = "演示：黑色耳机盒"


def request(method, path, data=None, token=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    body = None
    if data is not None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    with urllib.request.urlopen(
        urllib.request.Request(BASE + path, data=body, headers=headers, method=method),
        timeout=20,
    ) as response:
        return json.load(response)


def main():
    for attempt in range(30):
        try:
            items = request("GET", "/api/items")
            break
        except urllib.error.HTTPError as error:
            if error.code != 503 or attempt == 29:
                raise
        except urllib.error.URLError:
            if attempt == 29:
                raise
        time.sleep(2)
    existing = next((item for item in items if item["title"] == TITLE), None)
    if existing:
        print(f"Sample item already exists: {existing['id']}")
        return
    token = request("POST", "/api/auth/login", {"username": "admin1", "password": "admin123"})["token"]
    item = request("POST", "/api/items", {
        "title": TITLE,
        "category": "ELECTRONICS",
        "publicDescription": "黑色无线耳机充电盒，在教学楼 A 座一层拾得。",
        "foundLocation": "教学楼 A 座一层",
        "foundAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "valuable": False,
        "hiddenClues": {"盒内侧刻字是什么？": "LF-2048"},
    }, token)
    print(f"Sample item created: {item['id']}")


if __name__ == "__main__":
    main()
