#!/usr/bin/env python3
"""Second implementation of the contract, in Python, used to judge one atom run.

It does not import the atom and does not trust output.json: it recomputes the request URL and the five payload fields
from the run's own input.json (the stashNotifier rules, re-derived from its bytecode), then compares that against

  * the request the fake Bitbucket actually received (log.tsv),
  * the entry the fake Bitbucket actually stored (store.json),
  * what the atom reported (output.json).

    python3 tools/check.py --run DIR --store store.json --log log.tsv --sha SHA --expect success|error:<code> \
        [--state S] [--no-request]

--state overrides the state derived from BUILD_STATUS (for runs that pin it); --no-request asserts that nothing was
sent at all.
"""
import argparse
import json
import os
import sys

STATES = {"SUCCESSFUL", "FAILED", "INPROGRESS"}
LIMITS = {"key": 255, "name": 255, "description": 255, "url": 450}


class Fail(Exception):
    pass


def abbreviate(value, limit):
    return value if len(value) <= limit else value[: limit - 3] + "..."


def expected_state(raw, unstable_as_success):
    """stashNotifier's Result -> StashBuildState switch (javap -c StashNotifier.perform)."""
    name = (raw or "SUCCESS").strip().upper()
    if name in ("", "SUCCESS", "SUCCESSFUL", "OK", "PASSED"):
        return "SUCCESSFUL"
    if name in ("UNSTABLE", "WARNING"):
        return "SUCCESSFUL" if unstable_as_success else "FAILED"
    if name in ("BUILDING", "RUNNING", "PENDING", "INPROGRESS", "IN_PROGRESS"):
        return "INPROGRESS"
    if name in ("ABORTED", "CANCELED", "CANCELLED", "NOT_BUILT", "NOTBUILT"):
        return None
    return "FAILED"


def load(path, default=None):
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        return default
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def truthy(value):
    return str(value).strip().lower() == "true"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True)
    parser.add_argument("--store", required=True)
    parser.add_argument("--log", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--expect", required=True, help="success or error:<code>")
    parser.add_argument("--state", default="")
    parser.add_argument("--no-request", action="store_true")
    args = parser.parse_args()

    given = load(os.path.join(args.run, "input.json"), {})
    output = load(os.path.join(args.run, "output.json"), {})
    status = output.get("status")
    message = output.get("message") or ""
    data = {k: (v.get("value") if isinstance(v, dict) else v) for k, v in (output.get("data") or {}).items()}

    if args.expect.startswith("error:"):
        code = int(args.expect.split(":", 1)[1])
        if status != "error":
            raise Fail(f"expected error {code}, got {status}: {message}")
        got = (output.get("errorCode"))
        if str(got) != str(code):
            raise Fail(f"expected error code {code}, got {got}: {message}")
        return print(f"ok: error {code} - {message[:90]}")

    if status != "success":
        raise Fail(f"expected success, got {status}: {message}")

    unstable = truthy(given.get("considerUnstableAsSuccess", "false"))
    notify_aborted = truthy(given.get("notifyAborted", "false"))
    state = args.state.strip().upper() or expected_state(given.get("BUILD_STATUS"), unstable) or ""
    if state == "" and notify_aborted:
        # the atom turns a silenced state into FAILED when the job asked for it
        state = "FAILED"
    if args.no_request:
        if state:
            raise Fail(f"nothing should have been sent, but state {state} was expected to be reported")
        if data.get("state") != "SKIPPED":
            raise Fail(f"expected SKIPPED, got {data.get('state')}")
        if os.path.exists(args.log) and os.path.getsize(args.log):
            raise Fail("a request was sent for a state that must be skipped")
        return print("ok: skipped, no request")

    server = (given.get("serverUrl") or "").rstrip("/")
    target = f"{server}/rest/build-status/1.0/commits/{args.sha}"

    key = given.get("key") or given.get("JOB_NAME") or ""
    if not given.get("key") and truthy(given.get("includeBuildNumberInKey", "false")):
        key = f"{key}-{given.get('BUILD_NUMBER', '')}"
    name = given.get("name") or f"{given.get('JOB_NAME', '')} #{given.get('BUILD_NUMBER', '')}"
    root = given.get("jenkinsRootUrl") or given.get("buildUrl") or given.get("BUILD_URL") or ""
    description = given.get("description") or (
        f"building on Jenkins @ {root}" if state == "INPROGRESS" else f"built by Jenkins @ {root}")
    url = given.get("buildUrl") or given.get("BUILD_URL") or ""
    expected = {
        "state": state,
        "key": abbreviate(key, LIMITS["key"]),
        "name": abbreviate(name, LIMITS["name"]),
        "description": abbreviate(description, LIMITS["description"]),
        "url": abbreviate(url, LIMITS["url"]),
    }

    if data.get("state") != state or data.get("apiUrl") != target:
        raise Fail(f"output.json says state={data.get('state')} apiUrl={data.get('apiUrl')}, expected {state} {target}")
    if data.get("key") != expected["key"]:
        raise Fail(f"output.json key={data.get('key')!r}, expected {expected['key']!r}")
    if data.get("verified") != state:
        raise Fail(f"the atom did not read {state} back: verified={data.get('verified')!r}")

    lines = []
    if os.path.exists(args.log):
        with open(args.log, encoding="utf-8") as handle:
            lines = [line.rstrip("\n").split("\t") for line in handle if line.strip()]
    posts = [line for line in lines if line[0] == "POST"]
    if len(posts) != 1:
        raise Fail(f"expected exactly one POST, saw {len(posts)}")
    if posts[0][1] != f"/rest/build-status/1.0/commits/{args.sha}":
        raise Fail(f"wrong path {posts[0][1]}")
    gets = [line for line in lines if line[0] == "GET"]
    if len(gets) != 1:
        raise Fail(f"expected one read-back GET, saw {len(gets)}")

    stored = load(args.store, {}) or {}
    entries = stored.get(args.sha) or []
    if not entries:
        raise Fail("the server stored nothing for this commit")
    last = entries[-1]
    if last != expected:
        raise Fail(f"stored payload {json.dumps(last, ensure_ascii=False, sort_keys=True)}"
                   f" != expected {json.dumps(expected, ensure_ascii=False, sort_keys=True)}")
    print(f"ok: {state} key={expected['key']} url={target}")


if __name__ == "__main__":
    try:
        main()
    except Fail as failure:
        print(f"check failed: {failure}", file=sys.stderr)
        sys.exit(1)
