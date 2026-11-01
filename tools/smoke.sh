#!/usr/bin/env bash
# One fake Bitbucket per scenario; each one re-checks the request itself and stores exactly what it will hand back.
# The checks are behavioural: a lying server must be caught, a skipped state must send nothing at all.
set -uo pipefail
cd "${BASH_SOURCE[0]%/*}/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

work="${TMPDIR:-/tmp}/bitbucket-smoke-$$"
mkdir -p "$work"
sha="a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"
server_pid=""
port=""
store=""
logfile=""

export JOB_NAME=parcel BUILD_NUMBER=42 BUILD_URL="http://jenkins/job/parcel/42/display/redirect"

stop() {
  [ -n "$server_pid" ] && kill "$server_pid" 2>/dev/null
  wait "$server_pid" 2>/dev/null
  server_pid=""
}
trap stop EXIT

# serve <mode> <auth-mode> <credential> [expect-sha] [expect-pr] — starts a server on a port the kernel picks
serve() {
  local out
  port=""
  out="$work/server-$(date +%s%N).out"
  : >"$out"
  python3 tools/fake_bitbucket.py --port 0 --dir "$work" \
    --mode "$1" --auth-mode "$2" --credential "$3" --expect-sha "${4:-}" \
    --expect-pr "${5:-PROJ/parcel/1}" >"$out" 2>&1 &
  server_pid=$!
  for _ in {1..100}; do
    port="$(sed -n 's/^listening \([0-9]*\)$/\1/p' "$out")"
    [ -n "$port" ] && break
    sleep 0.1
  done
  if [ -z "$port" ]; then
    cat "$out" >&2
    fail "the fake Bitbucket did not start"
  fi
  store="$work/store-$port.json"
  logfile="$work/log-$port.tsv"
  prstore="$work/pr-$port.json"
  base="http://127.0.0.1:$port"
  prurl="$base/rest/api/1.0/projects/PROJ/repos/parcel/pull-requests/1"
  [ -f "$logfile" ] || fail "the fake Bitbucket wrote no log for port $port"
}

# new_run <name> — a fresh directory for one run
new_run() { dir="$work/$1"; mkdir -p "$dir"; }

# check <args...> — judge the current run against the fake server's own logs and store
check() { python3 tools/check.py --run "$dir" --store "$store" --log "$logfile" \
  --pr-store "${prstore:-}" --pr-url "${prurl:-}" "$@" || fail "check failed for $dir"; }

# contains <file> <needle>
contains() { grep -q "$2" "$1" || fail "$1 does not mention $2"; }

## 1. success, basic auth, default payload
export BUILD_STATUS=SUCCESS
serve normal basic "ci-bot:s3cr3t"
new_run basic
printf '{"serverUrl": "http://127.0.0.1:%s", "sha": "%s", "username": "ci-bot"}' "$port" "$sha" >"$dir/input.json"
printf '{"password": "s3cr3t"}' >"$dir/.secrets.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "basic run failed"
check --sha "$sha" --expect success
contains "$logfile" "Basic "
grep -q "s3cr3t" "$dir/log.txt" && fail "the password must not reach the log"
grep -q "s3cr3t" "$dir/output.json" && fail "the password must not reach output.json"
stop

## 2. bearer token + UNSTABLE counted as success
export BUILD_STATUS=UNSTABLE
serve normal bearer "bb-token-123"
new_run bearer
printf '{"serverUrl": "http://127.0.0.1:%s/", "sha": "%s", "considerUnstableAsSuccess": "true"}' "$port" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "bearer run failed"
check --sha "$sha" --expect success
contains "$logfile" "Bearer bb-token-123"
grep -q "bb-token-123" "$dir/log.txt" && fail "the token must not reach the log"
stop

## 3. UNSTABLE by default is FAILED (the plugin's default considerUnstableAsSuccess=false)
serve normal bearer "bb-token-123"
new_run unstable
printf '{"serverUrl": "http://127.0.0.1:%s", "sha": "%s"}' "$port" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "unstable run failed"
check --sha "$sha" --expect success
contains "$store" '"state": "FAILED"'
stop

