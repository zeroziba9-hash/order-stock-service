package com.zeroziba.orderstock.product;

import com.zeroziba.orderstock.auth.AdminAccess;
import com.zeroziba.orderstock.common.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductSearch {
    public enum Sort { ID_ASC, PRICE_ASC, PRICE_DESC }
    public record Page<T>(List<T> products,int page,boolean hasMore) { }
    private final JdbcTemplate jdbc;
    private final AdminAccess admins;
    public ProductSearch(JdbcTemplate jdbc,AdminAccess admins) {this.jdbc=jdbc;this.admins=admins;}
    private String pattern(String q,int page,Integer lowStock) {
        if(q==null)q="";
        q=q.strip();
        if(q.length()>100 || page<0 || page>10000 || (lowStock!=null && (lowStock<0 || lowStock>1_000_000)))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","검색어·페이지·재고 기준을 확인하세요.");
        return "%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    }
    private String order(Sort sort) {
        return switch(sort){case ID_ASC -> "id ASC";case PRICE_ASC -> "price ASC,id ASC";case PRICE_DESC -> "price DESC,id ASC";};
    }
    @Transactional(readOnly=true)
    public Page<ProductResponse> search(String q,boolean available,Sort sort,int page) {
        var rows=jdbc.query("SELECT id,name,price,stock FROM products WHERE selling=TRUE AND name LIKE ? ESCAPE '!' AND (?=FALSE OR stock>0) ORDER BY "+order(sort)+" LIMIT 21 OFFSET ?",
            (row,index)->new ProductResponse(row.getLong("id"),row.getString("name"),row.getLong("price"),row.getInt("stock")),pattern(q,page,null),available,page*20);
        return new Page<>(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    @Transactional(readOnly=true)
    public Page<CatalogService.Item> admin(long admin,String q,Boolean selling,Integer lowStock,int page) {
        admins.require(admin);
        var rows=jdbc.query("SELECT * FROM products WHERE name LIKE ? ESCAPE '!' AND (? IS NULL OR selling=?) AND (? IS NULL OR stock<=?) ORDER BY id ASC LIMIT 21 OFFSET ?",
            (row,index)->new CatalogService.Item(row.getLong("id"),row.getString("name"),row.getLong("price"),row.getInt("stock"),row.getBoolean("selling"),row.getLong("catalog_revision")),
            pattern(q,page,lowStock),selling,selling,lowStock,lowStock,page*20);
        return new Page<>(rows.stream().limit(20).toList(),page,rows.size()>20);
    }
    @Transactional(readOnly=true)
    public CatalogService.Item one(long admin,long id) {
        admins.require(admin);
        var rows=jdbc.query("SELECT * FROM products WHERE id=?",(row,index)->new CatalogService.Item(row.getLong("id"),row.getString("name"),row.getLong("price"),row.getInt("stock"),row.getBoolean("selling"),row.getLong("catalog_revision")),id);
        if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND","상품을 찾을 수 없습니다.");
        return rows.get(0);
    }
}
