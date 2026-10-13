package com.cherrytest.shop.adapter;

import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

record TestRecord(String name, String status, long durationMs, @Nullable String message, List<ObjectNode> attachments) {
    TestRecord {
        attachments = List.copyOf(attachments);
    }

    ObjectNode json() {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("name", name);
        value.put("status", status);
        value.put("duration_ms", durationMs);
        value.putArray("attachments").addAll(attachments);
        if (message != null) {
            value.put("message", message);
        }
        return value;
    }
}
