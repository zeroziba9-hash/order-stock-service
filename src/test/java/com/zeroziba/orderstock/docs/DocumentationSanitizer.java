package com.zeroziba.orderstock.docs;

import static org.springframework.restdocs.operation.preprocess.Preprocessors.modifyHeaders;

import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.restdocs.operation.OperationRequest;
import org.springframework.restdocs.operation.OperationResponse;
import org.springframework.restdocs.operation.preprocess.ContentModifyingOperationPreprocessor;
import org.springframework.restdocs.operation.preprocess.OperationPreprocessorAdapter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Documentation only. Never modifies the live request, response or assertions. */
final class DocumentationSanitizer extends OperationPreprocessorAdapter {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ContentModifyingOperationPreprocessor content = new ContentModifyingOperationPreprocessor(this::sanitize);

    @Override
    public OperationRequest preprocess(OperationRequest request) {
        var headers = modifyHeaders().remove("Authorization");
        if (request.getHeaders().containsHeader("Cookie")) {
            headers.set("Cookie", "JSESSIONID=<session-id>");
        }
        if (request.getHeaders().containsHeader("X-CSRF-TOKEN")) {
            headers.set("X-CSRF-TOKEN", "<csrf-token>");
        }
        return content.preprocess(headers.preprocess(request));
    }

    @Override
    public OperationResponse preprocess(OperationResponse response) {
        return content.preprocess(modifyHeaders().remove("Set-Cookie").preprocess(response));
    }

    byte[] sanitize(byte[] original, MediaType type) {
        if (original.length == 0 || type == null) {
            return original;
        }
        if (MediaType.APPLICATION_JSON.isCompatibleWith(type)) {
            // JSON is UTF-8 even on a Windows Java 17 VM whose default charset is not UTF-8.
            var json = mapper.readTree(original);
            if (json instanceof ObjectNode object) {
                for (String field : new String[]{"password", "token"}) {
                    if (object.has(field)) {
                        object.put(field, "<redacted>");
                    }
                }
            }
            return mapper.writeValueAsBytes(json);
        }
        if (MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(type)) {
            String form = new String(original, StandardCharsets.UTF_8);
            return form.replaceAll("(^|&)(password=)[^&]*", "$1$2%3Credacted%3E").getBytes(StandardCharsets.UTF_8);
        }
        return original;
    }
}
