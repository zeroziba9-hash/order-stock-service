package com.zeroziba.orderstock.product;

import com.zeroziba.orderstock.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/products")
public class CatalogController {
    private final CatalogService catalog;
    private final CatalogChangeHistory changes;
    public CatalogController(CatalogService catalog, CatalogChangeHistory changes) { this.catalog=catalog; this.changes=changes; }
    @GetMapping public List<CatalogService.Item> list(@AuthenticationPrincipal AuthenticatedUser admin) { return catalog.list(admin.getId()); }
    @PostMapping public ResponseEntity<CatalogService.Created> create(@AuthenticationPrincipal AuthenticatedUser admin,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@Valid @RequestBody CatalogService.Create body) {
        var result=catalog.create(admin.getId(),key,body);
        return ResponseEntity.status(result.replayed()?200:201).header("Idempotency-Replayed",Boolean.toString(result.replayed()))
                .header("Location","/admin/products").body(result);
    }
    @PutMapping("/{id}") public CatalogService.Item edit(@AuthenticationPrincipal AuthenticatedUser admin,
            @PathVariable long id,@Valid @RequestBody CatalogService.Edit body) { return catalog.edit(admin.getId(),id,body); }
    @GetMapping("/{id}/changes") public CatalogChangeHistory.Page changes(@AuthenticationPrincipal AuthenticatedUser admin,
            @PathVariable long id,@RequestParam(required=false) Long beforeId,
            @RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to) {
        return changes.list(admin.getId(),id,beforeId,from,to);
    }
}