## 4. an explicit INPROGRESS carries the plugin's "building on Jenkins" description
export BUILD_STATUS=SUCCESS
serve normal bearer "bb-token-123"
new_run inprogress
printf '{"serverUrl": "http://127.0.0.1:%s", "sha": "%s", "state": "in_progress", "jenkinsRootUrl": "https://ci.example.com"}' "$port" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "inprogress run failed"
check --sha "$sha" --expect success --state INPROGRESS
contains "$store" 'building on Jenkins @ https://ci.example.com'
stop

## 5. ABORTED is not reported at all (what the plugin prints and returns on)
export BUILD_STATUS=ABORTED
serve normal bearer "bb-token-123"
new_run aborted
printf '{"serverUrl": "http://127.0.0.1:%s", "sha": "%s"}' "$port" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "aborted run failed"
check --sha "$sha" --expect success --no-request
stop

## 6. ... unless the job asks for it, then it is FAILED
serve normal bearer "bb-token-123"
new_run aborted-notify
printf '{"serverUrl": "http://127.0.0.1:%s", "sha": "%s", "notifyAborted": "true"}' "$port" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "aborted-notify run failed"
check --sha "$sha" --expect success --state FAILED
stop

## 7. 401 is reported with the server's own words
export BUILD_STATUS=SUCCESS
serve normal basic "ci-bot:s3cr3t"
new_run unauthorized
printf '{"serverUrl": "http://127.0.0.1:%s", "sha": "%s", "username": "ci-bot"}' "$port" "$sha" >"$dir/input.json"
printf '{"password": "wrong"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a 401 must fail the atom"
contains "$dir/output.json" "40005"
contains "$dir/output.json" "401"
contains "$dir/output.json" "Authentication failed"
stop

## 8. 403 is its own failure
serve forbidden bearer "bb-token-123"
new_run forbidden
printf '{"serverUrl": "http://127.0.0.1:%s", "sha": "%s"}' "$port" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a 403 must fail the atom"
contains "$dir/output.json" "403"
stop

## 9. a 204 that stored nothing must be caught by the read-back
serve lying-drop bearer "bb-token-123"
new_run lying-drop
printf '{"serverUrl": "http://127.0.0.1:%s", "sha": "%s"}' "$port" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a 204 with no stored entry must fail the atom"
contains "$dir/output.json" "40006"
contains "$dir/output.json" "lists no status with key"
stop

## 10. a 204 that stored a different state must be caught too
serve lying-state bearer "bb-token-123"
new_run lying-state
printf '{"serverUrl": "http://127.0.0.1:%s", "sha": "%s"}' "$port" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a state that came back different must fail the atom"
contains "$dir/output.json" "reports state FAILED"
stop

## 11. verifyAfterWrite=false trusts the 204 — the same server, the opposite outcome
serve lying-drop bearer "bb-token-123"
new_run no-verify
printf '{"serverUrl": "http://127.0.0.1:%s", "sha": "%s", "verifyAfterWrite": "false"}' "$port" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "without a read-back the 204 is enough"
contains "$dir/output.json" "not-checked"
stop

## 12. an unknown commit is 404, not a silent success
serve normal bearer "bb-token-123" "ffffffffffffffffffffffffffffffffffffffff"
new_run unknown-commit
printf '{"serverUrl": "http://127.0.0.1:%s", "sha": "%s"}' "$port" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "an unknown commit must fail the atom"
contains "$dir/output.json" "404"
stop

## 13. a read-back endpoint that refuses GET is not mistaken for success
serve get-405 bearer "bb-token-123"
new_run get-405
printf '{"serverUrl": "http://127.0.0.1:%s", "sha": "%s"}' "$port" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a broken read-back must fail the atom"
contains "$dir/output.json" "40006"
stop

## 14. user errors: no sha, no credential, no server, non-http server
new_run no-sha
printf '{"serverUrl": "http://127.0.0.1:65000"}' >"$dir/input.json"
run "$dir" GIT_COMMIT= >/dev/null 2>&1 && fail "a missing sha must fail"
contains "$dir/output.json" "40001"

