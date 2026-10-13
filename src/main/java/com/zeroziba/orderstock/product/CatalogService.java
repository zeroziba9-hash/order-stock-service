package com.zeroziba.orderstock.product;

import com.zeroziba.orderstock.auth.AdminAccess;
import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.inventory.InventoryLedger;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    public record Create(@NotBlank @Size(max=100) String name,@NotNull @Min(0) @Max(1000000000000L) Long price) { }
    public record Edit(@NotBlank @Size(max=100) String name,@NotNull @Min(0) @Max(1000000000000L) Long price,
            @NotNull Boolean selling,@NotNull @Min(0) Long revision) { }
    public record Item(long id,String name,long price,int stock,boolean selling,long revision) {
        static Item from(Product p) { return new Item(p.getId(),p.getName(),p.getPrice(),p.getStock(),p.isSelling(),p.getCatalogRevision()); }
    }
    public record Created(long productId,boolean replayed) { }
    private final ProductRepository products;
    private final AdminAccess admins;
    private final InventoryLedger ledger;
    private final JdbcTemplate jdbc;
    public CatalogService(ProductRepository products,AdminAccess admins,InventoryLedger ledger,JdbcTemplate jdbc) {
        this.products=products; this.admins=admins; this.ledger=ledger; this.jdbc=jdbc;
    }
    @Transactional(readOnly=true)
    public List<Item> list(long admin) { admins.require(admin); return products.findAllByOrderByIdAsc().stream().map(Item::from).toList(); }
    @Transactional
    public Created create(long admin,String key,Create body) {
        admins.require(admin);
        if (key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_IDEMPOTENCY_KEY","유효한 요청 키가 필요합니다.");
        validate(body.name(),body.price());
        String name=body.name().strip();
        jdbc.update("""
                INSERT INTO catalog_creation_requests(admin_id,request_key,product_name,price) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE request_key=request_key
                """,admin,key,name,body.price());
        var reservation=jdbc.queryForMap("SELECT product_name,price,product_id FROM catalog_creation_requests WHERE admin_id=? AND request_key=? FOR UPDATE",admin,key);
        if (!name.equals(reservation.get("product_name")) || body.price().longValue()!=((Number)reservation.get("price")).longValue())
            throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_REUSED","다른 상품 등록에 사용한 키입니다.");
        if (reservation.get("product_id")!=null) return new Created(((Number)reservation.get("product_id")).longValue(),true);
        var product=products.saveAndFlush(new Product(name,body.price(),0));
        ledger.record(product,0,InventoryLedger.Type.BASELINE,"opening",admin,"상품 등록");
        jdbc.update("UPDATE catalog_creation_requests SET product_id=? WHERE admin_id=? AND request_key=?",product.getId(),admin,key);
        return new Created(product.getId(),false);
    }
    @Transactional
    public Item edit(long admin,long id,Edit body) {
        admins.require(admin); validate(body.name(),body.price());
        if (body.selling()==null || body.revision()==null || body.revision()<0)
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","판매 상태와 수정 버전을 확인하세요.");
        var product=products.findByIdForUpdate(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND","상품을 찾을 수 없습니다."));
        product.editCatalog(body.name(),body.price(),body.selling(),body.revision()); products.flush();
        return Item.from(product);
    }
    private void validate(String name,Long price) {
        if (name==null || name.isBlank() || name.length()>100 || price==null || price<0 || price>1000000000000L)
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","상품명은 1~100자, 가격은 0원 이상이어야 합니다.");
    }
}
