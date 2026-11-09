#!/usr/bin/env python3
"""End-to-end API check for the local Docker Compose demonstration.

This script creates disposable demonstration records. It never prints bearer
tokens, hidden clue answers, or pickup codes.
"""

import datetime as dt
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request
import uuid


BASE = os.environ.get("LF_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
RUN_ID = uuid.uuid4().hex[:8]
CLUE = f"本次测试隐藏编号 {RUN_ID}"
ANSWER = uuid.uuid4().hex
TOKENS = {}


class ApiError(Exception):
    def __init__(self, method, path, status, body):
        super().__init__(f"{method} {path} -> HTTP {status}: {body}")
        self.status = status


def call(method, path, data=None, actor=None, expected=200):
    headers = {"Accept": "application/json"}
    if actor:
        headers["Authorization"] = f"Bearer {TOKENS[actor]}"
    payload = None
    if data is not None:
        payload = json.dumps(data, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(BASE + path, data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status = response.status
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        status = error.code
        body = error.read().decode("utf-8")
    if isinstance(expected, int):
        expected = {expected}
    if status not in expected:
        raise ApiError(method, path, status, body)
    if body:
        try:
            return json.loads(body)
        except ValueError:
            return body
    return None


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def login():
    for actor, password in (
        ("student", "student123"),
        ("admin1", "admin123"),
        ("admin2", "admin123"),
        ("staff", "staff123"),
    ):
        TOKENS[actor] = call("POST", "/api/auth/login", {"username": actor, "password": password})["token"]
    check(call("GET", "/api/auth/me", actor="student")["role"] == "STUDENT", "student login role")
    print("PASS auth: four demo roles")


def create_item(label, valuable=False, age_days=1):
    found = dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=age_days)
    row = call("POST", "/api/items", {
        "title": f"{label} {RUN_ID}",
        "category": "电子设备",
        "publicDescription": "黑色收纳盒，教学楼拾得",
        "foundLocation": "教学楼一层",
        "foundAt": found.isoformat().replace("+00:00", "Z"),
        "valuable": valuable,
        "hiddenClues": {CLUE: ANSWER},
    }, "admin1")
    item_id = row["id"]
    public = call("GET", f"/api/items/{item_id}")
    check(public["verificationQuestions"] == [CLUE], "question should be shown without answer")
    check(ANSWER not in json.dumps(public, ensure_ascii=False), "hidden answer leaked in public item")
    return item_id


def create_claim(item_id, actor="student"):
    return call("POST", "/api/claims", {
        "itemId": item_id,
        "description": "收纳盒内侧有我留下的编号",
        "answers": {CLUE: ANSWER},
    }, actor)["claimId"]


def task(item_id, key, actor="admin1", timeout=15):
    until = time.time() + timeout
    while time.time() < until:
        rows = call("GET", "/api/tasks", actor=actor)
        matches = [r for r in rows if r["taskDefinitionKey"] == key and r["variables"].get("itemId") == item_id]
        if matches:
            return matches[0]
        time.sleep(0.5)
    raise AssertionError(f"task {key} missing for item {item_id}")


def status(item_id, expected):
    row = call("GET", f"/api/items/{item_id}")
    check(row["status"] == expected, f"item {item_id} is {row['status']}, expected {expected}")


def code_for(claim_id):
    response = call("GET", f"/api/pickup/{claim_id}/code", actor="student")
    check(response["status"] == "ACTIVE", "pickup code should be active")
    return response["code"]


def redeem(item_id, claim_id):
    call("GET", f"/api/pickup/{claim_id}/code", actor="staff", expected=403)
    code = code_for(claim_id)
    call("POST", f"/api/pickup/{claim_id}/redeem", {"code": "0000000000"}, "staff", expected=400)
    status(item_id, "RESERVED")
    response = call("POST", f"/api/pickup/{claim_id}/redeem", {"code": code}, "staff")
    check(response["status"] == "HANDED_OVER", "handover response")
    status(item_id, "HANDED_OVER")
    check(call("GET", f"/api/claims/{claim_id}", actor="student")["status"] == "COMPLETED", "claim completion")
    call("POST", f"/api/pickup/{claim_id}/redeem", {"code": code}, "staff", expected={404, 409})


def normal_claim():
    item_id = create_item("普通认领")
    claim_id = create_claim(item_id)
    evidence = call("GET", f"/api/admin/claims/{claim_id}/evidence", actor="admin1")
    check(evidence["evidence"]["preliminaryPassed"], "correct clue should pass preliminary check")
    check(evidence["clues"]["clues"][CLUE] == ANSWER, "admin evidence access")
    first = task(item_id, "initialReview")
    call("POST", f"/api/tasks/{first['id']}/complete", {"decision": "APPROVE", "reason": "线索匹配"}, "admin1")
    status(item_id, "RESERVED")
    task(item_id, "confirmPickup", "staff")
    redeem(item_id, claim_id)
    print("PASS normal claim: evidence, approval, one-time code, handover")


def dispute_claim():
    item_id = create_item("争议认领")
    first_id = create_claim(item_id, "student")
    second_id = create_claim(item_id, "admin1")
    call("POST", "/api/disputes", {
        "itemId": item_id, "claimIds": [first_id, second_id], "reason": "两名认领人均提供线索"
    }, "admin2")
    status(item_id, "DISPUTED")
    review = task(item_id, "disputeReview")
    call("POST", f"/api/tasks/{review['id']}/complete", {
        "decision": "APPROVE", "winningClaimId": first_id, "reason": "人工核实原始凭证"
    }, "admin2")
    status(item_id, "RESERVED")
    check(call("GET", f"/api/claims/{second_id}", actor="admin2")["status"] == "REJECTED", "loser claim rejected")
    task(item_id, "disputePickup", "staff")
    redeem(item_id, first_id)
    print("PASS dispute: freeze, single winner, code, handover")


def automatic_dispute():
    item_id = create_item("自动争议认领")
    first_id = create_claim(item_id, "student")
    first_review = task(item_id, "initialReview")
    call("POST", f"/api/tasks/{first_review['id']}/complete", {
        "decision": "APPROVE", "reason": "第一份申请线索匹配"
    }, "admin1")
    status(item_id, "RESERVED")
    second = call("POST", "/api/claims", {
        "itemId": item_id,
        "description": "另一位认领人也能提供隐藏编号",
        "answers": {CLUE: ANSWER},
    }, "admin2")
    check(second["status"] == "DISPUTED", "second valid claim should trigger automatic dispute")
    status(item_id, "DISPUTED")
    check(call("GET", f"/api/claims/{first_id}", actor="admin1")["status"] == "DISPUTED", "first claim should be frozen")
    check(call("GET", f"/api/claims/{second['claimId']}", actor="admin1")["status"] == "DISPUTED", "second claim should be frozen")
    task(item_id, "disputeReview")
    print("PASS automatic dispute: second valid claim freezes existing handover")


def disposal():
    item_id = create_item("无人认领处置", age_days=31)
    call("POST", "/api/disposals", {"itemId": item_id, "reason": "超过 30 天保管期"}, "admin1")
    review = task(item_id, "disposalReview")
    call("POST", f"/api/tasks/{review['id']}/complete", {
        "decision": "APPROVE", "destination": "校保卫处", "reason": "同意移交"
    }, "admin1", expected=409)
    call("POST", f"/api/tasks/{review['id']}/complete", {
        "decision": "APPROVE", "destination": "校保卫处", "reason": "同意移交"
    }, "admin2")
    status(item_id, "DISPOSED")
    print("PASS disposal: 30-day rule, separate reviewer, transfer")


def dynamic_v2():
    before = call("GET", "/api/admin/process-definitions", actor="admin1")
    prior = max(r["version"] for r in before if r["key"] == "claim_standard")
    deployed = call("POST", "/api/admin/process-definitions/demo-v2", {}, "admin1")
    check(deployed["key"] == "claim_standard" and deployed["version"] == prior + 1, "v2 version increment")
    item_id = create_item("贵重物品双人复核", valuable=True)
    claim_id = create_claim(item_id)
    first = task(item_id, "initialReview")
    call("POST", f"/api/tasks/{first['id']}/complete", {"decision": "APPROVE", "reason": "一级审核通过"}, "admin1")
    status(item_id, "PUBLISHED")
    second = task(item_id, "secondReview")
    call("POST", f"/api/tasks/{second['id']}/complete", {"decision": "APPROVE", "reason": "复核"}, "admin1", expected=409)
    call("POST", f"/api/tasks/{second['id']}/complete", {"decision": "APPROVE", "reason": "复核通过"}, "admin2")
    status(item_id, "RESERVED")
    redeem(item_id, claim_id)
    print(f"PASS dynamic BPMN: claim_standard v{deployed['version']}, separate reviewer")


def upload_bpmn():
    before = call("GET", "/api/admin/process-definitions", actor="admin1")
    prior = max(r["version"] for r in before if r["key"] == "claim_standard")
    source = Path(__file__).resolve().parents[1] / "workflow-service/src/main/resources/demo-v2/claim-standard-v2.bpmn20.xml"
    boundary = "----lost-found-smoke-" + uuid.uuid4().hex
    body = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"claim-standard-v2.bpmn20.xml\"\r\n"
        "Content-Type: application/xml\r\n\r\n"
    ).encode() + source.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(
        BASE + "/api/admin/process-definitions/upload", data=body, method="POST",
        headers={"Authorization": f"Bearer {TOKENS['admin1']}",
                 "Content-Type": f"multipart/form-data; boundary={boundary}", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            deployed = json.load(response)
    except urllib.error.HTTPError as error:
        raise ApiError("POST", "/api/admin/process-definitions/upload", error.code, error.read().decode()) from error
    check(deployed["key"] == "claim_standard" and deployed["version"] == prior + 1, "BPMN upload version increment")
    print(f"PASS BPMN upload: claim_standard v{deployed['version']}")


def service_catalog():
    rows = call("GET", "/api/admin/services", actor="admin1")
    names = {r["serviceName"] for r in rows if r["enabled"] and r["healthy"] and r["registered"]}
    check(names == {"item-service", "claim-service", "verification-service", "handover-service"}, f"catalog unhealthy: {rows}")
    definitions = call("GET", "/api/admin/process-definitions", actor="admin1")
    check({r["key"] for r in definitions} >= {"claim_standard", "claim_dispute", "unclaimed_disposal"}, "three BPMN definitions missing")
    print("PASS catalog: four registered healthy services, three deployed processes")


def negative_controls():
    call("GET", "/api/admin/services", actor="student", expected=403)
    call("PATCH", "/api/admin/services/claim-service", {"enabled": False}, "admin1")
    try:
        call("GET", "/api/claims?itemId=no-such-item", actor="student", expected=503)
    finally:
        call("POST", "/api/admin/services/register", {"serviceName": "claim-service"}, "admin1")
    check(next(r for r in call("GET", "/api/admin/services", actor="admin1")
               if r["serviceName"] == "claim-service")["enabled"], "service re-registration")
    item_id = create_item("保管期未满")
    call("POST", "/api/disposals", {"itemId": item_id, "reason": "未满 30 天"}, "admin1", expected=409)
    status(item_id, "PUBLISHED")
    item_id = create_item("错误线索驳回")
    claim_id = call("POST", "/api/claims", {
        "itemId": item_id, "description": "测试错误答案", "answers": {CLUE: "错误答案"},
    }, "student")["claimId"]
    evidence = call("GET", f"/api/admin/claims/{claim_id}/evidence", actor="admin1")
    check(not evidence["evidence"]["preliminaryPassed"], "wrong clue should fail preliminary check")
    review = task(item_id, "initialReview")
    call("POST", f"/api/tasks/{review['id']}/complete", {"decision": "REJECT", "reason": "线索不符"}, "admin1")
    status(item_id, "PUBLISHED")
    check(call("GET", f"/api/claims/{claim_id}", actor="student")["status"] == "REJECTED", "wrong claim rejected")
    print("PASS negative controls: role guard, service pause/re-register, 30-day guard, bad clue rejection")


def main():
    print(f"Testing {BASE}; run ID {RUN_ID}")
    login()
    failures = []
    for scenario in (service_catalog, negative_controls, normal_claim, dispute_claim,
                     automatic_dispute, disposal, dynamic_v2, upload_bpmn):
        try:
            scenario()
        except Exception as error:
            failures.append((scenario.__name__, error))
            print(f"FAIL {scenario.__name__}: {error}", file=sys.stderr)
    if failures:
        print(f"{len(failures)} scenario(s) failed", file=sys.stderr)
        return 1
    print("All end-to-end scenarios passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
