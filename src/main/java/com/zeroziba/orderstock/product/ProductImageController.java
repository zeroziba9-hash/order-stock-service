package com.zeroziba.orderstock.product;

import com.zeroziba.orderstock.auth.AuthenticatedUser;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ProductImageController {
    private final ProductImageService images;
    public ProductImageController(ProductImageService images){this.images=images;}
    @GetMapping("/products/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable long id,@RequestParam(required=false) Long revision){return response(images.image(id,revision,null));}
    @GetMapping("/admin/products/{id}/image/file")
    public ResponseEntity<byte[]> adminImage(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable long id,@RequestParam(required=false) Long revision){return response(images.image(id,revision,user.getId()));}
    @GetMapping("/admin/products/{id}/image")
    public ProductImageService.Info info(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable long id){return images.info(user.getId(),id);}
    @PostMapping(value="/admin/products/{id}/image",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProductImageService.Info upload(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable long id,@RequestParam long revision,@RequestPart MultipartFile file){return images.upload(user.getId(),id,revision,file);}
    @DeleteMapping("/admin/products/{id}/image")
    public ProductImageService.Info delete(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable long id,@RequestParam long revision){return images.delete(user.getId(),id,revision);}
    @GetMapping("/admin/products/{id}/image/changes")
    public ProductImageService.History history(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable long id,@RequestParam(required=false) Long beforeId){return images.history(user.getId(),id,beforeId);}
    private ResponseEntity<byte[]> response(ProductImageService.Image image) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noCache())
            .eTag("\""+image.hash()+"\"").header("X-Content-Type-Options","nosniff").header("Content-Disposition","inline").body(image.bytes());
    }
}
