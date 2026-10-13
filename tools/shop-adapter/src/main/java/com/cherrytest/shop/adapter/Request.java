package com.cherrytest.shop.adapter;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

record Request(String phase, Path root, Path output, boolean coverage, Map<String, String> env) {
    Request {
        env = new TreeMap<>(env);
    }

    static Request parse(JsonNode node) {
        JsonNode phase = node.get("phase");
        JsonNode root = node.get("root");
        JsonNode output = node.get("output");
        if (phase == null || root == null || output == null) {
            throw new AdapterException("missing field `phase`, `root` or `output` in the adapter request");
        }
        JsonNode coverage = node.get("coverage");
        Map<String, String> env = new TreeMap<>();
        JsonNode requested = node.get("env");
        if (requested != null && requested.isObject()) {
            requested.properties().forEach(entry -> env.put(entry.getKey(), entry.getValue().asString()));
        }
        return new Request(
                phase.asString(),
                Path.of(root.asString()),
                Path.of(output.asString()),
                coverage == null || coverage.asBoolean(),
                env);
    }
}
