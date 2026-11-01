package com.jenkinschina.atom.bitbucketstatus;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomService;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Report a build state to a Bitbucket Server / Data Center commit — port of {@code stashNotifier}.
 *
 * <p>The plugin is a {@code Notifier} (prebuild + perform), which is why it never had a Pipeline step: the interesting
 * part, however, is one HTTP call. Bitbucket's build-status API is
 * {@code POST {root}/rest/build-status/1.0/commits/{sha}} with {@code {state, key, name, description, url}} and the
 * three states {@code SUCCESSFUL}/{@code FAILED}/{@code INPROGRESS}. The plugin does not look at the answer; this atom
 * does: after the POST it GETs the same URL and requires an entry with the same key, state and url, because a 204 from
 * a proxy in front of Bitbucket proves nothing.
 *
 * <p>The same plugin also acts on the pull request that the hook was for: {@code BitBucketPPRPullRequestServerObserver}
 * calls {@code getLinkApprove()} / {@code getLinkDecline()} and sends {@code POST .../approve}, {@code DELETE .../approve}
 * (withdraw) or {@code POST .../decline} with an empty body. That half is ported here as {@code prAction}. The
 * {@code comment} input is <em>not</em> in the plugin — it is the one thing a build is usually asked to do on a PR that
 * the plugin never did; it posts {@code POST .../comments {"text": ...}} and reads the comment list back.
 */
@AtomService("bitbucket-status")
public class BitbucketStatusAtom implements AtomTask {

