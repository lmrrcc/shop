package com.cherrytest.shop.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.jspecify.annotations.Nullable;

final class JunitReport {
    private JunitReport() {}

    static List<TestRecord> records(Path report) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        List<TestRecord> records = new ArrayList<>();
        try (InputStream input = Files.newInputStream(report)) {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            String name = null;
            String status = "passed";
            String message = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "testcase" -> {
                            name = reader.getAttributeValue(null, "name");
                            status = "passed";
                            message = null;
                        }
                        case "failure", "error" -> {
                            status = "failed";
                            message = attribute(reader, "message");
                        }
                        case "skipped" -> status = "skipped";
                        default -> {}
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && reader.getLocalName().equals("testcase")
                        && name != null) {
                    records.add(new TestRecord(name, status, 0, message, List.of()));
                    name = null;
                }
            }
        } catch (IOException | XMLStreamException exception) {
            throw new AdapterException("read " + report + ": " + exception.getMessage(), exception);
        }
        return records;
    }

    private static @Nullable String attribute(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        return value == null || value.isEmpty() ? null : Processes.prefix(value, 4000);
    }
}
