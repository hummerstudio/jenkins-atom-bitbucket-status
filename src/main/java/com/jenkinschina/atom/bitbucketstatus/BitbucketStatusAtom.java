package com.jenkinschina.atom.bitbucketstatus;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomService;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
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

/**
 * Report a build state to a Bitbucket Server / Data Center commit — port of {@code stashNotifier}.
 *
 * <p>The plugin is a {@code Notifier} (prebuild + perform), which is why it never had a Pipeline step: the interesting
 * part, however, is one HTTP call. Bitbucket's build-status API is
 * {@code POST {root}/rest/build-status/1.0/commits/{sha}} with {@code {state, key, name, description, url}} and the
 * three states {@code SUCCESSFUL}/{@code FAILED}/{@code INPROGRESS}. The plugin does not look at the answer; this atom
 * does: after the POST it GETs the same URL and requires an entry with the same key, state and url, because a 204 from
 * a proxy in front of Bitbucket proves nothing.
 */
@AtomService("bitbucket-status")
public class BitbucketStatusAtom implements AtomTask {

    private static final int MAX_KEY = 255;
    private static final int MAX_NAME = 255;
    private static final int MAX_DESCRIPTION = 255;
    private static final int MAX_URL = 450;

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

        return AtomResult.success()
                .data("status", String.valueOf(code))
                .data("state", state)
                .data("key", payload.get("key"))
                .data("apiUrl", commitUrl)
                .data("verified", verified);
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

    private static final class ReadBackException extends Exception {
        ReadBackException(String message) {
            super(message);
        }
    }
}
