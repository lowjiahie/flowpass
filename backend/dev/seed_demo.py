#!/usr/bin/env python3
"""Seed a local demo into tenant `default` (LOCAL DEVELOPMENT ONLY).

What it does, through the real HTTP API (so every validation and audit record applies):
  1. creates + publishes the `purchase_request` template (the BPMN shipped with the service) with a form schema,
  2. sets an SLA policy (24h, reminder 6h before, escalate to the manager),
  3. creates three amount-banded approver rules,
and, directly in PostgreSQL (the org directory has no API yet), the reporting line and the finance role.

Requirements: the stack from backend/docker-compose.yml, the service running with the `keycloak` profile.
Usage:  python dev/seed_demo.py            (idempotent: re-running adds nothing that already exists)

Demo people (password = username): demo (requester, admin rights), alice (demo's manager),
bob (alice's manager), carol (finance).
"""
import json
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

KEYCLOAK = "http://localhost:8080/realms/saas-platform/protocol/openid-connect/token"
API = "http://localhost:9000"
TENANT = "default"
KEY = "purchase_request"
ROOT = Path(__file__).resolve().parent.parent

FORM_SCHEMA = {
    "type": "object",
    "required": ["amount", "currency"],
    "properties": {
        "amount": {"type": "number", "minimum": 0, "title": "Amount"},
        "currency": {"type": "string", "minLength": 3, "maxLength": 3, "title": "Currency", "default": "MYR"},
        "reason": {"type": "string", "maxLength": 500, "title": "Reason"},
    },
}

RULES = [
    # priority (lower wins), condition, approvers by round
    (5, {"amountMin": 10000},
     [("INITIATOR_MANAGER", "1", 1), ("INITIATOR_MANAGER", "2", 2), ("ROLE", "finance", 3)]),
    (10, {"amountMin": 1000},
     [("INITIATOR_MANAGER", "1", 1), ("ROLE", "finance", 2)]),
    (100, {}, [("INITIATOR_MANAGER", "1", 1)]),
]

DIRECTORY = [  # (user, manager)
    ("user-demo", "user-alice"),
    ("user-alice", "user-bob"),
    ("user-bob", None),
    ("user-carol", None),
]


def token():
    body = urllib.parse.urlencode({"client_id": "approval-seed", "username": "demo", "password": "demo",
                                   "grant_type": "password"}).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=body)) as r:
        return json.load(r)["access_token"]


def call(method, path, tok, payload=None, ok=(200, 201, 204)):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(API + path, data=data, method=method,
                                 headers={"Authorization": "Bearer " + tok, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            body = json.loads(raw)
        except ValueError:
            body = raw.decode(errors="replace")
        return e.code, body


def must(status_body, what):
    status, body = status_body
    if status >= 300:
        sys.exit(f"{what} failed: HTTP {status} {body}")
    return body


def sql(statement):
    subprocess.run(["docker", "exec", "-i", "flowpass-postgres", "psql", "-U", "flowpass", "-d", "flowpass",
                    "-v", "ON_ERROR_STOP=1", "-q"], input=statement.encode(), check=True)


def main():
    tok = token()

    status, template = call("GET", f"/v1/admin/templates/{KEY}", tok)
    if status == 404:
        must(call("POST", "/v1/admin/templates", tok, {"templateKey": KEY}), "create template")
        bpmn = (ROOT / "approval-center/src/main/resources/processes/purchase_request.bpmn20.xml").read_text("utf-8")
        must(call("PUT", f"/v1/admin/templates/{KEY}/draft", tok, {"bpmnXml": bpmn}), "save draft")
        must(call("PUT", f"/v1/admin/templates/{KEY}/draft/form-schema", tok, {"formSchema": FORM_SCHEMA}),
             "save form schema")
        must(call("POST", f"/v1/admin/templates/{KEY}/publish", tok), "publish")
        print("template published")
    else:
        must((status, template), "read template")
        print("template exists, status", template["status"])

    status, _ = call("GET", f"/v1/admin/templates/{KEY}/sla", tok)
    if status == 404:
        must(call("PUT", f"/v1/admin/templates/{KEY}/sla", tok,
                  {"dueAfterHours": 24, "remindBeforeHours": 6, "onOverdue": "ESCALATE_TO_MANAGER"}), "set sla")
        print("sla set")

    existing = must(call("GET", f"/v1/admin/decision-rules?templateKey={KEY}", tok), "list rules")
    if not existing:
        for priority, cond, approvers in RULES:
            body = {"templateKey": KEY, "priority": priority, **cond,
                    "approvers": [{"type": t, "id": i, "round": r} for t, i, r in approvers]}
            must(call("POST", "/v1/admin/decision-rules", tok, body), f"rule {priority}")
        print("rules created:", len(RULES))
    else:
        print("rules exist:", len(existing))

    lines = [f"INSERT INTO directory_user (tenant_id, user_id, manager_id) VALUES "
             f"('{TENANT}', '{u}', {'NULL' if m is None else repr(m)}) "
             f"ON CONFLICT (tenant_id, user_id) DO UPDATE SET manager_id = EXCLUDED.manager_id;"
             for u, m in DIRECTORY]
    lines.append(f"INSERT INTO directory_role_member (tenant_id, role_name, user_id) VALUES "
                 f"('{TENANT}', 'finance', 'user-carol') ON CONFLICT DO NOTHING;")
    sql("\n".join(lines))
    print("directory seeded")
    print("done — log in as demo / demo (requester), alice, bob, carol (password = username)")


if __name__ == "__main__":
    main()
