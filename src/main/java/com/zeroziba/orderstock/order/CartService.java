package com.zeroziba.orderstock.order;

import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.product.ProductRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {
    public record Update(@NotNull @PositiveOrZero Long revision,
            @NotNull @Size(max=20) List<@NotNull @Valid CreateOrderRequest> items) { }
    public record Item(long id,String name,long price,int quantity,int stock,boolean selling) { }
    public record Cart(long revision,List<Item> items) { }
    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    public CartService(JdbcTemplate jdbc,ProductRepository products){this.jdbc=jdbc;this.products=products;}

    @Transactional(readOnly=true)
    public Cart get(long userId) {
        // Repeatable-read keeps the header revision and items in the same snapshot.
        var revisions=jdbc.queryForList("SELECT revision FROM saved_carts WHERE user_id=?",Long.class,userId);
        return new Cart(revisions.isEmpty()?0:revisions.get(0),jdbc.query("""
                SELECT p.id,p.name,p.price,c.quantity,p.stock,p.selling
                FROM saved_cart_items c JOIN products p ON p.id=c.product_id WHERE c.user_id=? ORDER BY p.id
                """,(row,index)->new Item(row.getLong(1),row.getString(2),row.getLong(3),row.getInt(4),row.getInt(5),row.getBoolean(6)),userId));
    }

    @Transactional
    public Cart update(long userId,Update body) {
        if(body==null || body.revision()==null || body.revision()<0 || body.items()==null || body.items().size()>20)
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","장바구니 내용을 확인하세요.");
        var items=body.items().isEmpty()?List.<CreateOrderRequest>of():new CartOrderRequest(body.items()).normalized();
        jdbc.update("INSERT INTO saved_carts(user_id,revision) VALUES (?,0) ON DUPLICATE KEY UPDATE user_id=user_id",userId);
        long revision=jdbc.queryForObject("SELECT revision FROM saved_carts WHERE user_id=? FOR UPDATE",Long.class,userId);
        if(revision!=body.revision())throw new ApiException(HttpStatus.CONFLICT,"CART_CHANGED","다른 창이나 기기에서 장바구니가 바뀌었어요. 최신 내용을 불러온 뒤 다시 수정하세요.");
        for(var item:items)if(!products.existsById(item.productId()))throw new ApiException(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND","상품을 찾을 수 없습니다.");
        jdbc.update("DELETE FROM saved_cart_items WHERE user_id=?",userId);
        for(var item:items)jdbc.update("INSERT INTO saved_cart_items(user_id,product_id,quantity) VALUES (?,?,?)",userId,item.productId(),item.quantity());
        jdbc.update("UPDATE saved_carts SET revision=? WHERE user_id=?",Math.incrementExact(revision),userId);
        return get(userId);
    }
}
