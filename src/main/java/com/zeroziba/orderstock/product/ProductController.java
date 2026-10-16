package com.zeroziba.orderstock.product;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;
    private final ProductSearch search;
    private final ProductContentService content;
    private final ProductImageService images;

    public ProductController(ProductService productService, ProductSearch search, ProductContentService content, ProductImageService images) {
        this.productService = productService;
        this.search=search;
        this.content=content;
        this.images=images;
    }

    @GetMapping
    public List<ProductResponse> list() {
        return images.decorate(productService.findAll());
    }

    @GetMapping("/{id}")
    public ProductContentService.Detail detail(@org.springframework.web.bind.annotation.PathVariable long id) {
        return content.detail(id);
    }

    @GetMapping("/search")
    public ProductSearch.Page<ProductResponse> search(@org.springframework.web.bind.annotation.RequestParam(defaultValue="") String q,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="false") boolean available,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="ID_ASC") ProductSearch.Sort sort,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="0") int page) {
        var result=search.search(q,available,sort,page);
        return new ProductSearch.Page<>(images.decorate(result.products()),result.page(),result.hasMore());
    }
}
