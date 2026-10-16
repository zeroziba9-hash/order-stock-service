package com.zeroziba.orderstock.product;

import com.zeroziba.orderstock.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/products/{id}/content")
public class ProductContentController {
    private final ProductContentService content;
    public ProductContentController(ProductContentService content){this.content=content;}
    @GetMapping
    public ProductContentService.Content get(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable long id){return content.get(user.getId(),id);}
    @PutMapping
    public ProductContentService.Content update(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable long id,@Valid @RequestBody ProductContentService.Update body){return content.update(user.getId(),id,body);}
    @GetMapping("/changes")
    public ProductContentService.History history(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable long id,@RequestParam(required=false) Long beforeId){return content.history(user.getId(),id,beforeId);}
}
