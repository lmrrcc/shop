package com.cherrytest.shop.adapter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

final class Processes {
    static final int TAIL_CHARS = 4000;
    private static final File DEV_NULL = new File("/dev/null");

    private Processes() {}

    static ProcessBuilder command(Path cwd, List<String> argv, Map<String, String> set, List<String> removed) {
        ProcessBuilder builder = new ProcessBuilder(argv).directory(cwd.toFile());
        removed.forEach(builder.environment()::remove);
        builder.environment().putAll(set);
        return builder;
    }

    static Process spawnLogged(ProcessBuilder builder, Path log) throws IOException {
        Path parent = log.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        builder.redirectInput(ProcessBuilder.Redirect.from(DEV_NULL));
        return builder.start();
    }

    static int status(ProcessBuilder builder, Path log, @Nullable Duration timeout) throws IOException {
        Process process = spawnLogged(builder, log);
        try {
            if (timeout == null) {
                return process.waitFor();
            }
            if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return process.exitValue();
            }
            process.destroyForcibly();
            process.waitFor();
            throw new AdapterException("process timed out after " + timeout.toSeconds() + "s\n" + tail(log, TAIL_CHARS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AdapterException("interrupted while waiting for " + builder.command().getFirst(), exception);
        }
    }

    record Output(int code, String stdout, String stderr) {}

    static Output output(ProcessBuilder builder) throws IOException {
        builder.redirectInput(ProcessBuilder.Redirect.from(DEV_NULL));
        Process process = builder.start();
        try (ExecutorService pumps = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stderr = pumps.submit(() -> process.getErrorStream().readAllBytes());
            byte[] stdout = process.getInputStream().readAllBytes();
            return new Output(
                    process.waitFor(),
                    new String(stdout, StandardCharsets.UTF_8),
                    new String(stderr.get(), StandardCharsets.UTF_8));
        } catch (ExecutionException exception) {
            throw new AdapterException("read stderr of " + builder.command().getFirst(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AdapterException("interrupted while waiting for " + builder.command().getFirst(), exception);
        }
    }

    static String tail(Path path, int limit) {
        try {
            return endSlice(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), limit);
        } catch (IOException exception) {
            return "";
        }
    }

    static String endSlice(String value, int limit) {
        int count = value.codePointCount(0, value.length());
        if (count <= limit) {
            return value;
        }
        return value.substring(value.offsetByCodePoints(0, count - limit));
    }

    static String prefix(String value, int limit) {
        int count = value.codePointCount(0, value.length());
        if (count <= limit) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, limit));
    }
}
