package com.zeroziba.orderstock.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.restdocs.headers.HeaderDocumentation.*;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.*;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.*;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.*;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;
import static org.springframework.restdocs.request.RequestDocumentation.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.zeroziba.orderstock.TestAccounts;
import com.zeroziba.orderstock.monitoring.RequestTracingFilter;
import com.zeroziba.orderstock.order.CreateOrderRequest;
import com.zeroziba.orderstock.order.OrderExpirationService;
import com.zeroziba.orderstock.order.OrderService;
import com.zeroziba.orderstock.product.Product;
import com.zeroziba.orderstock.product.ProductRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.snippet.Snippet;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** Real security filters, services and MySQL; no mocked response bodies. */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(RestDocumentationExtension.class)
class ApiDocumentationTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductRepository products;
    @Autowired private OrderService orders;
    @Autowired private OrderExpirationService expiration;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private RequestTracingFilter tracing;
    private MockMvc mvc;
    private MockHttpSession session;
    private String token;
    private long userId;
    private Product product;

    @BeforeEach
    void prepare(WebApplicationContext context, RestDocumentationContextProvider documentation) throws Exception {
        clean();
        var config = documentationConfiguration(documentation);
        config.uris().withScheme("http").withHost("localhost").withPort(18080);
        config.operationPreprocessors()
                .withRequestDefaults(new DocumentationSanitizer(), prettyPrint())
                .withResponseDefaults(new DocumentationSanitizer(), prettyPrint());
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilter(tracing).apply(springSecurity()).apply(config).build();
        userId = TestAccounts.create(jdbc, "reader@example.test");
        product = products.saveAndFlush(new Product("문서 테스트 머그컵", 15000, 100));
        session = login("reader@example.test", TestAccounts.PASSWORD, null);
        token = csrfToken(session);
    }

    @AfterEach
    void clean() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("order_stock_test");
        com.zeroziba.orderstock.TestDatabase.cleanInventory(jdbc);
        for (String table : List.of("inventory_adjustment_requests", "inventory_adjustments", "refund_attempts", "payment_attempts", "order_requests", "order_items",
                "purchase_orders", "products", "app_users")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void publicEndpoints() throws Exception {
        mvc.perform(get("/products")).andExpect(status().isOk()).andDo(document("products", responseFields(
                fieldWithPath("[].id").description("상품 ID"),
                fieldWithPath("[].name").description("현재 상품명"),
                fieldWithPath("[].price").description("원 단위 정수 가격"),
                fieldWithPath("[].stock").description("주문 가능한 현재 재고")), responseHeaders(
                headerWithName(RequestTracingFilter.HEADER).description("서버가 매 요청 새로 발급하는 추적 ID. 같은 ID로 서버 로그 조회"))));
        mvc.perform(get("/storefront/config")).andExpect(status().isOk()).andDo(document("storefront-config", responseFields(
                fieldWithPath("mockPayments").description("모의 결제 쓰기 허용 여부. prod에서는 false"),
                fieldWithPath("mockRefunds").description("모의 환불 쓰기 허용 여부. prod에서는 false"))));
    }

    @Test
    void registrationAndSessionLifecycle() throws Exception {
        var csrf = mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andDo(document("csrf", responseFields(
                fieldWithPath("headerName").description("쓰기 요청에 사용할 CSRF 헤더 이름"),
                fieldWithPath("token").description("현재 세션의 CSRF 토큰. 문서에서는 마스킹")))).andReturn();
        var anonymousSession = (MockHttpSession) csrf.getRequest().getSession(false);
        String anonymousToken = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/auth/register").session(anonymousSession).header("X-CSRF-TOKEN", anonymousToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"NewReader@example.test\",\"password\":\"DocPass123!\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.email").value("newreader@example.test"))
                .andDo(document("register", csrfHeader(), requestFields(
                        fieldWithPath("email").description("이메일, 최대 254자. 소문자로 정규화"),
                        fieldWithPath("password").description("8~72자, UTF-8 72바이트 이하, 공백만 사용 불가")),
                        responseFields(userFields())));
        MockHttpSession registeredSession = login("newreader@example.test", "DocPass123!", "login");
        mvc.perform(get("/auth/me").session(registeredSession)).andExpect(status().isOk())
                .andDo(document("me", responseFields(userFields())));
        mvc.perform(post("/auth/logout").session(registeredSession)
                        .header("X-CSRF-TOKEN", csrfToken(registeredSession)))
                .andExpect(status().isNoContent()).andDo(document("logout", csrfHeader()));
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized())
                .andDo(document("auth-required", responseFields(errorFields())));
    }

    @Test
    void orderCreationReplayDetailAndCancellation() throws Exception {
        var created = mvc.perform(write("/orders").header("Idempotency-Key", "order-demo-1").content(orderBody(2)))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "false"))
                .andDo(document("order-create", orderHeaders(), requestFields(createFields()), replayHeaders(),
                        responseFields(orderFields("")))).andReturn();
        long id = id(created, "orderId");
        mvc.perform(write("/orders").header("Idempotency-Key", "order-demo-1").content(orderBody(2)))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.orderId").value(id))
                .andDo(document("order-replay", replayHeaders(), responseFields(orderFields(""))));
        mvc.perform(read("/orders/{orderId}", id)).andExpect(status().isOk())
                .andDo(document("order-detail", orderPath(), responseFields(orderFields(""))));
        var cancelled = mvc.perform(write("/orders/{orderId}/cancel", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andDo(document("order-cancel", orderPath(), csrfHeader(), responseFields(orderFields("")))).andReturn();
        mvc.perform(write("/orders/{orderId}/cancel", id)).andExpect(status().isOk())
                .andExpect(content().json(cancelled.getResponse().getContentAsString()));
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(100);
    }

    @Test
    void accountSessionAndPasswordContracts() throws Exception {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO app_sessions VALUES (?,?,?,?,?,?,?)", java.util.UUID.randomUUID().toString(), session.getId(), now, now, 1800, now + 1_800_000, "reader@example.test");
        mvc.perform(get("/auth/sessions").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].current").value(true))
                .andDo(document("account-sessions", responseFields(
                        fieldWithPath("sessions").description("본인 유효 접속, 현재 접속 우선·최근 활동순, 최대 100개"),
                        fieldWithPath("sessions[].current").description("요청한 브라우저의 접속이면 true"),
                        fieldWithPath("sessions[].createdAt").description("세션 생성 시각. 로그인 이전 익명 세션 생성 시각일 수 있음"),
                        fieldWithPath("sessions[].lastAccessedAt").description("직전 저장된 최근 활동 시각"),
                        fieldWithPath("sessions[].expiresAt").description("직전 저장된 유휴 만료 시각. 활동 시 연장"),
                        fieldWithPath("truncated").description("표시 한도를 초과하면 true. 로그아웃은 전체에 적용"))));
        mvc.perform(post("/auth/sessions/logout-others").session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isNoContent()).andDo(document("account-logout-others", csrfHeader()));
        mvc.perform(post("/auth/password").session(session).header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"TestPass123!\",\"newPassword\":\"DocPass123!\"}"))
                .andExpect(status().isNoContent()).andDo(document("account-password", csrfHeader(), requestFields(
                        fieldWithPath("currentPassword").description("현재 비밀번호. 공백 구분, UTF-8 72바이트 이하"),
                        fieldWithPath("newPassword").description("새 비밀번호. 8~72자, UTF-8 72바이트 이하, 공백만 사용 불가, 현재 값과 달라야 함"))));
        assertThat(session.isInvalid()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_sessions WHERE PRINCIPAL_NAME=?", Integer.class, "reader@example.test")).isZero();
    }

    @Test
    void adminInventoryAdjustmentReplayAndHistory() throws Exception {
        mvc.perform(get("/admin/inventory-adjustments").session(session)).andExpect(status().isForbidden())
                .andDo(document("inventory-forbidden", responseFields(errorFields())));
        jdbc.update("UPDATE app_users SET user_role='ADMIN' WHERE id=?", userId);
        session = login("reader@example.test", TestAccounts.PASSWORD, null);
        token = csrfToken(session);
        mvc.perform(write("/admin/products/{productId}/inventory-adjustments", product.getId())
                        .header("Idempotency-Key", "inventory-demo-1").content("{\"delta\":5,\"reason\":\"추가 입고\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.beforeStock").value(100))
                .andExpect(jsonPath("$.afterStock").value(105))
                .andDo(document("inventory-adjust", pathParameters(parameterWithName("productId").description("조정할 상품 ID")),
                        requestHeaders(headerWithName("X-CSRF-TOKEN").description("현재 세션의 CSRF 토큰"),
                                headerWithName("Idempotency-Key").description("필수. 관리자별 요청 키, 주문 키와 동일한 형식")),
                        requestFields(fieldWithPath("delta").description("0이 아닌 32비트 정수. 증가 양수, 감소 음수"),
                                fieldWithPath("reason").description("1~200자 변경 사유, 양끝 공백 제거")),
                        responseHeaders(headerWithName("Idempotency-Replayed").description("새 조정은 false, 기존 조정 재응답은 true")), responseFields(inventoryFields(""))));
        mvc.perform(write("/admin/products/{productId}/inventory-adjustments", product.getId())
                        .header("Idempotency-Key", "inventory-demo-1").content("{\"delta\":5,\"reason\":\"추가 입고\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"))
                .andDo(document("inventory-replay", responseHeaders(headerWithName("Idempotency-Replayed").description("기존 조정 재응답은 true")), responseFields(inventoryFields(""))));
        var historyFields = new ArrayList<FieldDescriptor>();
        historyFields.add(fieldWithPath("adjustments").type(JsonFieldType.ARRAY).description("ID 내림차순, 최대 20건 수동 조정"));
        historyFields.addAll(List.of(inventoryFields("adjustments[].")));
        historyFields.add(fieldWithPath("nextBeforeId").type(JsonFieldType.NUMBER).optional().description("다음 페이지 커서. 마지막은 null"));
        mvc.perform(read("/admin/inventory-adjustments").queryParam("productId", product.getId().toString()))
                .andExpect(status().isOk()).andDo(document("inventory-history", queryParameters(
                        parameterWithName("productId").optional().description("특정 상품만 조회"),
                        parameterWithName("beforeId").optional().description("이 ID 미만의 이전 기록")),
                        responseFields(historyFields)));
    }

    @Test
    void orderWithoutKeyHasNoReplayGuarantee() throws Exception {
        mvc.perform(write("/orders").content(orderBody(1)))
                .andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andDo(document("order-without-key", csrfHeader(), requestFields(createFields()),
                        responseHeaders(headerWithName("Location").description("생성한 주문 상세 경로")),
                        responseFields(orderFields(""))));
        mvc.perform(write("/orders").content(orderBody(1))).andExpect(status().isCreated());
        assertThat(orders.findRecent(userId, null).orders()).hasSize(2);
    }

    @Test
    void catalogRegistrationSaleStopAndFullLedger() throws Exception {
        jdbc.update("UPDATE app_users SET user_role='ADMIN' WHERE id=?",userId);
        session=login("reader@example.test",TestAccounts.PASSWORD,null); token=csrfToken(session);
        var created=mvc.perform(write("/admin/products").header("Idempotency-Key","catalog-demo")
                        .content("{\"name\":\"새 컬렉션\",\"price\":2000}"))
                .andExpect(status().isCreated()).andDo(document("catalog-create",requestHeaders(headerWithName("X-CSRF-TOKEN").description("현재 CSRF 토큰"),headerWithName("Idempotency-Key").description("관리자별 상품 등록 키, 필수")),
                        requestFields(fieldWithPath("name").description("1~100자 상품명"),fieldWithPath("price").description("0~1000000000000원 정수")),
                        responseFields(fieldWithPath("productId").description("재고 0개로 등록한 상품 ID"),fieldWithPath("replayed").description("동일 등록 요청 재응답 여부"))))
                .andReturn();
        long productId=id(created,"productId");
        mvc.perform(put("/admin/products/{id}",productId).session(session).header("X-CSRF-TOKEN",token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"새 컬렉션\",\"price\":3000,\"selling\":false,\"revision\":0}"))
                .andExpect(status().isOk()).andDo(document("catalog-edit",pathParameters(parameterWithName("id").description("상품 ID")),csrfHeader(),
                        requestFields(fieldWithPath("name").description("상품명"),fieldWithPath("price").description("0~1000000000000원 정수"),
                                fieldWithPath("selling").description("false면 신규 주문 차단·공개 목록 제외"),fieldWithPath("revision").description("조회 당시 수정 버전. 불일치하면 409")),
                        responseFields(catalogFields(""))));
        mvc.perform(read("/admin/products")).andExpect(status().isOk())
                .andDo(document("catalog-list",responseFields(catalogFields("[]."))));
        var historyFields=new ArrayList<FieldDescriptor>();
        historyFields.add(fieldWithPath("changes").type(JsonFieldType.ARRAY).description("선택 상품의 변경, 최신 ID순 최대 20건"));
        historyFields.add(fieldWithPath("changes[].changeId").description("변경 ID"));
        historyFields.add(fieldWithPath("changes[].productId").description("상품 ID"));
        historyFields.add(fieldWithPath("changes[].actorId").description("변경한 관리자 ID, 이메일 미포함"));
        historyFields.add(fieldWithPath("changes[].createdAt").description("UTC 변경 시각"));
        for(String prefix:List.of("changes[].before.","changes[].after.")) {
            historyFields.add(fieldWithPath(prefix+"name").description("해당 시점 상품명"));
            historyFields.add(fieldWithPath(prefix+"price").description("원 단위 가격"));
            historyFields.add(fieldWithPath(prefix+"selling").description("판매 중 여부"));
            historyFields.add(fieldWithPath(prefix+"revision").description("수정 버전. 변경 전후 1 증가"));
        }
        historyFields.add(fieldWithPath("nextBeforeId").type(JsonFieldType.NUMBER).optional().description("이 ID 미만을 요청할 다음 커서, 마지막 null"));
        mvc.perform(read("/admin/products/{id}/changes",productId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].actorId").value(userId))
                .andDo(document("catalog-changes",pathParameters(parameterWithName("id").description("상품 ID")),
                        queryParameters(parameterWithName("beforeId").optional().description("양수 변경 ID 미만의 이전 변경")),
                        responseFields(historyFields)));
        mvc.perform(read("/admin/inventory-movements").queryParam("productId",Long.toString(productId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.consistent").value(true))
                .andDo(document("ledger-history",queryParameters(parameterWithName("productId").description("필수 상품 ID"),
                        parameterWithName("beforeId").optional().description("이 ID 미만의 이전 변동")),responseFields(
                        fieldWithPath("movements").type(JsonFieldType.ARRAY).description("최신순 최대 20개 재고 변동"),
                        fieldWithPath("movements[].movementId").description("변동 ID"),fieldWithPath("movements[].productId").description("상품 ID"),
                        fieldWithPath("movements[].productName").description("변동 당시 상품명"),fieldWithPath("movements[].type").description("BASELINE/ORDER/CANCEL/REFUND/EXPIRE/MANUAL"),
                        fieldWithPath("movements[].reference").description("시작점 opening 또는 주문/수동 조정 ID"),
                        fieldWithPath("movements[].actorId").type(JsonFieldType.NUMBER).optional().description("회원/관리자 ID. 시작점·자동 만료는 null"),
                        fieldWithPath("movements[].delta").description("수량 증감"),fieldWithPath("movements[].beforeStock").description("변경 전 수량"),
                        fieldWithPath("movements[].afterStock").description("변경 후 수량"),fieldWithPath("movements[].reason").description("사유"),
                        fieldWithPath("movements[].createdAt").description("UTC 변경 시각"),fieldWithPath("nextBeforeId").type(JsonFieldType.NUMBER).optional().description("다음 커서. 마지막 null"),
                        fieldWithPath("currentStock").description("현재 상품 재고"),fieldWithPath("recordedStock").description("전체 이력 수량 합계. 페이지와 무관"),
                        fieldWithPath("consistent").description("현재 수량과 기록 합계의 일치 여부"))));
    }

    private static FieldDescriptor[] catalogFields(String prefix) {
        return new FieldDescriptor[]{fieldWithPath(prefix+"id").description("상품 ID"),fieldWithPath(prefix+"name").description("상품명"),
                fieldWithPath(prefix+"price").description("가격"),fieldWithPath(prefix+"stock").description("현재 재고"),
                fieldWithPath(prefix+"selling").description("판매 여부"),fieldWithPath(prefix+"revision").description("상품 정보 수정 버전")};
    }

    @Test
    void invalidCredentialsUseGenericError() throws Exception {
        mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "reader@example.test").param("password", "WrongDocPass123!"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andDo(document("invalid-credentials", responseFields(errorFields())));
    }

    @Test
    void cursorPagesIncludeNextPageAndEmptyResult() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            ids.add(orders.create(userId, new CreateOrderRequest(product.getId(), 1)).orderId());
        }
        var first = mvc.perform(read("/orders")).andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(20))
                .andDo(document("order-list", responseFields(pageFields()))).andReturn();
        long cursor = id(first, "nextBeforeId");
        mvc.perform(read("/orders").queryParam("beforeId", Long.toString(cursor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.nextBeforeId").isEmpty())
                .andDo(document("order-next-page", queryParameters(
                        parameterWithName("beforeId").description("직전 응답의 nextBeforeId. 생략하면 최신 20건")),
                        responseFields(pageFields())));
        mvc.perform(read("/orders").queryParam("beforeId", ids.get(0).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orders").isEmpty())
                .andDo(document("order-empty-page", responseFields(
                        fieldWithPath("orders").description("주문이 없으면 빈 배열"),
                        fieldWithPath("nextBeforeId").type(JsonFieldType.NUMBER).optional().description("다음 페이지가 없으면 null"))));
    }

    @Test
    void paymentRefundSuccessFailureReplayAndHistory() throws Exception {
        long id = orders.create(userId, new CreateOrderRequest(product.getId(), 2)).orderId();
        for (String operation : List.of("payments", "refunds")) {
            String name = operation.equals("payments") ? "payment" : "refund";
            for (String result : List.of("FAILURE", "SUCCESS")) {
                String key = name + "-" + result.toLowerCase();
                String snippet = name + "-" + result.toLowerCase();
                var attempt = mvc.perform(write("/orders/{orderId}/" + operation, id)
                                .header("Idempotency-Key", key).content("{\"result\":\"" + result + "\"}"))
                        .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "false"))
                        .andExpect(jsonPath("$.status").value(result.equals("SUCCESS") ? "SUCCEEDED" : "FAILED"))
                        .andDo(document(snippet, orderPath(), attemptHeaders(), replayHeaders(), requestFields(
                                fieldWithPath("result").description("SUCCESS 또는 FAILURE. 로컬 모의 결과 선택")),
                                responseFields(attemptFields("", name)))).andReturn();
                mvc.perform(write("/orders/{orderId}/" + operation, id).header("Idempotency-Key", key)
                                .content("{\"result\":\"" + result + "\"}"))
                        .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"))
                        .andExpect(content().json(attempt.getResponse().getContentAsString()))
                        .andDo(document(snippet + "-replay", replayHeaders(), responseFields(attemptFields("", name))));
            }
            mvc.perform(read("/orders/{orderId}/" + operation, id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andDo(document(name + "-history", orderPath(), responseFields(attemptFields("[].", name))));
            if (operation.equals("payments")) {
                mvc.perform(read("/orders/{orderId}", id)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("PAID"))
                        .andDo(document("order-paid", responseFields(orderFields(""))));
                mvc.perform(write("/orders/{orderId}/cancel", id)).andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("REFUND_REQUIRED"))
                        .andDo(document("refund-required", responseFields(errorFields())));
            }
        }
        mvc.perform(read("/orders/{orderId}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andDo(document("order-refunded", responseFields(orderFields(""))));
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(100);
    }

    @Test
    void deadlineRejectsPaymentBeforeWorkerAndExposesExpiredOrder() throws Exception {
        long id = orders.create(userId, new CreateOrderRequest(product.getId(), 1)).orderId();
        LocalDateTime deadline = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> entityManager.createQuery(
                        "update PurchaseOrder o set o.createdAt=:created, o.expiresAt=:deadline where o.id=:id")
                .setParameter("created", deadline.minusMinutes(30)).setParameter("deadline", deadline)
                .setParameter("id", id).executeUpdate());
        mvc.perform(write("/orders/{orderId}/payments", id).header("Idempotency-Key", "late-payment")
                        .content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_EXPIRED"))
                .andDo(document("order-expired-error", responseFields(errorFields())));
        assertThat(expiration.expireDueOrders()).isEqualTo(1);
        mvc.perform(read("/orders/{orderId}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED")).andExpect(jsonPath("$.expiredAt").isString())
                .andDo(document("order-expired", responseFields(orderFields(""))));
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(100);
    }

    @Test
    void validationCsrfOwnershipAndConflictingRequests() throws Exception {
        mvc.perform(post("/orders").session(session).contentType(MediaType.APPLICATION_JSON).content(orderBody(1)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andDo(document("csrf-rejected", responseFields(errorFields())));
        mvc.perform(write("/orders").content(orderBody(0))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andDo(document("invalid-request", responseFields(errorFields())));
        mvc.perform(write("/orders").content(orderBody(101))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andDo(document("insufficient-stock", responseFields(errorFields())));
        long other = TestAccounts.create(jdbc, "other-reader@example.test");
        long otherOrder = orders.create(other, new CreateOrderRequest(product.getId(), 1)).orderId();
        mvc.perform(read("/orders/{orderId}", otherOrder)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andDo(document("order-not-found", responseFields(errorFields())));
        mvc.perform(write("/orders").header("Idempotency-Key", "same-key").content(orderBody(1)))
                .andExpect(status().isCreated());
        mvc.perform(write("/orders").header("Idempotency-Key", "same-key").content(orderBody(2)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andDo(document("idempotency-conflict", responseFields(errorFields())));
        mvc.perform(write("/orders/{orderId}/payments", otherOrder).content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"))
                .andDo(document("missing-key", responseFields(errorFields())));
    }

    private MockHttpSession login(String email, String password, String snippet) throws Exception {
        var csrf = mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        var loginSession = (MockHttpSession) csrf.getRequest().getSession(false);
        String loginToken = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        var login = mvc.perform(post("/auth/login").session(loginSession).header("X-CSRF-TOKEN", loginToken)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        // REST Docs serializes these as a form body. formField() also sets content,
                        // which MockMvcRequestConverter interprets as query parameters in 4.0.1.
                        .param("email", email).param("password", password))
                .andExpect(status().isOk());
        if (snippet != null) {
            login.andDo(document(snippet, csrfHeader(), formParameters(
                    parameterWithName("email").description("가입 이메일"),
                    parameterWithName("password").description("비밀번호. 문서에서는 마스킹")), responseFields(userFields())));
        }
        return (MockHttpSession) login.andReturn().getRequest().getSession(false);
    }

    private String csrfToken(MockHttpSession current) throws Exception {
        var result = mvc.perform(get("/auth/csrf").session(current)).andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private MockHttpServletRequestBuilder read(String path, Object... ids) {
        return get(path, ids).session(session);
    }

    private MockHttpServletRequestBuilder write(String path, Object... ids) {
        return post(path, ids).session(session).header("X-CSRF-TOKEN", token).contentType(MediaType.APPLICATION_JSON);
    }

    private String orderBody(int quantity) {
        return "{\"productId\":" + product.getId() + ",\"quantity\":" + quantity + "}";
    }

    private static long id(MvcResult result, String field) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$." + field)).longValue();
    }

    private static Snippet csrfHeader() {
        return requestHeaders(headerWithName("X-CSRF-TOKEN").description("현재 세션에서 /auth/csrf로 얻은 토큰"));
    }

    private static Snippet orderHeaders() {
        return requestHeaders(headerWithName("X-CSRF-TOKEN").description("현재 세션의 CSRF 토큰"),
                headerWithName("Idempotency-Key").optional().description("회원별 주문 키. 생략 가능하지만 재시도 안전성을 위해 권장"));
    }

    private static Snippet attemptHeaders() {
        return requestHeaders(headerWithName("X-CSRF-TOKEN").description("현재 세션의 CSRF 토큰"),
                headerWithName("Idempotency-Key").description("주문별 결제/환불 키. 필수"));
    }

    private static Snippet replayHeaders() {
        return responseHeaders(headerWithName("Location").description("주문 상세 또는 해당 결제/환불 이력 경로"),
                headerWithName("Idempotency-Replayed").description("신규 false (201), 기존 결과 재반환 true (200)"));
    }

    private static Snippet orderPath() {
        return pathParameters(parameterWithName("orderId").description("본인 주문 ID, 양의 정수"));
    }

    private static FieldDescriptor[] userFields() {
        return new FieldDescriptor[]{fieldWithPath("userId").description("회원 ID"), fieldWithPath("email").description("정규화된 이메일"),
                fieldWithPath("role").description("USER 또는 ADMIN. 회원가입은 항상 USER")};
    }

    private static FieldDescriptor[] inventoryFields(String prefix) {
        return new FieldDescriptor[]{fieldWithPath(prefix + "adjustmentId").description("변경 이력 ID"),
                fieldWithPath(prefix + "adminId").description("실행한 관리자 회원 ID"),
                fieldWithPath(prefix + "productId").description("상품 ID"),
                fieldWithPath(prefix + "productName").description("조정 당시 상품명"),
                fieldWithPath(prefix + "delta").description("조정 수량"),
                fieldWithPath(prefix + "beforeStock").description("조정 직전 잠금 상태의 재고"),
                fieldWithPath(prefix + "afterStock").description("조정 직후 재고. 재응답에서는 최초 결과"),
                fieldWithPath(prefix + "reason").description("변경 사유"),
                fieldWithPath(prefix + "createdAt").description("변경 시각, UTC ISO-8601")};
    }

    private static FieldDescriptor[] createFields() {
        return new FieldDescriptor[]{fieldWithPath("productId").description("상품 ID, 양의 정수"),
                fieldWithPath("quantity").description("주문 수량, 양의 정수. 가격은 서버에서 결정")};
    }

    private static FieldDescriptor[] errorFields() {
        return new FieldDescriptor[]{fieldWithPath("code").description("클라이언트 분기에 사용할 오류 코드"),
                fieldWithPath("message").description("사용자 안내 문구. 문구 자체를 분기 조건으로 사용하지 않음")};
    }

    private static FieldDescriptor[] orderFields(String prefix) {
        return new FieldDescriptor[]{
                fieldWithPath(prefix + "orderId").description("주문 ID"),
                fieldWithPath(prefix + "status").description("CREATED / PAID / CANCELLED / REFUNDED / EXPIRED"),
                fieldWithPath(prefix + "totalAmount").description("원 단위 주문 총액, 서버 가격 × 수량"),
                fieldWithPath(prefix + "createdAt").description("주문 생성 시각, UTC ISO-8601"),
                nullableTime(prefix + "cancelledAt", "취소 시각"), nullableTime(prefix + "paidAt", "결제 성공 시각"),
                nullableTime(prefix + "refundedAt", "환불 성공 시각"),
                fieldWithPath(prefix + "expiresAt").description("생성 시 고정한 결제 마감, UTC ISO-8601"),
                nullableTime(prefix + "expiredAt", "자동 만료 처리 시각"),
                fieldWithPath(prefix + "items").description("주문 항목 배열. 현재 단일 상품"),
                fieldWithPath(prefix + "items[].productId").description("상품 ID"),
                fieldWithPath(prefix + "items[].productName").description("주문 당시 상품명"),
                fieldWithPath(prefix + "items[].unitPrice").description("주문 당시 원 단위 가격"),
                fieldWithPath(prefix + "items[].quantity").description("주문 수량")};
    }

    private static FieldDescriptor nullableTime(String path, String description) {
        return fieldWithPath(path).type(JsonFieldType.STRING).optional().description(description + ", UTC ISO-8601, 미발생 시 null");
    }

    private static FieldDescriptor[] pageFields() {
        var fields = new ArrayList<FieldDescriptor>();
        fields.add(fieldWithPath("orders").description("본인 주문, ID 내림차순, 최대 20건"));
        fields.addAll(List.of(orderFields("orders[].")));
        fields.add(fieldWithPath("nextBeforeId").type(JsonFieldType.NUMBER).optional().description("다음 조회의 beforeId, 마지막 페이지면 null"));
        return fields.toArray(FieldDescriptor[]::new);
    }

    private static FieldDescriptor[] attemptFields(String prefix, String kind) {
        var fields = new ArrayList<FieldDescriptor>();
        fields.add(fieldWithPath(prefix + kind + "Id").description("시도 이력 ID"));
        fields.add(fieldWithPath(prefix + "orderId").description("본인 주문 ID"));
        if (kind.equals("refund")) {
            fields.add(fieldWithPath(prefix + "paymentId").description("환불 대상 성공 결제 ID"));
        }
        fields.add(fieldWithPath(prefix + "amount").description("서버가 계산한 원 단위 전액"));
        fields.add(fieldWithPath(prefix + "status").description("SUCCEEDED 또는 FAILED. 실패도 정상 이력으로 저장"));
        fields.add(fieldWithPath(prefix + "createdAt").description("시도 시각, UTC ISO-8601"));
        return fields.toArray(FieldDescriptor[]::new);
    }
}
