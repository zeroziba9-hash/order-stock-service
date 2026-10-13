package com.zeroziba.orderstock.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.*;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.headers.HeaderDocumentation.*;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;
import com.zeroziba.orderstock.TestAccounts;
import com.zeroziba.orderstock.docs.DocumentationSanitizer;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties={"app.auth.limits.enabled=true", "app.auth.limits.login-attempts=8",
        "app.auth.limits.registration-attempts=2", "app.auth.limits.failed-logins=2"})
@ActiveProfiles("test")
@ExtendWith(RestDocumentationExtension.class)
class AuthLimitIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry metrics;
    private static final AtomicInteger CLIENT = new AtomicInteger();
    private String client;
    private MockMvc mvc;
    @BeforeEach void prepare(WebApplicationContext context,RestDocumentationContextProvider docs) {
        clean(); client="203.0.113."+CLIENT.incrementAndGet();
        TestAccounts.create(jdbc,"limit@example.test");
        var documentation=documentationConfiguration(docs);
        documentation.operationPreprocessors().withRequestDefaults(new DocumentationSanitizer(),prettyPrint())
                .withResponseDefaults(new DocumentationSanitizer(),prettyPrint());
        documentation.uris().withScheme("http").withHost("localhost").withPort(18080);
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).apply(documentation).build();
    }
    @AfterEach void clean() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("order_stock_test");
        com.zeroziba.orderstock.TestDatabase.cleanInventory(jdbc);
        for(String table:List.of("refund_attempts","payment_attempts","order_requests","order_items",
                "purchase_orders","products","app_users")) jdbc.update("DELETE FROM "+table);
    }
    private MockHttpServletRequestBuilder login(String email,String password) {
        return post("/auth/login").with(csrf()).with(request->{request.setRemoteAddr(client);return request;})
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("email",email).param("password",password);
    }
    private MockHttpServletRequestBuilder register() {
        return post("/auth/register").with(csrf()).with(request->{request.setRemoteAddr(client);return request;})
                .contentType(MediaType.APPLICATION_JSON).content("{}");
    }
    @Test void failedLoginsReturnDocumented429WithRetryAfterAndNoAccountLeak() throws Exception {
        mvc.perform(login("limit@example.test","wrong")).andExpect(status().isUnauthorized());
        mvc.perform(login("LIMIT@EXAMPLE.TEST","wrong")).andExpect(status().isUnauthorized());
        var result=mvc.perform(login("limit@example.test",TestAccounts.PASSWORD))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("AUTH_RATE_LIMITED"))
                .andExpect(header().string("Cache-Control","no-store"))
                .andDo(document("auth-rate-limited",responseHeaders(
                        headerWithName("Retry-After").description("다시 요청하기까지 기다릴 정수 초")),
                        responseFields(fieldWithPath("code").description("AUTH_RATE_LIMITED"),
                                fieldWithPath("message").description("계정 존재 여부와 무관한 공통 안내"))))
                .andReturn().getResponse();
        assertThat(Long.parseLong(result.getHeader("Retry-After"))).isBetween(1L,300L);
        assertThat(result.getContentAsString()).doesNotContain("limit@example.test",client,TestAccounts.PASSWORD);
    }
    @Test void unknownAccountAndExistingAccountReceiveSameLimitResponse() throws Exception {
        for(String email:List.of("limit@example.test","missing@example.test")) {
            mvc.perform(login(email,"wrong")).andExpect(status().isUnauthorized());
            mvc.perform(login(email,"wrong")).andExpect(status().isUnauthorized());
        }
        var existing=mvc.perform(login("limit@example.test","wrong")).andExpect(status().isTooManyRequests()).andReturn().getResponse();
        var missing=mvc.perform(login("missing@example.test","wrong")).andExpect(status().isTooManyRequests()).andReturn().getResponse();
        assertThat(existing.getContentAsString()).isEqualTo(missing.getContentAsString());
    }
    @Test void rotatingEmailCannotBypassClientBudgetAndForwardedHeadersAreIgnored() throws Exception {
        for(int i=0;i<8;i++) mvc.perform(login("missing"+i+"@example.test","wrong")
                .header("X-Forwarded-For","192.0.2."+i).header(AuthClientAddress.HEADER,"192.0.2."+i))
                .andExpect(status().isUnauthorized());
        mvc.perform(login("another@example.test","wrong").header("X-Forwarded-For","192.0.2.99")
                .header(AuthClientAddress.HEADER,"192.0.2.99")).andExpect(status().isTooManyRequests());
        client="198.51.100.1";
        mvc.perform(login("limit@example.test",TestAccounts.PASSWORD)).andExpect(status().isOk());
    }
    @Test void csrfFailureDoesNotConsumeBudgetAndPublicReadsRemainAvailable() throws Exception {
        for(int i=0;i<12;i++) mvc.perform(post("/auth/login").with(request->{request.setRemoteAddr(client);return request;})
                .param("email","limit@example.test").param("password","wrong")).andExpect(status().isForbidden());
        mvc.perform(login("limit@example.test",TestAccounts.PASSWORD)).andExpect(status().isOk());
        mvc.perform(get("/auth/csrf")).andExpect(status().isOk());
        mvc.perform(get("/products")).andExpect(status().isOk());
    }
    @Test void registrationValidationAndDuplicatesCountButLoginBudgetIsIndependent() throws Exception {
        mvc.perform(register()).andExpect(status().isBadRequest());
        mvc.perform(register().content("{\"email\":\"limit@example.test\",\"password\":\"OtherPass123!\"}"))
                .andExpect(status().isConflict());
        mvc.perform(register()).andExpect(status().isTooManyRequests());
        mvc.perform(login("limit@example.test",TestAccounts.PASSWORD)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_users",Integer.class)).isEqualTo(1);
    }
    @Test void metricsCountOneOutcomePerRequestWithOnlyFixedLabels() throws Exception {
        double failures=counter("failure"),successes=counter("success"),limited=counter("rate_limited");
        mvc.perform(login("limit@example.test","wrong")).andExpect(status().isUnauthorized());
        mvc.perform(login("limit@example.test",TestAccounts.PASSWORD)).andExpect(status().isOk());
        mvc.perform(login("limit@example.test","wrong")).andExpect(status().isUnauthorized());
        mvc.perform(login("limit@example.test","wrong")).andExpect(status().isUnauthorized());
        mvc.perform(login("limit@example.test","wrong")).andExpect(status().isTooManyRequests());
        assertThat(counter("failure")-failures).isEqualTo(3);
        assertThat(counter("success")-successes).isEqualTo(1);
        assertThat(counter("rate_limited")-limited).isEqualTo(1);
        for(var meter:metrics.getMeters()) if(meter.getId().getName().startsWith("stockroom.auth"))
            assertThat(meter.getId().getTags().toString()).doesNotContain("@",client,"email","password");
    }
    private double counter(String outcome) { return metrics.get("stockroom.auth.requests")
            .tags("endpoint","login","outcome",outcome).counter().count(); }

    @Test void concurrentHttpLoginWaveHonorsInflightCredentialLimit() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(16);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for(int i=0;i<32;i++) futures.add(pool.submit(()-> {
                start.await(); return mvc.perform(login("limit@example.test","wrong"))
                        .andReturn().getResponse().getStatus();
            }));
            start.countDown(); int failures=0,limited=0;
            for(var future:futures) {
                int result=future.get(20,java.util.concurrent.TimeUnit.SECONDS);
                if(result==401) failures++; else if(result==429) limited++;
                else throw new AssertionError("Unexpected auth status: "+result);
            }
            assertThat(failures).isEqualTo(2); assertThat(limited).isEqualTo(30);
        } finally {
            start.countDown(); pool.shutdownNow();
            assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }
}
