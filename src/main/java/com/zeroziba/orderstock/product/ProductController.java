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

    public ProductController(ProductService productService, ProductSearch search, ProductContentService content) {
        this.productService = productService;
        this.search=search;
        this.content=content;
    }

    @GetMapping
    public List<ProductResponse> list() {
        return productService.findAll();
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
        return search.search(q,available,sort,page);
    }
}
