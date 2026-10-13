package com.cherrytest.shop.adapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

final class PlaywrightReport {
    private static final int MESSAGE_LIMIT = 4000;

    private PlaywrightReport() {}

    static void collect(@Nullable JsonNode suites, Path output, List<TestRecord> tests) {
        if (suites == null || !suites.isArray()) {
            return;
        }
        for (JsonNode suite : suites) {
            collect(suite.get("suites"), output, tests);
            JsonNode specs = suite.get("specs");
            if (specs == null || !specs.isArray()) {
                continue;
            }
            for (JsonNode spec : specs) {
                tests.add(record(spec, output, tests.size()));
            }
        }
    }

    private static TestRecord record(JsonNode spec, Path output, int index) {
        List<JsonNode> results = new ArrayList<>();
        JsonNode cases = spec.get("tests");
        if (cases != null && cases.isArray()) {
            for (JsonNode found : cases) {
                JsonNode caseResults = found.get("results");
                if (caseResults != null && caseResults.isArray()) {
                    caseResults.forEach(results::add);
                }
            }
        }
        JsonNode result = results.isEmpty() ? JsonNodeFactory.instance.objectNode() : results.getLast();
        JsonNode statusNode = result.get("status");
        String status = statusNode == null ? "failed" : switch (statusNode.asString()) {
            case "passed" -> "passed";
            case "skipped" -> "skipped";
            default -> "failed";
        };
        JsonNode title = spec.get("title");
        String name = title == null || title.asString().isEmpty() ? "unnamed" : title.asString();
        JsonNode error = result.at("/error/message");
        String message = error.isMissingNode() || error.asString().isEmpty()
                ? null
                : Processes.prefix(error.asString(), MESSAGE_LIMIT);
        return new TestRecord(name, status, ShopAdapter.wholeMillis(result.get("duration")), message,
                attachments(result.get("attachments"), output, index));
    }

    private static List<ObjectNode> attachments(@Nullable JsonNode found, Path output, int testIndex) {
        List<ObjectNode> attachments = new ArrayList<>();
        if (found == null || !found.isArray()) {
            return attachments;
        }
        int index = 0;
        for (JsonNode attachment : found) {
            int position = index++;
            JsonNode pathNode = attachment.get("path");
            if (pathNode == null) {
                continue;
            }
            Path source = Path.of(pathNode.asString());
            if (!Files.isRegularFile(source)) {
                continue;
            }
            Path fileName = source.getFileName();
            String name = fileName == null ? "attachment" : fileName.toString();
            Path destination = output.resolve("attachments").resolve(testIndex + "-" + position + "-" + name);
            try {
                Files.createDirectories(output.resolve("attachments"));
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                continue;
            }
            JsonNode label = attachment.get("name");
            JsonNode contentType = attachment.get("contentType");
            ObjectNode entry = JsonNodeFactory.instance.objectNode();
            entry.put("name", label == null || label.asString().isEmpty() ? String.valueOf(destination.getFileName()) : label.asString());
            entry.put("path", destination.toString());
            entry.put(
                    "media_type",
                    contentType == null || contentType.asString().isEmpty()
                            ? "application/octet-stream"
                            : contentType.asString());
            attachments.add(entry);
        }
        return attachments;
    }
}
