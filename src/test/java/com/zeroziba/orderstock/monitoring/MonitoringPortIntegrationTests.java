package com.zeroziba.orderstock.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles({"test", "observability"})
@AutoConfigureMetrics
class MonitoringPortIntegrationTests {
    @Autowired Environment environment;

    @Test
    void onlySeparateManagementPortAllowsScrapeAndExportsBoundedMetrics() throws Exception {
        int application = environment.getRequiredProperty("local.server.port", Integer.class);
        int management = environment.getRequiredProperty("local.management.port", Integer.class);
        assertThat(application).isNotEqualTo(management);
        var client = HttpClient.newHttpClient();
        var denied = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + application + "/actuator/prometheus"))
                .timeout(Duration.ofSeconds(10)).header("X-Forwarded-Port", Integer.toString(management)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(403);
        var scrape = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + management + "/actuator/prometheus"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("stockroom_order_events_total", "jvm_memory_used_bytes", "hikaricp_connections");
        assertThat(scrape.body()).doesNotContain("userId=", "orderId=", "email=", "requestKey=");
    }
}
