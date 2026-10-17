package com.zeroziba.orderstock.payment.toss;

import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TossApiClient {
    enum Outcome { APPROVED, CANCELED, REJECTED, UNKNOWN, REVIEW }
    record Result(Outcome outcome, LocalDateTime at, LocalDateTime approvedAt, String paymentKey) {
        Result(Outcome outcome, LocalDateTime at) { this(outcome,at,outcome==Outcome.APPROVED?at:null,null); }
        static Result unknown() { return new Result(Outcome.UNKNOWN, null); }
    }
    private final TossSettings settings;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final Set<String> DECLINED = Set.of("REJECT_CARD_PAYMENT", "REJECT_CARD_COMPANY", "EXCEED_MAX_DAILY_PAYMENT_COUNT",
            "EXCEED_MAX_PAYMENT_AMOUNT", "INVALID_CARD_EXPIRATION", "INVALID_CARD_NUMBER", "INVALID_CARD_INSTALLMENT_PLAN");

    public TossApiClient(TossSettings settings) { this.settings = settings; }
    Result confirm(TossStore.Row row) {
        return exchange(row, "/v1/payments/confirm", Map.of("paymentKey", row.paymentKey(), "orderId", row.id(), "amount", row.amount()), row.confirmKey(), true);
    }
    Result cancel(TossStore.Row row) {
        return exchange(row, "/v1/payments/" + path(row.paymentKey()) + "/cancel", Map.of("cancelReason", "사용자 전액 환불 요청"), row.refundKey(), false);
    }
    Result lookup(TossStore.Row row) { return exchange(row, "/v1/payments/" + (row.paymentKey()==null?"orders/"+row.id():path(row.paymentKey())), null, null, false); }
    private String path(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private Result exchange(TossStore.Row row, String path, Object body, String key, boolean confirmation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("PG HTTP must run outside database transactions");
        try {
            var request = HttpRequest.newBuilder(settings.api.resolve(path)).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((settings.secretKey + ":").getBytes(StandardCharsets.UTF_8)))
                    .header("Accept", "application/json");
            if (body == null) request.GET();
            else request.header("Content-Type", "application/json").header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.body().length() > 65536) return Result.unknown();
            JsonNode data = mapper.readTree(response.body());
            if (response.statusCode() != 200) {
                if (confirmation && response.statusCode() == 400 && DECLINED.contains(data.path("code").asString("")))
                    return new Result(Outcome.REJECTED, null);
                return Result.unknown();
            }
            if (!row.id().equals(data.path("orderId").asString("")) || (row.paymentKey()!=null && !row.paymentKey().equals(data.path("paymentKey").asString(""))) || !data.path("paymentKey").asString("").matches("[A-Za-z0-9_.=-]{1,200}")
                    || !"KRW".equals(data.path("currency").asString("")) || !"NORMAL".equals(data.path("type").asString(""))
                    || !data.path("totalAmount").isIntegralNumber() || data.path("totalAmount").asLong() != row.amount()
                    || !Set.of("카드", "간편결제").contains(data.path("method").asString(""))
                    || !data.path("virtualAccount").isNull() && !data.path("virtualAccount").isMissingNode())
                return new Result(Outcome.REVIEW, null);
            String status = data.path("status").asString("");
            if (status.equals("DONE")) {
                if (!data.path("balanceAmount").isIntegralNumber() || data.path("balanceAmount").asLong()!=row.amount()) return new Result(Outcome.REVIEW,null);
                return new Result(Outcome.APPROVED, utc(data.path("approvedAt").asString()), utc(data.path("approvedAt").asString()), data.path("paymentKey").asString());
            }
            if (status.equals("CANCELED")) {
                long total = 0; LocalDateTime latest = null; Set<String> transactions = new HashSet<>();
                if (!data.path("cancels").isArray()) return new Result(Outcome.REVIEW, null);
                for (JsonNode cancel : data.path("cancels")) {
                    String transaction = cancel.path("transactionKey").asString("");
                    if (!cancel.path("cancelAmount").isIntegralNumber() || cancel.path("cancelAmount").asLong() <= 0
                            || !"DONE".equals(cancel.path("cancelStatus").asString("")) || transaction.isBlank() || !transactions.add(transaction))
                        return new Result(Outcome.REVIEW, null);
                    total = Math.addExact(total, cancel.path("cancelAmount").asLong());
                    LocalDateTime at = utc(cancel.path("canceledAt").asString());
                    if (latest == null || at.isAfter(latest)) latest = at;
                }
                if (total == row.amount() && data.path("balanceAmount").isIntegralNumber() && data.path("balanceAmount").asLong() == 0 && latest != null)
                    return new Result(Outcome.CANCELED, latest, utc(data.path("approvedAt").asString()), data.path("paymentKey").asString());
                return new Result(Outcome.REVIEW, null);
            }
            if (Set.of("ABORTED", "EXPIRED").contains(status)) return new Result(Outcome.REJECTED, null);
            if (status.equals("PARTIAL_CANCELED")) return new Result(Outcome.REVIEW, null);
            return Result.unknown();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return Result.unknown();
        } catch (Exception failure) {
            // Provider errors may include credentials, identifiers or personal data. Do not log them.
            return Result.unknown();
        }
    }
    private LocalDateTime utc(String value) { return OffsetDateTime.parse(value).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
}
