package atom;

import static atom.FakeBitbucket.list;
import static atom.FakeBitbucket.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bitbucket-status atom against a fake Bitbucket that re-checks every request itself.
 *
 * <p>These are the 25 scenarios {@code tools/smoke.sh} ran, with {@code tools/check.py}'s judgement ported to Java: the
 * payload is recomputed from the run's own input.json and compared with what the server stored, the read-back is
 * checked against the atom's report, and the pull-request half is judged by the state the server keeps — not by the
 * status code the atom was handed.
 */
class BitbucketStatusIT {

    private static final String SHA = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678";
    private static final Path MODULE = Path.of(System.getProperty("atom.module", System.getProperty("user.dir")));

    private Path work;

    @BeforeEach
    void setUp() throws Exception {
        work = Files.createTempDirectory(Path.of(System.getProperty("atom.scratch")), "bitbucket-");
    }

    // ------------------------------------------------------------------ 1-6: the state mapping

    @Test
    @DisplayName("basic auth, SUCCESS: the payload is stored, Basic is used, and the password stays out of the log")
    void basicAuthSuccess() throws Exception {
        try (FakeBitbucket app = server("normal", "basic", "ci-bot:s3cr3t")) {
            Map<String, String> params = base(app);
            params.put("username", "ci-bot");

            AtomResult run = run(params, Map.of("password", "s3cr3t"));
            assertPayload(run, app, SHA);

            assertTrue(app.logText().contains("Basic "), "basic auth was not used:\n" + app.logText());
            assertFalse(run.console().contains("s3cr3t"), "the password must not reach the log");
            assertFalse(run.file("output.json").contains("s3cr3t"), "the password must not reach output.json");
        }
    }

