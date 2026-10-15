package com.zeroziba.orderstock.order;
import com.zeroziba.orderstock.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;

public record CartOrderRequest(@NotEmpty @Size(max=20) List<@NotNull @Valid CreateOrderRequest> items) {
    public List<CreateOrderRequest> normalized() {
        if(items==null || items.isEmpty() || items.size()>20)throw invalid();
        var ids=new HashSet<Long>();
        for(var item:items) if(item==null || item.productId()==null || item.productId()<1 || item.quantity()==null || item.quantity()<1 || !ids.add(item.productId()))throw invalid();
        return items.stream().sorted(Comparator.comparing(CreateOrderRequest::productId)).toList();
    }
    static String fingerprint(List<CreateOrderRequest> normalized) {
        StringBuilder value=new StringBuilder();
        for(var item:normalized)value.append(item.productId()).append(':').append(item.quantity()).append(';');
        return hash(value.toString());
    }
    static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","중복 없는 상품 1~20개와 양의 정수 수량을 입력하세요.");}
}
