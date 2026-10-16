package com.zeroziba.orderstock.product;

import com.zeroziba.orderstock.auth.AdminAccess;
import com.zeroziba.orderstock.auth.AuthenticatedUser;
import com.zeroziba.orderstock.common.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class CatalogContentStatus {
    public record Status(long id,boolean image,boolean description,boolean specification) { }
    private final JdbcTemplate jdbc;private final AdminAccess admins;
    public CatalogContentStatus(JdbcTemplate jdbc,AdminAccess admins){this.jdbc=jdbc;this.admins=admins;}
    @GetMapping("/admin/products/content-status") @Transactional(readOnly=true)
    public List<Status> status(@AuthenticationPrincipal AuthenticatedUser user,@RequestParam List<Long> ids){
        admins.require(user.getId());
        if(ids.isEmpty()||ids.size()>20||ids.stream().anyMatch(id->id==null||id<1)||new HashSet<>(ids).size()!=ids.size())
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","콘텐츠를 확인할 상품 ID를 1~20개 선택하세요.");
        return jdbc.query("SELECT p.id,i.image_hash IS NOT NULL,COALESCE(LENGTH(TRIM(c.description)),0)>0,COALESCE(LENGTH(TRIM(c.specification)),0)>0 FROM products p LEFT JOIN product_images i ON i.product_id=p.id LEFT JOIN product_content c ON c.product_id=p.id WHERE p.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY p.id",
            (row,index)->new Status(row.getLong(1),row.getBoolean(2),row.getBoolean(3),row.getBoolean(4)),ids.toArray());
    }
}
