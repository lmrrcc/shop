package com.cherrytest.shop.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class ShopAdapter {
    static final Duration HEALTH_WAIT = Duration.ofSeconds(60);
    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    static final Duration STOP_WAIT = Duration.ofSeconds(30);
    static final String STOCK_RESET = "DELETE FROM customer_order_line; DELETE FROM customer_order; DELETE FROM cart_line;"
            + " DELETE FROM cart; UPDATE product SET stock = CASE sku WHEN 'MUG-STONE' THEN 1000 WHEN 'TEE-CREW-M'"
            + " THEN 250 WHEN 'NB-DOT-A5' THEN 500 WHEN 'LAMP-DESK' THEN 2 ELSE stock END;"
            + " SELECT setval(pg_get_serial_sequence('customer_order', 'id'), 1, false)";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PrintStream OUT = System.out;
    private static final PrintStream ERR = System.err;

    private final Request request;
    private final Map<String, String> fileEnv;
    private final Path profile;
    private final Path jar;
    private final Path agent;
    private final List<Process> processes = new ArrayList<>();
    final List<TestRecord> tests = new ArrayList<>();
    private final List<Path> coverage = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();
    boolean ok = true;
    private String api = "";
    private String databaseUrl = "";

    ShopAdapter(Request request) {
        this.request = request;
        this.fileEnv = readEnvFile(request.root().resolve(".env"));
        this.profile = request.output().resolve("jacoco");
        this.jar = request.root().resolve("shop-java-api/target/shop-java-api.jar");
        this.agent = request.root().resolve("tools/shop-adapter/target/jacocoagent.jar");
    }

    public static void main(String[] arguments) {
        if (arguments.length == 0 || !arguments[0].equals("run")) {
            ERR.println("usage: shop-adapter run");
            System.exit(2);
        }
        ObjectNode response;
        try {
            response = new ShopAdapter(Request.parse(readRequest(System.in))).execute();
        } catch (AdapterException | IOException failure) {
            response = failure(String.valueOf(failure.getMessage()));
        }
        finish(response);
    }

    private static JsonNode readRequest(InputStream input) throws IOException {
        try {
            return JSON.readTree(input.readAllBytes());
        } catch (JacksonException exception) {
            throw new AdapterException(exception.getOriginalMessage(), exception);
        }
    }

    private static void finish(ObjectNode response) {
        JsonNode success = response.get("success");
        boolean passed = success != null && success.asBoolean();
        OUT.println(JSON.writeValueAsString(response));
        OUT.flush();
        System.exit(passed ? 0 : 1);
    }

    static ObjectNode failure(String message) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("protocol", 1);
        response.put("success", false);
        response.putArray("tests");
        response.putArray("coverage");
        response.putArray("diagnostics").add(message);
        return response;
    }

    ObjectNode execute() throws IOException {
        Files.createDirectories(request.output());
        Files.createDirectories(profile);
        if (!List.of("integration", "functional", "visual").contains(request.phase())) {
            throw new AdapterException("unsupported phase " + request.phase());
        }
        database();
        build();
        probeMissingDatabase();
        probeBind();
        AdapterException phaseFailure = null;
        try {
            startApi();
            if (request.phase().equals("integration")) {
                integrationTests();
            } else {
                resetStock();
                playwright();
            }
        } catch (AdapterException failure) {
            phaseFailure = failure;
        }
        stopChildren();
        if (phaseFailure != null) {
            throw phaseFailure;
        }
        if (request.coverage() && request.phase().equals("integration")) {
            try {
                exportServer();
            } catch (AdapterException failure) {
                ok = false;
                diagnostics.add(String.valueOf(failure.getMessage()));
            }
        }
        return response();
    }

    ObjectNode response() {
        if (tests.stream().anyMatch(test -> test.status().equals("failed"))) {
            ok = false;
        }
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("protocol", 1);
        response.put("success", ok);
        ArrayNode testArray = response.putArray("tests");
        tests.forEach(test -> testArray.add(test.json()));
        ArrayNode coverageArray = response.putArray("coverage");
        coverage.forEach(path -> coverageArray.add(path.toString()));
        ArrayNode diagnosticArray = response.putArray("diagnostics");
        diagnostics.forEach(diagnosticArray::add);
        return response;
    }

    String setting(String key) {
        return present(System.getenv(key))
                .or(() -> present(fileEnv.get(key)))
                .or(() -> present(request.env().get(key)))
                .orElseThrow(() -> new AdapterException(key + " is not set"));
    }

    private void database() {
        String host = requestOrProcess(request.env(), "CHERRY_TEST_SHOP_DB_HOST")
                .orElseThrow(() -> new AdapterException("Postgres endpoint is missing from the adapter request"));
        String port = requestOrProcess(request.env(), "CHERRY_TEST_SHOP_DB_PORT")
                .orElseThrow(() -> new AdapterException("Postgres endpoint is missing from the adapter request"));
        databaseUrl = "postgres://" + percentEncode(setting("SHOP_DB_USER")) + ":"
                + percentEncode(setting("SHOP_DB_PASSWORD")) + "@" + host + ":" + port + "/"
                + percentEncode(setting("SHOP_DB_NAME"));
    }

    private void build() throws IOException {
        Path log = request.output().resolve("build.log");
        ProcessBuilder command = Processes.command(
                request.root(),
                List.of(request.root().resolve("mvnw").toString(), "-B", "-ntp", "-q", "-Dmaven.test.skip=true", "-pl",
                        "shop-java-api", "-am", "package"),
                Map.of(),
                List.of());
        int code = Processes.status(command, log, null);
        if (code != 0 || !Files.isRegularFile(jar)) {
            throw new AdapterException("instrumented build failed\n" + Processes.tail(log, Processes.TAIL_CHARS));
        }
    }

    private List<String> serverArgv(String label) {
        return List.of(
                "java",
                "-javaagent:" + agent + "=destfile=" + profile.resolve("shop-" + label + ".exec") + ",append=false",
                "-jar",
                jar.toString());
    }

    private ProcessBuilder serverCommand(String label, Map<String, String> set, List<String> removed) {
        return Processes.command(request.root(), serverArgv(label), set, removed);
    }

    private void probeMissingDatabase() throws IOException {
        Path log = request.output().resolve("probe-missing-database.stderr");
        ProcessBuilder command =
                serverCommand("probe-missing-database", Map.of(), List.of("DATABASE_URL", "SHOP_API_ADDR"));
        int code = Processes.status(command, log, Duration.ofSeconds(30));
        if (code != 1) {
            ok = false;
            diagnostics.add("missing-database probe exited " + code + "\n" + Processes.tail(log, Processes.TAIL_CHARS));
        }
    }

    private void probeBind() throws IOException {
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Path log = request.output().resolve("probe-bind.stderr");
            ProcessBuilder command = serverCommand(
                    "probe-bind",
                    Map.of("DATABASE_URL", databaseUrl, "SHOP_API_ADDR", "127.0.0.1:" + listener.getLocalPort()),
                    List.of());
            int code = Processes.status(command, log, Duration.ofSeconds(60));
            if (code != 1) {
                ok = false;
                diagnostics.add("bind probe exited " + code + "\n" + Processes.tail(log, Processes.TAIL_CHARS));
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return listener.getLocalPort();
        }
    }

    private void startApi() {
        try {
            int port = freePort();
            Path log = request.output().resolve("api.stderr");
            ProcessBuilder command = serverCommand(
                    "api", Map.of("DATABASE_URL", databaseUrl, "SHOP_API_ADDR", "127.0.0.1:" + port), List.of());
            processes.add(Processes.spawnLogged(command, log));
            api = "http://127.0.0.1:" + port;
            waitHttp(api + "/health", log);
        } catch (IOException exception) {
            throw new AdapterException("start shop-java-api: " + exception.getMessage(), exception);
        }
    }

    private void integrationTests() {
        Path log = request.output().resolve("tests.stdout");
        String port = api.substring(api.lastIndexOf(':') + 1);
        ProcessBuilder command = Processes.command(
                request.root(),
                List.of(request.root().resolve("mvnw").toString(), "-B", "-ntp", "-pl", "shop-java-it", "verify"),
                Map.of(
                        "CHERRY_TEST_SKIP_DOCKER", "1",
                        "CHERRY_TEST_SHOP_API_URL", api,
                        "CHERRY_TEST_SHOP_API_HOST", "127.0.0.1",
                        "CHERRY_TEST_SHOP_API_PORT", port),
                List.of());
        int code;
        try {
            code = Processes.status(command, log, null);
        } catch (IOException exception) {
            throw new AdapterException("run integration tests: " + exception.getMessage(), exception);
        }
        for (Path report : failsafeReports(request.root().resolve("shop-java-it/target/failsafe-reports"))) {
            tests.addAll(JunitReport.records(report));
        }
        if (code != 0) {
            ok = false;
            diagnostics.add("integration tests exited " + code + "\n" + Processes.tail(log, Processes.TAIL_CHARS));
        }
        if (tests.isEmpty()) {
            ok = false;
            diagnostics.add("no integration test results were parsed");
        }
    }

    private static List<Path> failsafeReports(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("TEST-"))
                    .filter(path -> path.toString().endsWith(".xml"))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new AdapterException("read " + directory + ": " + exception.getMessage(), exception);
        }
    }

    static String composeProject(Map<String, String> env) {
        return present(System.getenv("CHERRY_TEST_COMPOSE_PROJECT"))
                .or(() -> present(env.get("CHERRY_TEST_COMPOSE_PROJECT")))
                .orElse("cherry-test");
    }

    private void resetStock() {
        ProcessBuilder command = Processes.command(
                request.root(),
                List.of("docker", "compose", "-p", composeProject(request.env()), "-f", "docker-compose.test.yml",
                        "exec", "-T", "shop-db", "psql", "-U", setting("SHOP_DB_USER"), "-d",
                        setting("SHOP_DB_NAME"), "-v", "ON_ERROR_STOP=1", "-c", STOCK_RESET),
                Map.of(),
                List.of());
        Processes.Output output;
        try {
            output = Processes.output(command);
        } catch (IOException exception) {
            throw new AdapterException(String.valueOf(exception.getMessage()), exception);
        }
        if (output.code() == 0) {
            return;
        }
        String detail = !output.stderr().strip().isEmpty()
                ? Processes.endSlice(output.stderr(), 2000)
                : Processes.endSlice(output.stdout(), 2000);
        throw new AdapterException(detail.isEmpty() ? "stock reset failed" : detail);
    }

    private void playwright() {
        Path web = request.root().resolve("shop-ts-web-react");
        try {
            int port = freePort();
            Path viteLog = request.output().resolve("vite.stderr");
            processes.add(Processes.spawnLogged(
                    Processes.command(
                            web,
                            List.of("pnpm", "exec", "vite", "--host", "127.0.0.1"),
                            Map.of("SHOP_API_URL", api, "SHOP_WEB_PORT", Integer.toString(port), "SHOP_ISTANBUL", "1"),
                            List.of()),
                    viteLog));
            String webUrl = "http://127.0.0.1:" + port;
            waitHttp(webUrl, viteLog);
            Processes.Output install = Processes.output(Processes.command(
                    web, List.of("pnpm", "exec", "playwright", "install", "chromium"), Map.of(), List.of()));
            if (install.code() != 0) {
                throw new AdapterException(install.stderr().strip().isEmpty()
                        ? "playwright browser install failed"
                        : Processes.endSlice(install.stderr(), 2000));
            }
            Path coverageFile = request.output().resolve("coverage.jsonl");
            Path report = request.output().resolve("playwright.json");
            List<String> argv = new ArrayList<>(List.of(
                    "pnpm", "exec", "playwright", "test", "tests/" + request.phase() + ".spec.ts", "--reporter=json"));
            if ("1".equals(System.getenv("CHERRY_TEST_UPDATE_SNAPSHOTS"))) {
                argv.add("--update-snapshots");
            }
            Path stdout = request.output().resolve("tests.stdout");
            Map<String, String> environment = new TreeMap<>();
            environment.put("SHOP_WEB_URL", webUrl);
            environment.put("SHOP_COVERAGE_FILE", coverageFile.toString());
            environment.put("SHOP_ROOT", request.root().toString());
            environment.put("SHOP_DB_USER", setting("SHOP_DB_USER"));
            environment.put("SHOP_DB_NAME", setting("SHOP_DB_NAME"));
            environment.put("SHOP_COMPOSE_PROJECT", composeProject(request.env()));
            environment.put("PLAYWRIGHT_JSON_OUTPUT_FILE", report.toString());
            environment.put("CI", "1");
            int code = Processes.status(Processes.command(web, argv, environment, List.of()), stdout, null);
            if (!Files.isRegularFile(report)) {
                throw new AdapterException(
                        "playwright wrote no JSON report\n" + Processes.tail(stdout, Processes.TAIL_CHARS));
            }
            JsonNode payload;
            try {
                payload = JSON.readTree(Files.readAllBytes(report));
            } catch (JacksonException exception) {
                throw new AdapterException(exception.getOriginalMessage(), exception);
            }
            JsonNode suites = payload.get("suites");
            PlaywrightReport.collect(suites, request.output(), tests);
            Optional<Path> lcov = browserLcov(web, coverageFile);
            if (lcov.isPresent()) {
                coverage.add(lcov.get());
            } else if (request.coverage()) {
                ok = false;
                diagnostics.add("browser coverage was empty");
            }
            if (code != 0) {
                ok = false;
                diagnostics.add("playwright exited " + code + "\n" + Processes.tail(stdout, Processes.TAIL_CHARS));
            }
            if (tests.isEmpty()) {
                ok = false;
                diagnostics.add("playwright reported no tests");
            }
        } catch (IOException exception) {
            throw new AdapterException(String.valueOf(exception.getMessage()), exception);
        }
    }

    private Optional<Path> browserLcov(Path web, Path coverageFile) throws IOException {
        if (!Files.isRegularFile(coverageFile) || Files.size(coverageFile) == 0) {
            return Optional.empty();
        }
        Path directory = request.output().resolve("browser");
        Processes.Output output = Processes.output(Processes.command(
                web,
                List.of("node", web.resolve("scripts/write-lcov.mjs").toString(), coverageFile.toString(),
                        directory.toString()),
                Map.of(),
                List.of()));
        Path lcov = directory.resolve("browser.lcov");
        if (output.code() != 0 || !Files.isRegularFile(lcov)) {
            ok = false;
            diagnostics.add(output.stderr().strip().isEmpty()
                    ? "browser LCOV was not written"
                    : Processes.endSlice(output.stderr(), 2000));
            return Optional.empty();
        }
        return Optional.of(lcov);
    }

    private void exportServer() {
        try {
            List<Path> executions = ServerCoverage.executionFiles(profile);
            if (executions.isEmpty()) {
                throw new AdapterException("instrumented server wrote no JaCoCo execution data");
            }
            String lcov = ServerCoverage.lcov(
                    executions,
                    request.root().resolve("shop-java-api/target/classes"),
                    request.root().resolve("shop-java-api/src/main/java"));
            if (!lcov.contains("DA:")) {
                throw new AdapterException("server coverage had no shop-java-api lines");
            }
            Path destination = request.output().resolve("server-full.lcov");
            Files.writeString(destination, lcov, StandardCharsets.UTF_8);
            coverage.add(destination);
        } catch (IOException exception) {
            throw new AdapterException("export server coverage: " + exception.getMessage(), exception);
        }
    }

    private void stopChildren() {
        for (Process process : processes) {
            if (process.isAlive()) {
                process.destroy();
            }
        }
        for (Process process : processes) {
            try {
                if (!process.waitFor(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                    ok = false;
                    diagnostics.add("a child required SIGKILL; coverage may be incomplete");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                ok = false;
                diagnostics.add("interrupted while stopping a child; coverage may be incomplete");
            }
        }
        processes.clear();
    }

    private static void waitHttp(String url, Path log) {
        Instant deadline = Instant.now().plus(HEALTH_WAIT);
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(HTTP_TIMEOUT)
                .build();
        while (Instant.now().isBefore(deadline)) {
            if (httpOk(client, url)) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AdapterException("interrupted while waiting for " + url, exception);
            }
        }
        throw new AdapterException(url + " did not become ready\n" + Processes.tail(log, Processes.TAIL_CHARS));
    }

    private static boolean httpOk(HttpClient client, String url) {
        try {
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(HTTP_TIMEOUT).build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static Optional<String> present(@Nullable String value) {
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    static Optional<String> requestOrProcess(Map<String, String> env, String key) {
        return present(env.get(key)).or(() -> present(System.getenv(key)));
    }

    static Map<String, String> readEnvFile(Path path) {
        Map<String, String> values = new TreeMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return values;
        }
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;
            }
            values.put(line.substring(0, equals).strip(), trimQuotes(line.substring(equals + 1).strip()));
        }
        return values;
    }

    private static String trimQuotes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == '"' || value.charAt(start) == '\'')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == '"' || value.charAt(end - 1) == '\'')) {
            end--;
        }
        return value.substring(start, end);
    }

    static String percentEncode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = raw & 0xff;
            if ((Character.isLetterOrDigit(unsigned) && unsigned < 128) || "-._~".indexOf(unsigned) >= 0) {
                encoded.append((char) unsigned);
            } else {
                encoded.append(String.format(Locale.ROOT, "%%%02X", unsigned));
            }
        }
        return encoded.toString();
    }

    static long wholeMillis(@Nullable JsonNode value) {
        if (value == null || !value.isNumber()) {
            return 0;
        }
        if (value.isIntegralNumber()) {
            return Math.max(0, value.longValue());
        }
        double number = value.doubleValue();
        if (!Double.isFinite(number) || number <= 0.0 || number >= 1.0e15) {
            return 0;
        }
        return (long) number;
    }
}
