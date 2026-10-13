package com.cherrytest.shop.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class ShopAdapterTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path directory;

    @Test
    void failsafe_cases_map_status() throws IOException {
        Path report = directory.resolve("TEST-ShopCheckoutIT.xml");
        Files.writeString(
                report,
                """
                <testsuite name="ShopCheckoutIT">
                  <testcase name="shop_project_cart_suite_adds_line_to_cart_test"/>
                  <testcase name="rejects"><failure message="boom"/></testcase>
                  <testcase name="waits"><skipped/></testcase>
                </testsuite>
                """,
                StandardCharsets.UTF_8);
        assertThat(JunitReport.records(report))
                .extracting(record -> record.name() + "=" + record.status())
                .containsExactly(
                        "shop_project_cart_suite_adds_line_to_cart_test=passed", "rejects=failed", "waits=skipped");
    }

    @Test
    void env_file_strips_quotes_and_comments() throws IOException {
        Path file = directory.resolve(".env");
        Files.writeString(
                file,
                "# comment\nSHOP_DB_USER=\"shop\"\nSHOP_DB_PASSWORD='secret'\nSHOP_DB_NAME=shop\n",
                StandardCharsets.UTF_8);
        Map<String, String> values = ShopAdapter.readEnvFile(file);
        assertThat(values)
                .containsEntry("SHOP_DB_USER", "shop")
                .containsEntry("SHOP_DB_PASSWORD", "secret")
                .containsEntry("SHOP_DB_NAME", "shop");
    }

    @Test
    void database_userinfo_is_percent_encoded() {
        assertThat(ShopAdapter.percentEncode("shop")).isEqualTo("shop");
        assertThat(ShopAdapter.percentEncode("a b")).isEqualTo("a%20b");
        assertThat(ShopAdapter.percentEncode("p@ss")).isEqualTo("p%40ss");
    }

    @Test
    void durations_truncate_toward_zero() {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        assertThat(ShopAdapter.wholeMillis(nodes.numberNode(1.9))).isEqualTo(1);
        assertThat(ShopAdapter.wholeMillis(nodes.numberNode(0.2))).isZero();
        assertThat(ShopAdapter.wholeMillis(nodes.numberNode(-4.0))).isZero();
        assertThat(ShopAdapter.wholeMillis(nodes.numberNode(12))).isEqualTo(12);
        assertThat(ShopAdapter.wholeMillis(nodes.nullNode())).isZero();
        assertThat(Processes.prefix("abcdef", 3)).isEqualTo("abc");
    }

    @Test
    void unsupported_phase_fails_before_building() {
        ShopAdapter adapter = new ShopAdapter(new Request("unit", directory, directory.resolve("out"), true, Map.of()));
        assertThatThrownBy(adapter::execute)
                .isInstanceOf(AdapterException.class)
                .hasMessageContaining("unsupported phase unit");
    }

    @Test
    void a_failed_case_fails_the_phase() {
        ShopAdapter adapter =
                new ShopAdapter(new Request("integration", directory, directory.resolve("out"), false, Map.of()));
        adapter.tests.add(new TestRecord("case", "failed", 4, null, List.of()));
        adapter.ok = true;
        ObjectNode response = adapter.response();
        assertThat(response.get("success").booleanValue()).isFalse();
        assertThat(response.get("tests").get(0).get("status").stringValue()).isEqualTo("failed");
    }

    @Test
    void server_lcov_lists_covered_and_missed_lines_per_source() throws IOException {
        assertThat(ServerCoverage.executionFiles(directory.resolve("missing"))).isEmpty();
        assertThat(JSON.writeValueAsString(ShopAdapter.failure("boom")))
                .isEqualTo("{\"protocol\":1,\"success\":false,\"tests\":[],\"coverage\":[],\"diagnostics\":[\"boom\"]}");
    }
}