    private static final int MAX_KEY = 255;
    private static final int MAX_NAME = 255;
    private static final int MAX_DESCRIPTION = 255;
    private static final int MAX_URL = 450;
    private static final Pattern PR_URL =
            Pattern.compile("/projects/([^/]+)/repos/([^/]+)/pull-requests/(\\d+)");
    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.-]*)}");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String server = first(context, "serverUrl", "bitbucketUrl", "url");
        if (server.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "serverUrl is required: the Bitbucket Server/Data Center root, e.g. https://bitbucket.example.com");
        }
        server = server.trim().replaceAll("/+$", "");
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            return AtomResult.error(ErrorType.USER, 40001, "serverUrl must be an absolute http(s) URL: " + server);
        }

        String sha = first(context, "sha", "commit", "GIT_COMMIT");
        if (sha.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001, "sha is required (or run after a checkout so GIT_COMMIT is set)");
        }

        String token = orEmpty(context.getSecret("token"));
        String username = context.getParam("username", "");
        String password = orEmpty(context.getSecret("password"));
        if (token.isBlank() && (username.isBlank() || password.isBlank())) {
            return AtomResult.error(ErrorType.USER, 40002,
                    "bind the token credential (HTTP access token) or username + password (personal access token):"
                            + " Bitbucket rejects anonymous build status");
        }

        boolean unstableAsSuccess = context.getBooleanParam("considerUnstableAsSuccess", false);
        boolean notifyAborted = context.getBooleanParam("notifyAborted", false);
        String requestedState = context.getParam("state", "").trim();
        if (requestedState.isEmpty()) {
            requestedState = orEmpty(context.getParam("BUILD_STATUS"));
        }

        String state = mapState(requestedState, unstableAsSuccess);
        if (state == null) {
            String reason = "state '" + requestedState + "' is not reported to Bitbucket";
            if (notifyAborted) {
                // the only two states Bitbucket knows for a finished build
                state = "FAILED";
            } else {
                context.log("[bitbucket-status] " + reason + " (notifyAborted=false), nothing to do");
                return AtomResult.success(reason)
                        .data("state", "SKIPPED")
                        .data("key", "")
                        .data("apiUrl", "");
            }
        }

        String buildUrl = first(context, "buildUrl", "targetUrl", "BUILD_URL");
        String rootUrl = context.getParam("jenkinsRootUrl", "");
        String description = context.getParam("description", "");
        if (description.isBlank()) {
            String where = rootUrl.isBlank() ? buildUrl : rootUrl;
            description = "INPROGRESS".equals(state) ? "building on Jenkins @ " + where : "built by Jenkins @ " + where;
        }
        String key = context.getParam("key", "");
        if (key.isBlank()) {
            key = orEmpty(context.getParam("JOB_NAME"));
            if (context.getBooleanParam("includeBuildNumberInKey", false)) {
                key = key + "-" + orEmpty(context.getParam("BUILD_NUMBER"));
            }
        }
        String name = context.getParam("name", "");
        if (name.isBlank()) {
            String job = orEmpty(context.getParam("JOB_NAME"));
            String number = orEmpty(context.getParam("BUILD_NUMBER"));
            name = number.isBlank() ? job : job + " #" + number;
        }

        // ---- pull request side: the plugin's approve/decline, plus a comment -------------------------------
        String prAction = context.getParam("prAction", "none").trim().toLowerCase(Locale.ROOT);
        if (!prAction.equals("none") && !prAction.equals("approve") && !prAction.equals("decline")) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "prAction must be none, approve or decline, got " + prAction);
        }
        String comment = expand(context, orEmpty(context.getParam("comment", ""))).trim();
        String prApi = "";
        if (!prAction.equals("none") || !comment.isEmpty()) {
            prApi = pullRequestApi(context, server);
            if (prApi.isEmpty()) {
                return AtomResult.error(ErrorType.USER, 40002,
                        "a pull-request action needs the pull request: pullRequestUrl,"
                                + " or projectKey + repoSlug + pullRequestId");
            }
        }

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("state", state);
        payload.put("key", abbreviate(key, MAX_KEY));
        payload.put("name", abbreviate(name, MAX_NAME));
        payload.put("description", abbreviate(description, MAX_DESCRIPTION));
        payload.put("url", abbreviate(buildUrl, MAX_URL));

        String commitUrl = server + "/rest/build-status/1.0/commits/" + sha.trim();
        String body = Json.write(payload);
        String authorization = token.isBlank()
                ? "Basic " + Base64.getEncoder().encodeToString(
                        (username + ":" + password).getBytes(StandardCharsets.UTF_8))
                : "Bearer " + token;

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        context.log("[bitbucket-status] " + sha.trim() + " -> " + state + " (key=" + payload.get("key") + ") at " + commitUrl);
        HttpResponse<String> response;
        try {
            response = client.send(HttpRequest.newBuilder(URI.create(commitUrl))
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AtomResult.error(ErrorType.THIRD_PART, 40004, "posting the build status was interrupted");
        } catch (Exception e) {
            return AtomResult.error(ErrorType.THIRD_PART, 40004,
                    "cannot reach " + commitUrl + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        int code = response.statusCode();
        if (code == 401 || code == 403) {
            return AtomResult.error(ErrorType.THIRD_PART, 40005, "Bitbucket answered " + code
                    + " — the credential is missing a REPO_WRITE grant or is not accepted here: " + excerpt(response.body()));
        }
        if (code / 100 != 2) {
            return AtomResult.error(ErrorType.THIRD_PART, 40005,
                    "Bitbucket answered " + code + " for " + commitUrl + ": " + excerpt(response.body()));
        }

        boolean verify = context.getBooleanParam("verifyAfterWrite", true);
        String verified = "not-checked";
        if (verify) {
            Map<String, Object> seen;
            try {
                seen = readBack(client, commitUrl, authorization, payload.get("key"));
            } catch (ReadBackException e) {
                return AtomResult.error(ErrorType.THIRD_PART, 40006, e.getMessage());
            }
            String seenState = String.valueOf(seen.get("state"));
            String seenUrl = String.valueOf(seen.get("url"));
            if (!state.equals(seenState)) {
                return AtomResult.error(ErrorType.THIRD_PART, 40006, "Bitbucket answered " + code + " but reports state "
                        + seenState + " for key " + payload.get("key") + " (sent " + state + ")");
            }
            if (!payload.get("url").isEmpty() && !payload.get("url").equals(seenUrl)) {
                return AtomResult.error(ErrorType.THIRD_PART, 40006, "Bitbucket reports url " + seenUrl
                        + " for key " + payload.get("key") + ", sent " + payload.get("url"));
            }
            verified = seenState;
        }

        String prDone = "none";
        String prVerified = "not-checked";
        String commentId = "";
        if (!prApi.isEmpty()) {
            context.log("[bitbucket-status] pull request " + prApi + " action=" + prAction);
            try {
                if (prAction.equals("approve")) {
                    if ("SUCCESSFUL".equals(state)) {
                        HttpResponse<String> answer = call(client, "POST", prApi + "/approve", authorization, "{}");
                        int prCode = answer.statusCode();
                        // 409 is Bitbucket's "you have already approved this"; the read-back below decides
                        if (prCode / 100 != 2 && prCode != 409) {
                            throw new PrException(40007, "approving " + prApi + " answered " + prCode + ": "
                                    + excerpt(answer.body()));
                        }
                        verifyApproved(client, prApi, authorization, true);
                        prDone = "approve";
                        prVerified = "approved";
                    } else if ("FAILED".equals(state)) {
                        HttpResponse<String> answer = call(client, "DELETE", prApi + "/approve", authorization, null);
                        int prCode = answer.statusCode();
                        if (prCode / 100 != 2 && prCode != 404) {
                            throw new PrException(40007, "withdrawing the approval of " + prApi + " answered " + prCode
                                    + ": " + excerpt(answer.body()));
                        }
                        verifyApproved(client, prApi, authorization, false);
                        prDone = "withdraw-approve";
                        prVerified = "withdrawn";
                    } else {
                        context.log("[bitbucket-status] " + state + " is neither green nor red: no approve/withdraw");
                    }
                } else if (prAction.equals("decline") && "FAILED".equals(state)) {
                    HttpResponse<String> answer = call(client, "POST", prApi + "/decline", authorization, "{}");
                    int prCode = answer.statusCode();
                    if (prCode / 100 != 2 && prCode != 409) {
                        throw new PrException(40007, "declining " + prApi + " answered " + prCode + ": "
                                + excerpt(answer.body()));
                    }
                    verifyDeclined(client, prApi, authorization);
                    prDone = "decline";
                    prVerified = "declined";
                } else if (prAction.equals("decline")) {
                    context.log("[bitbucket-status] " + state + " is not red: the pull request is left alone");
                }

                if (!comment.isEmpty()) {
                    Map<String, Object> commentBody = new LinkedHashMap<>();
                    commentBody.put("text", comment);
                    HttpResponse<String> answer = call(client, "POST", prApi + "/comments", authorization,
                            Json.write(commentBody));
                    int prCode = answer.statusCode();
                    if (prCode / 100 != 2) {
                        throw new PrException(40008, "posting the comment answered " + prCode + ": "
                                + excerpt(answer.body()));
                    }
                    commentId = verifyComment(client, prApi, authorization, comment);
                }
            } catch (PrException e) {
                return AtomResult.error(ErrorType.THIRD_PART, e.code, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AtomResult.error(ErrorType.THIRD_PART, 40004, "the pull request call was interrupted");
            } catch (Exception e) {
                return AtomResult.error(ErrorType.THIRD_PART, 40004, "cannot reach " + prApi + ": "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        return AtomResult.success()
                .data("status", String.valueOf(code))
                .data("state", state)
                .data("key", payload.get("key"))
                .data("apiUrl", commitUrl)
                .data("verified", verified)
                .data("prAction", prDone)
                .data("prVerified", prVerified)
                .data("commentId", commentId);
    }

    private static Map<String, Object> readBack(HttpClient client, String commitUrl, String authorization, String key)
            throws ReadBackException {
        HttpResponse<String> response;
        try {
            response = client.send(HttpRequest.newBuilder(URI.create(commitUrl))
                    .header("Authorization", authorization)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReadBackException("reading the statuses back was interrupted");
        } catch (Exception e) {
            throw new ReadBackException("cannot read the statuses back from " + commitUrl + ": " + e.getMessage());
        }
        if (response.statusCode() / 100 != 2) {
            throw new ReadBackException("reading the statuses back answered " + response.statusCode() + ": "
                    + excerpt(response.body()));
        }
        List<?> values;
        try {
            Object parsed = Json.parseObject(response.body()).get("values");
            values = parsed instanceof List<?> list ? list : List.of();
        } catch (RuntimeException e) {
            throw new ReadBackException("the status list is not the JSON Bitbucket documents: " + excerpt(response.body()));
        }
        for (Object value : values) {
            if (value instanceof Map<?, ?> entry && key.equals(String.valueOf(entry.get("key")))) {
                Map<String, Object> copy = new LinkedHashMap<>();
                entry.forEach((k, v) -> copy.put(String.valueOf(k), v));
                return copy;
            }
        }
        throw new ReadBackException("Bitbucket accepted the write but lists no status with key " + key
                + " (it returned " + values.size() + " entries)");
    }


    /** {@code {root}/projects/P/repos/R/pull-requests/N[/...]} → the REST URL on the server the atom was given. */
    static String pullRequestApi(AtomContext context, String server) {
        String project = context.getParam("projectKey", "").trim();
        String repo = context.getParam("repoSlug", "").trim();
        String id = first(context, "pullRequestId", "prId", "CHANGE_ID").trim();
        String url = context.getParam("pullRequestUrl", "").trim();
        if (!url.isEmpty()) {
            Matcher matcher = PR_URL.matcher(url);
            if (matcher.find()) {
                project = matcher.group(1);
                repo = matcher.group(2);
                id = matcher.group(3);
            }
        }
        if (project.isEmpty() || repo.isEmpty() || id.isEmpty()) {
            return "";
        }
        return server + "/rest/api/1.0/projects/" + project + "/repos/" + repo + "/pull-requests/" + id;
    }

    /** {@code ${NAME}} is replaced by the build parameter / environment variable of that name, as Jenkins does. */
    static String expand(AtomContext context, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher matcher = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(context.getParam(matcher.group(1), "")));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static HttpResponse<String> call(HttpClient client, String method, String url, String authorization,
                                             String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20));
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Reads the pull request back and requires that its reviewers/participants agree with what was just written. */
    private static void verifyApproved(HttpClient client, String prApi, String authorization, boolean expected)
            throws IOException, InterruptedException, PrException {
        Map<String, Object> pr = readPullRequest(client, prApi, authorization);
        boolean approved = false;
        for (String field : List.of("reviewers", "participants")) {
            if (pr.get(field) instanceof List<?> people) {
                for (Object person : people) {
                    if (person instanceof Map<?, ?> entry && Boolean.TRUE.equals(entry.get("approved"))) {
                        approved = true;
                    }
                }
            }
        }
        if (approved != expected) {
            throw new PrException(40007, "Bitbucket accepted the write but the pull request reports approved="
                    + approved + " (expected " + expected + ")");
        }
    }

    private static void verifyDeclined(HttpClient client, String prApi, String authorization)
            throws IOException, InterruptedException, PrException {
        Map<String, Object> pr = readPullRequest(client, prApi, authorization);
        String state = String.valueOf(pr.get("state"));
        if (!"DECLINED".equalsIgnoreCase(state)) {
            throw new PrException(40007, "Bitbucket accepted the decline but the pull request still reports state "
                    + state);
        }
    }

    private static Map<String, Object> readPullRequest(HttpClient client, String prApi, String authorization)
            throws IOException, InterruptedException, PrException {
        HttpResponse<String> answer = call(client, "GET", prApi, authorization, null);
        if (answer.statusCode() / 100 != 2) {
            throw new PrException(40007, "reading the pull request back answered " + answer.statusCode() + ": "
                    + excerpt(answer.body()));
        }
        try {
            return Json.parseObject(answer.body());
        } catch (RuntimeException e) {
            throw new PrException(40007, "the pull request is not the JSON Bitbucket documents: "
                    + excerpt(answer.body()));
        }
    }

    /** The comment list is the only evidence that the text survived: a 201 with our words echoed proves nothing. */
    private static String verifyComment(HttpClient client, String prApi, String authorization, String text)
            throws IOException, InterruptedException, PrException {
        HttpResponse<String> answer = call(client, "GET", prApi + "/comments?limit=100", authorization, null);
        if (answer.statusCode() / 100 != 2) {
            throw new PrException(40009, "reading the comments back answered " + answer.statusCode() + ": "
                    + excerpt(answer.body()));
        }
        List<?> values;
        try {
            Object parsed = Json.parseObject(answer.body()).get("values");
            values = parsed instanceof List<?> list ? list : List.of();
        } catch (RuntimeException e) {
            throw new PrException(40009, "the comment list is not the JSON Bitbucket documents: "
                    + excerpt(answer.body()));
        }
        for (Object value : values) {
            if (value instanceof Map<?, ?> entry && text.equals(String.valueOf(entry.get("text")))) {
                Object id = entry.get("id");
                return id == null ? "unknown" : String.valueOf(id);
            }
        }
        throw new PrException(40009, "Bitbucket accepted the comment but lists none with that text"
                + " (it returned " + values.size() + " comment(s))");
    }

    /** Jenkins result names in, Bitbucket names out; {@code null} means "the plugin sends nothing for this one". */
    static String mapState(String raw, boolean unstableAsSuccess) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "", "SUCCESS", "SUCCESSFUL", "OK", "PASSED" -> "SUCCESSFUL";
            case "UNSTABLE", "WARNING" -> unstableAsSuccess ? "SUCCESSFUL" : "FAILED";
            case "FAILURE", "FAILED", "ERROR" -> "FAILED";
            case "BUILDING", "RUNNING", "PENDING", "INPROGRESS", "IN_PROGRESS" -> "INPROGRESS";
            // the plugin prints ABORTED / NOT BUILT and returns without notifying
            case "ABORTED", "CANCELED", "CANCELLED", "NOT_BUILT", "NOTBUILT" -> null;
            default -> "FAILED";
        };
    }

    /** commons-lang3 {@code StringUtils.abbreviate}, which is what the plugin applies before sending. */
    static String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    private static String first(AtomContext context, String... names) {
        for (String name : names) {
            String value = context.getParam(name, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String excerpt(String body) {
        String oneLine = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class PrException extends Exception {
        private final int code;

        PrException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class ReadBackException extends Exception {
        ReadBackException(String message) {
            super(message);
        }
    }
}
