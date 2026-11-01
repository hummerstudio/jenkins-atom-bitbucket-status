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
import re
from urllib.parse import urlsplit
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


def expand(given, text):
    """The atom expands ${NAME} from the build parameters; recompute it here rather than trust the atom."""
    def value(match):
        return str(given.get(match.group(1), ""))
    return re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_.-]*)\}", value, text or "")


def check_pull_request(args, lines, data, given, state, server):
    """Judge the pull-request half against the server's own state, not against the atom's report."""
    action = (given.get("prAction") or "none").strip().lower()
    comment = expand(given, given.get("comment") or "").strip()
    pr_calls = [line for line in lines if line[1].startswith("/rest/api/1.0/")]
    if action == "none" and not comment:
        if pr_calls:
            raise Fail(f"no pull-request work was asked for, but {len(pr_calls)} PR call(s) were made")
        return
    if not args.pr_url or not args.pr_store:
        raise Fail("the run asked for pull-request work but the check got no --pr-url/--pr-store")

    pr_path = urlsplit(args.pr_url).path.rstrip("/")
    wrote = [(line[0], line[1]) for line in pr_calls if line[0] in ("POST", "DELETE")]
    expected_write = None
    if action == "approve" and state == "SUCCESSFUL":
        expected_write = ("POST", pr_path + "/approve")
    elif action == "approve" and state == "FAILED":
        expected_write = ("DELETE", pr_path + "/approve")
    elif action == "decline" and state == "FAILED":
        expected_write = ("POST", pr_path + "/decline")
    if action != "none" and state not in ("SUCCESSFUL", "FAILED") and not comment and pr_calls:
        raise Fail(f"{state} is neither green nor red: the pull request must be left alone, saw {pr_calls}")
    if expected_write and expected_write not in wrote:
        raise Fail(f"expected {expected_write[0]} {expected_write[1]}, saw {wrote}")
    if comment and ("POST", pr_path + "/comments") not in wrote:
        raise Fail(f"the comment was never posted, saw {wrote}")

    pr = load(args.pr_store, {}) or {}
    if action == "approve" and state == "SUCCESSFUL" and not pr.get("approved"):
        raise Fail("the server does not hold an approval after the run")
    if action == "approve" and state == "FAILED" and pr.get("approved"):
        raise Fail("the server still holds the approval after a red build")
    if action == "decline" and state == "FAILED" and pr.get("state") != "DECLINED":
        raise Fail(f"the pull request is {pr.get('state')}, not DECLINED")
    if comment:
        texts = [entry.get("text") for entry in pr.get("comments") or []]
        if comment not in texts:
            raise Fail(f"the server's comments {texts!r} do not contain the text the atom was given")

    # what the atom reported has to agree with the state the server actually holds
    if action != "none" and state in ("SUCCESSFUL", "FAILED"):
        wanted_action = {"approve": ("approve", "approved"), "decline": ("decline", "declined")}[action]
        if action == "approve" and state == "FAILED":
            wanted_action = ("withdraw-approve", "withdrawn")
        if (data.get("prAction"), data.get("prVerified")) != wanted_action:
            raise Fail(f"output.json says prAction={data.get('prAction')} prVerified={data.get('prVerified')},"
                       f" expected {wanted_action}")
    if comment and not data.get("commentId"):
        raise Fail("the atom reported no comment id after posting a comment")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", required=True)
    parser.add_argument("--store", required=True)
    parser.add_argument("--log", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--expect", required=True, help="success or error:<code>")
    parser.add_argument("--state", default="")
    parser.add_argument("--no-request", action="store_true")
    parser.add_argument("--pr-store", default="", help="pr-<port>.json written by the fake Bitbucket")
    parser.add_argument("--pr-url", default="", help="the pull request REST URL the atom should have used")
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
    commit_path = f"/rest/build-status/1.0/commits/{args.sha}"
    posts = [line for line in lines if line[0] == "POST" and line[1] == commit_path]
    if len(posts) != 1:
        raise Fail(f"expected exactly one POST to {commit_path}, saw {len(posts)}")
    commit_gets = [line for line in lines if line[0] == "GET" and line[1] == commit_path]
    if len(commit_gets) != 1:
        raise Fail(f"expected one read-back GET of the commit, saw {len(commit_gets)}")

    stored = load(args.store, {}) or {}
    entries = stored.get(args.sha) or []
    if not entries:
        raise Fail("the server stored nothing for this commit")
    last = entries[-1]
    if last != expected:
        raise Fail(f"stored payload {json.dumps(last, ensure_ascii=False, sort_keys=True)}"
                   f" != expected {json.dumps(expected, ensure_ascii=False, sort_keys=True)}")
    check_pull_request(args, lines, data, given, state, server)
    print(f"ok: {state} key={expected['key']} url={target}")


if __name__ == "__main__":
    try:
        main()
    except Fail as failure:
        print(f"check failed: {failure}", file=sys.stderr)
        sys.exit(1)
