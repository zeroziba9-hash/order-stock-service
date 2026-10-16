package com.zeroziba.orderstock.product;

import com.zeroziba.orderstock.auth.AdminAccess;
import com.zeroziba.orderstock.common.ApiException;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductContentService {
    public record Content(String description,String specification,long revision) { }
    public record Update(@NotNull @Size(max=2000) String description,@NotNull @Size(max=1000) String specification,
                         @NotNull @PositiveOrZero Long revision) { }
    public record Detail(long id,String name,long price,int stock,String description,String specification,String imageUrl) { }
    public record Change(long changeId,long actorId,Content before,Content after,Instant createdAt) { }
    public record History(List<Change> changes,Long nextBeforeId) { }
    private final ProductRepository products;
    private final JdbcTemplate jdbc;
    private final AdminAccess admins;
    private final Clock clock;
    private final ProductImageService images;
    public ProductContentService(ProductRepository products,JdbcTemplate jdbc,AdminAccess admins,Clock clock,ProductImageService images) {
        this.products=products;this.jdbc=jdbc;this.admins=admins;this.clock=clock;this.images=images;
    }

    @Transactional(readOnly=true)
    public Detail detail(long id) {
        validateId(id);
        var product=products.findById(id).filter(Product::isSelling).orElseThrow(this::missing);
        var content=read(id);
        return new Detail(id,product.getName(),product.getPrice(),product.getStock(),content.description(),content.specification(),images.publicUrl(id));
    }

    @Transactional(readOnly=true)
    public Content get(long admin,long id) {
        admins.require(admin);requireProduct(id);return read(id);
    }

    @Transactional
    public Content update(long admin,long id,Update body) {
        admins.require(admin);requireProduct(id);
        if(body==null || body.description()==null || body.description().length()>2000 || body.specification()==null
                || body.specification().length()>1000 || body.revision()==null || body.revision()<0)
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","상품 설명·규격과 수정 버전을 확인하세요.");
        jdbc.update("INSERT INTO product_content(product_id) VALUES (?) ON DUPLICATE KEY UPDATE product_id=product_id",id);
        var before=jdbc.queryForObject("SELECT description,specification,revision FROM product_content WHERE product_id=? FOR UPDATE",
                (row,index)->new Content(row.getString(1),row.getString(2),row.getLong(3)),id);
        if(before.revision()!=body.revision())throw new ApiException(HttpStatus.CONFLICT,"CONTENT_CHANGED","다른 접속에서 상품 설명이 바뀌었어요. 새로고침 후 다시 수정하세요.");
        var description=body.description().strip();var specification=body.specification().strip();
        if(before.description().equals(description) && before.specification().equals(specification))return before;
        var after=new Content(description,specification,Math.incrementExact(before.revision()));
        jdbc.update("UPDATE product_content SET description=?,specification=?,revision=? WHERE product_id=?",description,specification,after.revision(),id);
        jdbc.update("""
                INSERT INTO product_content_changes(product_id,actor_id,before_description,after_description,
                before_specification,after_specification,before_revision,after_revision,created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,id,admin,before.description(),after.description(),before.specification(),after.specification(),before.revision(),after.revision(),LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        return after;
    }

    @Transactional(readOnly=true)
    public History history(long admin,long id,Long beforeId) {
        admins.require(admin);requireProduct(id);
        if(beforeId!=null && beforeId<1)throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","이력 커서를 확인하세요.");
        var rows=jdbc.query("SELECT * FROM product_content_changes WHERE product_id=? AND id<? ORDER BY id DESC LIMIT 21",
                (row,index)->new Change(row.getLong("id"),row.getLong("actor_id"),
                        new Content(row.getString("before_description"),row.getString("before_specification"),row.getLong("before_revision")),
                        new Content(row.getString("after_description"),row.getString("after_specification"),row.getLong("after_revision")),
                        row.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id,beforeId==null?Long.MAX_VALUE:beforeId);
        return new History(rows.stream().limit(20).toList(),rows.size()>20?rows.get(19).changeId():null);
    }
    private Content read(long id) {
        var rows=jdbc.query("SELECT description,specification,revision FROM product_content WHERE product_id=?",
                (row,index)->new Content(row.getString(1),row.getString(2),row.getLong(3)),id);
        return rows.isEmpty()?new Content("","",0):rows.get(0);
    }
    private void requireProduct(long id){validateId(id);if(!products.existsById(id))throw missing();}
    private void validateId(long id){if(id<1)throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","상품 ID를 확인하세요.");}
    private ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND","상품을 찾을 수 없거나 판매가 중지됐어요.");}
}
