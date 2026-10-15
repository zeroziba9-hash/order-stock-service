package com.zeroziba.orderstock.product;
import com.zeroziba.orderstock.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AdminProductSearchController {
    private final ProductSearch search;
    public AdminProductSearchController(ProductSearch search){this.search=search;}
    @GetMapping("/admin/products/{id}")
    public CatalogService.Item one(@AuthenticationPrincipal AuthenticatedUser admin,@PathVariable long id){return search.one(admin.getId(),id);}
    @GetMapping("/admin/products/search")
    public ProductSearch.Page<CatalogService.Item> search(@AuthenticationPrincipal AuthenticatedUser admin,
            @RequestParam(defaultValue="") String q,@RequestParam(required=false) Boolean selling,
            @RequestParam(required=false) Integer lowStock,@RequestParam(defaultValue="0") int page) {
        return search.admin(admin.getId(),q,selling,lowStock,page);
    }
}