new_run no-cred
printf '{"serverUrl": "http://127.0.0.1:65000", "sha": "%s"}' "$sha" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "a missing credential must fail"
contains "$dir/output.json" "40002"

new_run no-server
printf '{"sha": "%s"}' "$sha" >"$dir/input.json"
printf '{"token": "x"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a missing serverUrl must fail"
contains "$dir/output.json" "40001"

new_run ftp-server
printf '{"serverUrl": "ftp://example.com", "sha": "%s"}' "$sha" >"$dir/input.json"
printf '{"token": "x"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a non-http serverUrl must fail"
contains "$dir/output.json" "40001"

## 15. a long key is cut at 255 chars with an ellipsis, exactly like StringUtils.abbreviate
export BUILD_STATUS=SUCCESS
export JOB_NAME="job-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
serve normal bearer "bb-token-123"
new_run long-key
printf '{"serverUrl": "http://127.0.0.1:%s", "sha": "%s"}' "$port" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "long-key run failed"
check --sha "$sha" --expect success
python3 -c "import json,sys;e=json.load(open(sys.argv[1]))[sys.argv[2]][-1]['key'];print(len(e));sys.exit(0 if len(e)==255 and e.endswith('...') else 1)" "$store" "$sha" >"$dir/keylen.txt" || fail "the key was not abbreviated to 255 chars"
stop
export JOB_NAME=parcel

## 16. includeBuildNumberInKey puts the number in the key
serve normal bearer "bb-token-123"
new_run build-number-key
printf '{"serverUrl": "http://127.0.0.1:%s", "sha": "%s", "includeBuildNumberInKey": "true"}' "$port" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "build-number key run failed"
contains "$store" '"key": "parcel-42"'
stop


## 17. green build approves the pull request; a second run hits "already approved" and the read-back still agrees
serve normal bearer "bb-token-123"
new_run pr-approve
printf '{"serverUrl": "%s", "sha": "%s", "prAction": "approve", "pullRequestUrl": "%s/projects/PROJ/repos/parcel/pull-requests/1/overview"}' "$base" "$sha" "$base" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "approve run failed"
check --sha "$sha" --expect success
contains "$dir/output.json" '"prVerified"'
python3 -c "import json,sys;d=json.load(open(sys.argv[1]))['data'];sys.exit(0 if d['prAction']['value']=='approve' and d['prVerified']['value']=='approved' else 1)" "$dir/output.json" || fail "the approve was not reported as approved"
: >"$logfile"   # the server keeps one log; this run's requests are what the check judges
new_run pr-approve-again
printf '{"serverUrl": "%s", "sha": "%s", "prAction": "approve", "projectKey": "PROJ", "repoSlug": "parcel", "pullRequestId": "1"}' "$base" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "the second approve must survive a 409"
grep -q "409 already approved" "$logfile" || fail "the second approve never hit the 409 path"
check --sha "$sha" --expect success
python3 -c "import json,sys;sys.exit(0 if len(json.load(open(sys.argv[1]))['comments'])==0 else 1)" "$prstore" || fail "no comment was expected yet"
stop

## 18. a red build withdraws the approval it made earlier
serve normal bearer "bb-token-123"
new_run pr-withdraw-1
printf '{"serverUrl": "%s", "sha": "%s", "prAction": "approve", "projectKey": "PROJ", "repoSlug": "parcel", "pullRequestId": "1"}' "$base" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "approve-before-withdraw failed"
check --sha "$sha" --expect success
: >"$logfile"
export BUILD_STATUS=FAILURE
new_run pr-withdraw
printf '{"serverUrl": "%s", "sha": "%s", "prAction": "approve", "projectKey": "PROJ", "repoSlug": "parcel", "pullRequestId": "1"}' "$base" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "withdraw run failed"
check --sha "$sha" --expect success --state FAILED
contains "$logfile" "DELETE	/rest/api/1.0/projects/PROJ/repos/parcel/pull-requests/1/approve"
python3 -c "import json,sys;d=json.load(open(sys.argv[1]))['data'];sys.exit(0 if d['prAction']['value']=='withdraw-approve' and d['prVerified']['value']=='withdrawn' else 1)" "$dir/output.json" || fail "the withdrawal was not reported"
stop