    @Test
    @DisplayName("bearer token, UNSTABLE counted as success: the token is sent and never printed")
    void bearerTokenUnstableAsSuccess() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123")) {
            Map<String, String> params = base(app);
            params.put("considerUnstableAsSuccess", "true");
            params.put("BUILD_STATUS", "UNSTABLE");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));
            assertPayload(run, app, SHA);

            assertTrue(app.logText().contains("Bearer bb-token-123"), "the token was not sent as a bearer token");
            assertFalse(run.console().contains("bb-token-123"), "the token must not reach the log");
        }
    }

    @Test
    @DisplayName("UNSTABLE by default is FAILED, exactly like the plugin's considerUnstableAsSuccess=false")
    void unstableIsFailedByDefault() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123")) {
            Map<String, String> params = base(app);
            params.put("BUILD_STATUS", "UNSTABLE");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));
            assertPayload(run, app, SHA, "FAILED");

            assertTrue(Files.readString(app.store(), java.nio.charset.StandardCharsets.UTF_8).contains("\"state\": \"FAILED\""),
                    "the stored state is not FAILED");
        }
    }

    @Test
    @DisplayName("an explicit INPROGRESS carries the plugin's \"building on Jenkins\" description")
    void explicitInProgress() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123")) {
            Map<String, String> params = base(app);
            params.put("state", "in_progress");
            params.put("jenkinsRootUrl", "https://ci.example.com");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));
            assertPayload(run, app, SHA, "INPROGRESS");

            assertTrue(Files.readString(app.store(), java.nio.charset.StandardCharsets.UTF_8)
                    .contains("building on Jenkins @ https://ci.example.com"), "the description is not the plugin's");
        }
    }

    @Test
    @DisplayName("ABORTED is not reported at all: no request, and the atom says SKIPPED")
    void abortedIsSkipped() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123")) {
            Map<String, String> params = base(app);
            params.put("BUILD_STATUS", "ABORTED");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));
            assertSkipped(run, app);
        }
    }

    @Test
    @DisplayName("... unless the job asks for it (notifyAborted), then ABORTED is FAILED")
    void abortedNotifiedWhenAsked() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123")) {
            Map<String, String> params = base(app);
            params.put("BUILD_STATUS", "ABORTED");
            params.put("notifyAborted", "true");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));
            assertPayload(run, app, SHA, "FAILED");
        }
    }

    // ------------------------------------------------------------------ 7-13: what the server says

    @Test
    @DisplayName("a 401 is reported with the server's own words and a 40005")
    void unauthorizedIsReported() throws Exception {
        try (FakeBitbucket app = server("normal", "basic", "ci-bot:s3cr3t")) {
            Map<String, String> params = base(app);
            params.put("username", "ci-bot");

            AtomResult run = run(params, Map.of("password", "wrong"));

            run.assertRefused();
            assertContains(run, "40005");
            assertContains(run, "401");
            assertContains(run, "Authentication failed");
        }
    }

    @Test
    @DisplayName("a 403 is its own failure")
    void forbiddenIsReported() throws Exception {
        try (FakeBitbucket app = server("forbidden", "bearer", "bb-token-123")) {
            AtomResult run = run(base(app), Map.of("token", "bb-token-123"));

            run.assertRefused();
            assertContains(run, "403");
        }
    }

    @Test
    @DisplayName("a 204 that stored nothing is caught by the read-back, not trusted")
    void lyingDropIsCaught() throws Exception {
        try (FakeBitbucket app = server("lying-drop", "bearer", "bb-token-123")) {
            AtomResult run = run(base(app), Map.of("token", "bb-token-123"));

            run.assertRefused();
            assertContains(run, "40006");
            assertContains(run, "lists no status with key");
        }
    }

    @Test
    @DisplayName("a 204 that stored a different state is caught too")
    void lyingStateIsCaught() throws Exception {
        try (FakeBitbucket app = server("lying-state", "bearer", "bb-token-123")) {
            AtomResult run = run(base(app), Map.of("token", "bb-token-123"));

            run.assertRefused();
            assertContains(run, "reports state FAILED");
        }
    }

    @Test
    @DisplayName("verifyAfterWrite=false trusts the 204 — the same server, the opposite outcome")
    void verifyFalseTrustsTheWrite() throws Exception {
        try (FakeBitbucket app = server("lying-drop", "bearer", "bb-token-123")) {
            Map<String, String> params = base(app);
            params.put("verifyAfterWrite", "false");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));

            run.assertOk();
            assertContains(run, "not-checked");
        }
    }

    @Test
    @DisplayName("an unknown commit is a 404, not a silent success")
    void unknownCommitIs404() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123",
                "ffffffffffffffffffffffffffffffffffffffff")) {
            AtomResult run = run(base(app), Map.of("token", "bb-token-123"));

            run.assertRefused();
            assertContains(run, "404");
        }
    }

    @Test
    @DisplayName("a read-back endpoint that refuses GET is not mistaken for success")
    void brokenReadBackIsCaught() throws Exception {
        try (FakeBitbucket app = server("get-405", "bearer", "bb-token-123")) {
            AtomResult run = run(base(app), Map.of("token", "bb-token-123"));

            run.assertRefused();
            assertContains(run, "40006");
        }
    }

    // ------------------------------------------------------------------ 14-16: inputs

    @Test
    @DisplayName("user errors: no sha, no credential, no server, a non-http server")
    void userErrors() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123")) {
            Map<String, String> noSha = new LinkedHashMap<>();
            noSha.put("serverUrl", "http://127.0.0.1:65000");
            noSha.put("GIT_COMMIT", "");
            noSha.put("BUILD_STATUS", "SUCCESS");
            assertEquals("40001", run(noSha, Map.of()).field("errorCode"));

            Map<String, String> noCredential = base(app);
            assertEquals("40002", run(noCredential, Map.of()).field("errorCode"));

            Map<String, String> noServer = new LinkedHashMap<>();
            noServer.put("sha", SHA);
            noServer.put("BUILD_STATUS", "SUCCESS");
            assertEquals("40001", run(noServer, Map.of("token", "x")).field("errorCode"));

            Map<String, String> ftp = new LinkedHashMap<>();
            ftp.put("serverUrl", "ftp://example.com");
            ftp.put("sha", SHA);
            ftp.put("BUILD_STATUS", "SUCCESS");
            assertEquals("40001", run(ftp, Map.of("token", "x")).field("errorCode"));
        }
    }

    @Test
    @DisplayName("a long key is cut at 255 chars with an ellipsis, exactly like StringUtils.abbreviate")
    void longKeyIsAbbreviated() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123")) {
            Map<String, String> params = base(app);
            params.put("JOB_NAME", "job-" + "x".repeat(260));

            AtomResult run = run(params, Map.of("token", "bb-token-123"));
            assertPayload(run, app, SHA);

            String key = String.valueOf(lastStored(app, SHA).get("key"));
            assertEquals(255, key.length(), "the key was not abbreviated to 255 chars: " + key.length());
            assertTrue(key.endsWith("..."), "the abbreviation has no ellipsis");
        }
    }

    @Test
    @DisplayName("includeBuildNumberInKey puts the build number in the key")
    void buildNumberInKey() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123")) {
            Map<String, String> params = base(app);
            params.put("includeBuildNumberInKey", "true");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));
            assertPayload(run, app, SHA);

            assertEquals("parcel-42", lastStored(app, SHA).get("key"));
        }
    }

    // ------------------------------------------------------------------ 17-25: the pull request

    @Test
    @DisplayName("a green build approves the pull request; a second run survives the 409 and still verifies")
    void approveTwice() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123")) {
            Map<String, String> first = base(app);
            first.put("prAction", "approve");
            first.put("pullRequestUrl", app.pullRequestUrl() + "/overview");

            AtomResult approved = run(first, Map.of("token", "bb-token-123"));
            assertPayload(approved, app, SHA);
            assertEquals("approve", approved.field("prAction"));
            assertEquals("approved", approved.field("prVerified"));

            app.clearLog();

            Map<String, String> second = base(app);
            second.put("prAction", "approve");
            second.put("projectKey", "PROJ");
            second.put("repoSlug", "parcel");
            second.put("pullRequestId", "1");

            AtomResult again = run(second, Map.of("token", "bb-token-123"));
            assertPayload(again, app, SHA);

            assertTrue(app.logText().contains("409 already approved"),
                    "the second approve never hit the 409 path:\n" + app.logText());
            assertTrue(list(app.pullRequestDocument().get("comments")).isEmpty(), "no comment was expected yet");
        }
    }

    @Test
    @DisplayName("a red build withdraws the approval it made earlier")
    void withdrawApproval() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123")) {
            Map<String, String> approve = base(app);
            approve.put("prAction", "approve");
            approve.put("projectKey", "PROJ");
            approve.put("repoSlug", "parcel");
            approve.put("pullRequestId", "1");
            assertPayload(run(approve, Map.of("token", "bb-token-123")), app, SHA);

            app.clearLog();

            Map<String, String> red = base(app);
            red.put("BUILD_STATUS", "FAILURE");
            red.put("prAction", "approve");
            red.put("projectKey", "PROJ");
            red.put("repoSlug", "parcel");
            red.put("pullRequestId", "1");

            AtomResult run = run(red, Map.of("token", "bb-token-123"));
            assertPayload(run, app, SHA, "FAILED");

            assertTrue(app.logText().contains("DELETE\t/rest/api/1.0/projects/PROJ/repos/parcel/pull-requests/1/approve"),
                    "the approval was not withdrawn:\n" + app.logText());
            assertEquals("withdraw-approve", run.field("prAction"));
            assertEquals("withdrawn", run.field("prVerified"));
        }
    }

    @Test
    @DisplayName("a red build can decline the pull request instead")
    void declinePullRequest() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123")) {
            Map<String, String> params = base(app);
            params.put("BUILD_STATUS", "FAILURE");
            params.put("prAction", "decline");
            params.put("projectKey", "PROJ");
            params.put("repoSlug", "parcel");
            params.put("pullRequestId", "1");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));
            assertPayload(run, app, SHA, "FAILED");

            assertEquals("DECLINED", app.pullRequestDocument().get("state"));
        }
    }

    @Test
    @DisplayName("a green build can comment, with ${...} expanded, and the comment is read back by id")
    void commentOnPullRequest() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123")) {
            Map<String, String> params = base(app);
            params.put("comment", "build ${BUILD_NUMBER} is green on ${JOB_NAME}");
            params.put("projectKey", "PROJ");
            params.put("repoSlug", "parcel");
            params.put("pullRequestId", "1");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));
            assertPayload(run, app, SHA);

            assertTrue(Files.readString(app.pullRequest(), java.nio.charset.StandardCharsets.UTF_8)
                    .contains("build 42 is green on parcel"), "the comment was not posted expanded");
            assertEquals("1", run.field("commentId"));
            assertEquals("none", run.field("prAction"));
        }
    }

    @Test
    @DisplayName("a 201 whose text the server did not keep fails the atom")
    void lyingCommentIsCaught() throws Exception {
        try (FakeBitbucket app = server("pr-lie-comment", "bearer", "bb-token-123")) {
            Map<String, String> params = base(app);
            params.put("comment", "this text will be truncated by the server");
            params.put("projectKey", "PROJ");
            params.put("repoSlug", "parcel");
            params.put("pullRequestId", "1");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));

            run.assertRefused();
            assertContains(run, "40009");
            assertContains(run, "lists none with that text");
        }
    }

    @Test
    @DisplayName("a 200 approve that did not record the approval fails too")
    void lyingApproveIsCaught() throws Exception {
        try (FakeBitbucket app = server("pr-lie-approve", "bearer", "bb-token-123")) {
            Map<String, String> params = base(app);
            params.put("prAction", "approve");
            params.put("projectKey", "PROJ");
            params.put("repoSlug", "parcel");
            params.put("pullRequestId", "1");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));

            run.assertRefused();
            assertContains(run, "reports approved=false");
        }
    }

    @Test
    @DisplayName("an unknown pull request is a 404, not a quiet success")
    void unknownPullRequestIs404() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123", "", "PROJ/parcel/9")) {
            Map<String, String> params = base(app);
            params.put("prAction", "approve");
            params.put("projectKey", "PROJ");
            params.put("repoSlug", "parcel");
            params.put("pullRequestId", "1");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));

            run.assertRefused();
            assertContains(run, "404");
        }
    }

    @Test
    @DisplayName("a pull-request action without a pull request is a user error, and nothing is sent")
    void pullRequestActionNeedsATarget() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123")) {
            Map<String, String> params = base(app);
            params.put("prAction", "approve");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));

            run.assertRefused();
            assertContains(run, "40002");
            assertTrue(app.logText().isBlank(), "nothing should have been sent for a user error");
        }
    }

    @Test
    @DisplayName("an in-progress build touches neither the pull request nor the comment")
    void inProgressLeavesThePullRequestAlone() throws Exception {
        try (FakeBitbucket app = server("normal", "bearer", "bb-token-123")) {
            Map<String, String> params = base(app);
            params.put("state", "INPROGRESS");
            params.put("prAction", "approve");
            params.put("comment", "");
            params.put("projectKey", "PROJ");
            params.put("repoSlug", "parcel");
            params.put("pullRequestId", "1");

            AtomResult run = run(params, Map.of("token", "bb-token-123"));
            assertPayload(run, app, SHA, "INPROGRESS");

            assertFalse(app.logText().contains("/rest/api/1.0/"),
                    "an in-progress build must not call the PR API:\n" + app.logText());
        }
    }

    // ------------------------------------------------------------------ the judgement check.py made

    private void assertPayload(AtomResult run, FakeBitbucket app, String sha) throws Exception {
        assertPayload(run, app, sha, null);
    }

    private void assertPayload(AtomResult run, FakeBitbucket app, String sha, String stateOverride) throws Exception {
        run.assertOk();
        Map<String, Object> given = Json.parseObject(run.file("input.json"));
        String state = stateOverride != null ? stateOverride : expectedState(given);
        if (state.isEmpty()) {
            state = "FAILED";   // the atom turns a silenced state into FAILED when notifyAborted asked for it
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("state", state);
        String key = value(given, "key");
        if (key.isEmpty()) {
            key = value(given, "JOB_NAME");
            if (truthy(value(given, "includeBuildNumberInKey"))) {
                key = key + "-" + value(given, "BUILD_NUMBER");
            }
        }
        expected.put("key", abbreviate(key, 255));
        String name = value(given, "name");
        expected.put("name", abbreviate(name.isEmpty()
                ? value(given, "JOB_NAME") + " #" + value(given, "BUILD_NUMBER") : name, 255));
        String root = firstNonEmpty(value(given, "jenkinsRootUrl"), value(given, "buildUrl"), value(given, "BUILD_URL"));
        String description = value(given, "description");
        expected.put("description", abbreviate(description.isEmpty()
                ? ("INPROGRESS".equals(state) ? "building on Jenkins @ " : "built by Jenkins @ ") + root : description, 255));
        expected.put("url", abbreviate(firstNonEmpty(value(given, "buildUrl"), value(given, "BUILD_URL")), 450));

        String server = value(given, "serverUrl");
        String target = stripTrailingSlash(server) + "/rest/build-status/1.0/commits/" + sha;

        assertEquals(state, run.field("state"), "output.json says the wrong state");
        assertEquals(target, run.field("apiUrl"), "output.json says the wrong apiUrl");
        assertEquals(expected.get("key"), run.field("key"), "output.json key");
        assertEquals(state, run.field("verified"), "the atom did not read the state back");

        List<String[]> lines = app.lines();
        String commitPath = "/rest/build-status/1.0/commits/" + sha;
        long posts = lines.stream().filter(line -> "POST".equals(line[0]) && commitPath.equals(line[1])).count();
        long gets = lines.stream().filter(line -> "GET".equals(line[0]) && commitPath.equals(line[1])).count();
        assertEquals(1, posts, "expected exactly one POST to " + commitPath + ", saw " + posts + "\n" + app.logText());
        assertEquals(1, gets, "expected one read-back GET of the commit, saw " + gets + "\n" + app.logText());

        Map<String, Object> stored = lastStored(app, sha);
        for (Map.Entry<String, String> field : expected.entrySet()) {
            assertEquals(field.getValue(), stored.get(field.getKey()),
                    "stored " + field.getKey() + " is not the expected one");
        }

        assertPullRequest(run, app, given, lines, state);
    }

    /** check.py's check_pull_request: judge the pull-request half against the server's own state. */
    private static void assertPullRequest(AtomResult run, FakeBitbucket app, Map<String, Object> given,
            List<String[]> lines, String state) throws Exception {
        String action = value(given, "prAction").toLowerCase();
        String comment = expand(given, value(given, "comment")).trim();
        List<String[]> prCalls = lines.stream().filter(line -> line[1].startsWith("/rest/api/1.0/")).toList();
        if ("none".equals(action) && comment.isEmpty()) {
            assertTrue(prCalls.isEmpty(), "no pull-request work was asked for, but " + prCalls.size() + " PR call(s) were made");
            return;
        }

        String prPath = new java.net.URI(app.pullRequestUrl()).getPath().replaceAll("/$", "");
        List<String[]> wrote = prCalls.stream().filter(line -> "POST".equals(line[0]) || "DELETE".equals(line[0])).toList();
        String expectedWrite = null;
        if ("approve".equals(action) && "SUCCESSFUL".equals(state)) {
            expectedWrite = "POST " + prPath + "/approve";
        } else if ("approve".equals(action) && "FAILED".equals(state)) {
            expectedWrite = "DELETE " + prPath + "/approve";
        } else if ("decline".equals(action) && "FAILED".equals(state)) {
            expectedWrite = "POST " + prPath + "/decline";
        }
        if (!"none".equals(action) && !"SUCCESSFUL".equals(state) && !"FAILED".equals(state)
                && comment.isEmpty() && !prCalls.isEmpty()) {
            throw new AssertionError(state + " is neither green nor red: the pull request must be left alone, saw "
                    + app.logText());
        }
        String write = expectedWrite;
        if (write != null) {
            assertTrue(wrote.stream().anyMatch(line -> write.equals(line[0] + " " + line[1])),
                    "expected " + write + ", saw " + app.logText());
        }
        if (!comment.isEmpty()) {
            String comments = "POST " + prPath + "/comments";
            assertTrue(wrote.stream().anyMatch(line -> comments.equals(line[0] + " " + line[1])),
                    "the comment was never posted, saw " + app.logText());
        }

        Map<String, Object> pr = app.pullRequestDocument();
        boolean approved = Boolean.TRUE.equals(pr.get("approved"));
        if ("approve".equals(action) && "SUCCESSFUL".equals(state)) {
            assertTrue(approved, "the server does not hold an approval after the run");
        }
        if ("approve".equals(action) && "FAILED".equals(state)) {
            assertFalse(approved, "the server still holds the approval after a red build");
        }
        if ("decline".equals(action) && "FAILED".equals(state)) {
            assertEquals("DECLINED", pr.get("state"), "the pull request is not DECLINED");
        }
        if (!comment.isEmpty()) {
            List<String> texts = list(pr.get("comments")).stream().map(entry -> String.valueOf(map(entry).get("text"))).toList();
            assertTrue(texts.contains(comment), "the server's comments " + texts + " do not contain the text given");
        }

        if (!"none".equals(action) && ("SUCCESSFUL".equals(state) || "FAILED".equals(state))) {
            String[] wanted = switch (action) {
                case "approve" -> "FAILED".equals(state) ? new String[] {"withdraw-approve", "withdrawn"}
                        : new String[] {"approve", "approved"};
                case "decline" -> new String[] {"decline", "declined"};
                default -> null;
            };
            if (wanted != null) {
                assertEquals(wanted[0], run.field("prAction"), "output.json says the wrong prAction");
                assertEquals(wanted[1], run.field("prVerified"), "output.json says the wrong prVerified");
            }
        }
        if (!comment.isEmpty()) {
            assertFalse(run.field("commentId").isEmpty(), "the atom reported no comment id after posting a comment");
        }
    }

    private static void assertSkipped(AtomResult run, FakeBitbucket app) throws Exception {
        run.assertOk();
        assertEquals("SKIPPED", run.field("state"), "expected SKIPPED, got " + run.field("state"));
        assertTrue(app.logText().isBlank(), "a request was sent for a state that must be skipped:\n" + app.logText());
    }

    private static void assertContains(AtomResult run, String needle) {
        String output = run.file("output.json");
        assertTrue(output.contains(needle), "output.json does not mention " + needle + ":\n" + output);
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private FakeBitbucket server(String mode, String authMode, String credential) throws Exception {
        return server(mode, authMode, credential, "");
    }

    private FakeBitbucket server(String mode, String authMode, String credential, String expectSha) throws Exception {
        return server(mode, authMode, credential, expectSha, "PROJ/parcel/1");
    }

    private FakeBitbucket server(String mode, String authMode, String credential, String expectSha, String expectPr)
            throws Exception {
        return FakeBitbucket.start(MODULE, work, mode, authMode, credential, expectSha, expectPr);
    }

    private static Map<String, String> base(FakeBitbucket app) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("serverUrl", app.base());
        params.put("sha", SHA);
        params.put("BUILD_STATUS", "SUCCESS");
        params.put("JOB_NAME", "parcel");
        params.put("BUILD_NUMBER", "42");
        params.put("BUILD_URL", "http://jenkins/job/parcel/42/display/redirect");
        return params;
    }

    private static AtomResult run(Map<String, String> params, Map<String, String> secrets) throws Exception {
        return Atom.of()
                .file(".secrets.json", json(secrets))
                .input(json(params))
                .run();
    }

    private static Map<String, Object> lastStored(FakeBitbucket app, String sha) throws Exception {
        List<?> entries = list(app.storeDocument().get(sha));
        if (entries.isEmpty()) {
            throw new AssertionError("the server stored nothing for this commit:\n" + app.output());
        }
        return map(entries.get(entries.size() - 1));
    }

    private static String expectedState(Map<String, Object> given) {
        String raw = value(given, "BUILD_STATUS").trim().toUpperCase();
        boolean unstableAsSuccess = truthy(value(given, "considerUnstableAsSuccess"));
        return switch (raw) {
            case "", "SUCCESS", "SUCCESSFUL", "OK", "PASSED" -> "SUCCESSFUL";
            case "UNSTABLE", "WARNING" -> unstableAsSuccess ? "SUCCESSFUL" : "FAILED";
            case "BUILDING", "RUNNING", "PENDING", "INPROGRESS", "IN_PROGRESS" -> "INPROGRESS";
            case "ABORTED", "CANCELED", "CANCELLED", "NOT_BUILT", "NOTBUILT" -> "";
            default -> "FAILED";
        };
    }

    private static String abbreviate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 3) + "...";
    }

    private static String expand(Map<String, Object> given, String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\$\\{([A-Za-z_][A-Za-z0-9_.-]*)}").matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value(given, matcher.group(1))));
        }
        return matcher.appendTail(out).toString();
    }

    private static String value(Map<String, Object> given, String key) {
        Object value = given.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean truthy(String value) {
        return "true".equals(value.trim().toLowerCase());
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String json(Map<String, String> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                out.append(", ");
            }
            first = false;
            out.append('"').append(entry.getKey()).append("\": \"")
                    .append(entry.getValue().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return out.append('}').toString();
    }
}
