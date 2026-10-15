package com.zeroziba.orderstock.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;

public record DateRange(LocalDateTime from, LocalDateTime until) {
    public static DateRange of(LocalDate from, LocalDate to) {
        if ((from!=null && (from.getYear()<1970 || from.getYear()>9998))
                || (to!=null && (to.getYear()<1970 || to.getYear()>9998)) || (from!=null && to!=null && from.isAfter(to)))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","조회 시작일과 종료일을 확인하세요.");
        ZoneId zone=ZoneId.of("Asia/Seoul");
        return new DateRange(from==null?null:LocalDateTime.ofInstant(from.atStartOfDay(zone).toInstant(),ZoneOffset.UTC),
                to==null?null:LocalDateTime.ofInstant(to.plusDays(1).atStartOfDay(zone).toInstant(),ZoneOffset.UTC));
    }
}