## 19. a red build can decline the pull request instead
serve normal bearer "bb-token-123"
new_run pr-decline
printf '{"serverUrl": "%s", "sha": "%s", "prAction": "decline", "projectKey": "PROJ", "repoSlug": "parcel", "pullRequestId": "1"}' "$base" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "decline run failed"
check --sha "$sha" --expect success --state FAILED
contains "$prstore" '"state": "DECLINED"'
stop

export BUILD_STATUS=SUCCESS

## 20. a green build can comment, with ${...} expanded, and the comment is read back by id
serve normal bearer "bb-token-123"
new_run pr-comment
printf '{"serverUrl": "%s", "sha": "%s", "comment": "build ${BUILD_NUMBER} is green on ${JOB_NAME}", "projectKey": "PROJ", "repoSlug": "parcel", "pullRequestId": "1"}' "$base" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "comment run failed"
check --sha "$sha" --expect success
contains "$prstore" 'build 42 is green on parcel'
python3 -c "import json,sys;d=json.load(open(sys.argv[1]))['data'];sys.exit(0 if d['commentId']['value']=='1' and d['prAction']['value']=='none' else 1)" "$dir/output.json" || fail "the comment id was not reported"
stop

## 21. a 201 whose text the server did not keep must fail the atom (200/201 is not evidence)
serve pr-lie-comment bearer "bb-token-123"
new_run pr-lie-comment
printf '{"serverUrl": "%s", "sha": "%s", "comment": "this text will be truncated by the server", "projectKey": "PROJ", "repoSlug": "parcel", "pullRequestId": "1"}' "$base" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "a comment that was not kept must fail the atom"
contains "$dir/output.json" "40009"
contains "$dir/output.json" "lists none with that text"
stop

## 22. a 200 approve that did not record the approval must fail too
serve pr-lie-approve bearer "bb-token-123"
new_run pr-lie-approve
printf '{"serverUrl": "%s", "sha": "%s", "prAction": "approve", "projectKey": "PROJ", "repoSlug": "parcel", "pullRequestId": "1"}' "$base" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "an approval that was not recorded must fail the atom"
contains "$dir/output.json" "reports approved=false"
stop

## 23. an unknown pull request is 404, not a quiet success
serve normal bearer "bb-token-123" "" "PROJ/parcel/9"
new_run pr-unknown
printf '{"serverUrl": "%s", "sha": "%s", "prAction": "approve", "projectKey": "PROJ", "repoSlug": "parcel", "pullRequestId": "1"}' "$base" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "an unknown pull request must fail the atom"
contains "$dir/output.json" "404"
stop

## 24. a pull-request action without a pull request is a user error, and nothing is sent
serve normal bearer "bb-token-123"
new_run pr-no-target
printf '{"serverUrl": "%s", "sha": "%s", "prAction": "approve"}' "$base" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 && fail "prAction without a target must fail"
contains "$dir/output.json" "40002"
[ -s "$logfile" ] && fail "nothing should have been sent for a user error"
stop

## 25. an in-progress build touches neither the PR nor the comment
serve normal bearer "bb-token-123"
new_run pr-inprogress
printf '{"serverUrl": "%s", "sha": "%s", "state": "INPROGRESS", "prAction": "approve", "comment": "", "projectKey": "PROJ", "repoSlug": "parcel", "pullRequestId": "1"}' "$base" "$sha" >"$dir/input.json"
printf '{"token": "bb-token-123"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "in-progress run failed"
check --sha "$sha" --expect success --state INPROGRESS
grep -q "/rest/api/1.0/" "$logfile" && fail "an in-progress build must not call the PR API"
stop

echo "smoke ok: 25 scenarios (state mapping, both auth modes, 401/403/404, two lying servers, broken read-back, skipped states, abbreviation, PR approve/withdraw/decline/comment and their lying servers)"
