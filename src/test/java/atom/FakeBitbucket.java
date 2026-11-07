package atom;

import com.jenkinschina.atom.sdk.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One fake Bitbucket per scenario, started the way {@code tools/smoke.sh} started it: a real HTTP server on a port the
 * kernel picks, which re-checks every request itself and stores exactly what it will hand back.
 *
 * <p>The fixture stays in Python ({@code tools/fake_bitbucket.py}); this only starts it and reads the files it writes,
 * so a test can judge the run in Java.
 */
final class FakeBitbucket implements AutoCloseable {

    private final Process process;
    private final Path dir;
    private final int port;
    private final String expectPr;
    private final StringBuilder output = new StringBuilder();

    private FakeBitbucket(Process process, Path dir, int port, String expectPr) {
        this.process = process;
        this.dir = dir;
        this.port = port;
        this.expectPr = expectPr;
    }

    static FakeBitbucket start(Path moduleDir, Path dir, String mode, String authMode, String credential,
            String expectSha, String expectPr) throws Exception {
        List<String> command = new ArrayList<>(List.of("python3",
                moduleDir.resolve("tools/fake_bitbucket.py").toString(),
                "--port", "0",
                "--dir", dir.toString(),
                "--mode", mode,
                "--auth-mode", authMode,
                "--credential", credential,
                "--expect-sha", expectSha == null ? "" : expectSha,
                "--expect-pr", expectPr == null ? "" : expectPr));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        FakeBitbucket app = new FakeBitbucket(process, dir, -1, expectPr);

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        int port = -1;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            app.output.append(line).append('\n');
            if (line.startsWith("listening ")) {
                port = Integer.parseInt(line.substring("listening ".length()).trim());
                break;
            }
        }
        if (port < 0) {
            process.destroyForcibly();
            throw new IllegalStateException("the fake Bitbucket did not start:\n" + app.output);
        }
        FakeBitbucket started = new FakeBitbucket(process, dir, port, expectPr);
        started.drain(reader);
        return started;
    }

    /** Keep reading so the server never blocks on a full pipe; the lines are only used for failure messages. */
    private void drain(BufferedReader reader) {
        Thread thread = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // the process ended: nothing left to read
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    static List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }

    int port() {
        return port;
    }

    String base() {
        return "http://127.0.0.1:" + port;
    }

    String pullRequestUrl() {
        return base() + "/rest/api/1.0/projects/PROJ/repos/parcel/pull-requests/1";
    }

    Path store() {
        return dir.resolve("store-" + port + ".json");
    }

    Path log() {
        return dir.resolve("log-" + port + ".tsv");
    }

    Path pullRequest() {
        return dir.resolve("pr-" + port + ".json");
    }

    /** The requests the server received, as METHOD / PATH / AUTHORIZATION / NOTE. */
    List<String[]> lines() throws IOException {
        List<String[]> lines = new ArrayList<>();
        for (String line : Files.readAllLines(log(), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                lines.add(line.split("\t", -1));
            }
        }
        return lines;
    }

    String logText() throws IOException {
        return Files.readString(log(), StandardCharsets.UTF_8);
    }

    void clearLog() throws IOException {
        Files.writeString(log(), "", StandardCharsets.UTF_8);
    }

    Map<String, Object> storeDocument() throws IOException {
        String text = Files.readString(store(), StandardCharsets.UTF_8);
        return text.isBlank() ? Map.of() : Json.parseObject(text);
    }

    Map<String, Object> pullRequestDocument() throws IOException {
        String text = Files.readString(pullRequest(), StandardCharsets.UTF_8);
        return text.isBlank() ? Map.of() : Json.parseObject(text);
    }

    String expectPr() {
        return expectPr;
    }

    String output() {
        synchronized (output) {
            return output.toString();
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        process.destroyForcibly();
    }
}
