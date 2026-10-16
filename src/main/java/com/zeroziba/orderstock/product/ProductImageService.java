package com.zeroziba.orderstock.product;

import com.zeroziba.orderstock.auth.AdminAccess;
import com.zeroziba.orderstock.common.ApiException;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProductImageService {
    public static final int MAX_BYTES=3*1024*1024;
    public record Info(long revision,String imageUrl,Integer width,Integer height) { }
    public record Image(byte[] bytes,String hash) { }
    public record Change(long changeId,long actorId,String beforeHash,String afterHash,long revision,Instant createdAt) { }
    public record History(List<Change> changes,Long nextBeforeId) { }
    private record Encoded(byte[] bytes,String hash,int width,int height) { }
    private final JdbcTemplate jdbc;
    private final AdminAccess admins;
    private final ProductRepository products;
    private final Clock clock;
    public ProductImageService(JdbcTemplate jdbc,AdminAccess admins,ProductRepository products,Clock clock) {
        this.jdbc=jdbc;this.admins=admins;this.products=products;this.clock=clock;
    }
    @Transactional(readOnly=true)
    public Info info(long admin,long id){admins.require(admin);requireProduct(id);return readInfo(id);}
    @Transactional(readOnly=true)
    public String publicUrl(long id){return readInfo(id).imageUrl();}
    @Transactional(readOnly=true)
    public List<ProductResponse> decorate(List<ProductResponse> rows) {
        if(rows.isEmpty())return rows;
        var urls=new HashMap<Long,String>();
        var placeholders=String.join(",",Collections.nCopies(rows.size(),"?"));
        jdbc.query("SELECT product_id,revision FROM product_images WHERE image_data IS NOT NULL AND product_id IN ("+placeholders+")",
                (org.springframework.jdbc.core.RowCallbackHandler)row->urls.put(row.getLong(1),url(row.getLong(1),row.getLong(2))),rows.stream().map(ProductResponse::id).toArray());
        return rows.stream().map(row->new ProductResponse(row.id(),row.name(),row.price(),row.stock(),urls.get(row.id()))).toList();
    }
    @Transactional(readOnly=true)
    public Image image(long id,Long revision,Long admin) {
        if(admin!=null){admins.require(admin);requireProduct(id);}
        else if(!products.findById(id).filter(Product::isSelling).isPresent())throw missing();
        var rows=jdbc.query("SELECT image_data,image_hash,revision FROM product_images WHERE product_id=? AND image_data IS NOT NULL",
            (row,index)->new Object[]{row.getBytes(1),row.getString(2),row.getLong(3)},id);
        if(rows.isEmpty() || (revision!=null && !revision.equals(rows.get(0)[2])))throw missing();
        return new Image((byte[])rows.get(0)[0],(String)rows.get(0)[1]);
    }
    @Transactional
    public Info upload(long admin,long id,long revision,MultipartFile file) {
        admins.require(admin);requireProduct(id);
        if(revision<0)throw invalid();
        var encoded=encode(file);
        return write(admin,id,revision,encoded);
    }
    @Transactional
    public Info delete(long admin,long id,long revision) {
        admins.require(admin);requireProduct(id);if(revision<0)throw invalid();return write(admin,id,revision,null);
    }
    private Info write(long admin,long id,long revision,Encoded encoded) {
        jdbc.update("INSERT INTO product_images(product_id) VALUES (?) ON DUPLICATE KEY UPDATE product_id=product_id",id);
        var before=jdbc.queryForObject("SELECT image_hash,revision FROM product_images WHERE product_id=? FOR UPDATE",
            (row,index)->new Object[]{row.getString(1),row.getLong(2)},id);
        if((long)before[1]!=revision)throw new ApiException(HttpStatus.CONFLICT,"IMAGE_CHANGED","다른 접속에서 이미지가 바뀌었어요. 새로고침 후 다시 수정하세요.");
        String hash=encoded==null?null:encoded.hash();
        if(Objects.equals(before[0],hash))return readInfo(id);
        long next=Math.incrementExact(revision);
        jdbc.update("UPDATE product_images SET image_data=?,image_hash=?,width=?,height=?,revision=? WHERE product_id=?",
            encoded==null?null:encoded.bytes(),hash,encoded==null?null:encoded.width(),encoded==null?null:encoded.height(),next,id);
        jdbc.update("INSERT INTO product_image_changes(product_id,actor_id,before_hash,after_hash,revision,created_at) VALUES (?,?,?,?,?,?)",
            id,admin,before[0],hash,next,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        return readInfo(id);
    }
    @Transactional(readOnly=true)
    public History history(long admin,long id,Long beforeId) {
        admins.require(admin);requireProduct(id);if(beforeId!=null&&beforeId<1)throw invalid();
        var rows=jdbc.query("SELECT * FROM product_image_changes WHERE product_id=? AND id<? ORDER BY id DESC LIMIT 21",
            (row,index)->new Change(row.getLong("id"),row.getLong("actor_id"),row.getString("before_hash"),row.getString("after_hash"),row.getLong("revision"),row.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),id,beforeId==null?Long.MAX_VALUE:beforeId);
        return new History(rows.stream().limit(20).toList(),rows.size()>20?rows.get(19).changeId():null);
    }
    private Info readInfo(long id) {
        var rows=jdbc.query("SELECT revision,image_hash,width,height FROM product_images WHERE product_id=?",
            (row,index)->new Info(row.getLong(1),row.getString(2)==null?null:url(id,row.getLong(1)),row.getObject(3,Integer.class),row.getObject(4,Integer.class)),id);
        return rows.isEmpty()?new Info(0,null,null,null):rows.get(0);
    }
    private Encoded encode(MultipartFile file) {
        if(file==null||file.isEmpty())throw invalid();
        if(file.getSize()>MAX_BYTES)throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"IMAGE_TOO_LARGE","이미지는 3MB 이하로 선택하세요.");
        if(file.getContentType()==null||!Set.of("image/png","image/jpeg").contains(file.getContentType()))throw invalid();
        try(var sourceInput=file.getInputStream();var input=new MemoryCacheImageInputStream(sourceInput)) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw invalid();
            var reader=readers.next();
            try {
                if(!Set.of("png","jpeg","jpg").contains(reader.getFormatName().toLowerCase(Locale.ROOT)))throw invalid();
                reader.setInput(input,true,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1||width>8000||height>8000||(long)width*height>16_000_000)throw invalid();
                var source=reader.read(0);if(source==null)throw invalid();
                int orientation=ImageOrientation.read(file.getBytes());
                int orientedWidth=orientation>=5?height:width,orientedHeight=orientation>=5?width:height;
                double scale=Math.min(1d,1600d/Math.max(orientedWidth,orientedHeight));
                int w=Math.max(1,(int)Math.round(orientedWidth*scale)),h=Math.max(1,(int)Math.round(orientedHeight*scale));
                var normalized=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
                var graphics=normalized.createGraphics();
                try{graphics.setColor(Color.WHITE);graphics.fillRect(0,0,w,h);graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);graphics.scale((double)w/orientedWidth,(double)h/orientedHeight);graphics.transform(ImageOrientation.transform(orientation,width,height));graphics.drawImage(source,0,0,null);}
                finally{graphics.dispose();source.flush();}
                var output=new ByteArrayOutputStream();
                try{if(!ImageIO.write(normalized,"jpeg",output))throw invalid();}finally{normalized.flush();}
                byte[] bytes=output.toByteArray();if(bytes.length>MAX_BYTES)throw invalid();
                return new Encoded(bytes,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),w,h);
            }finally{reader.dispose();}
        }catch(ApiException exception){throw exception;}
        catch(IOException|java.security.NoSuchAlgorithmException|IllegalArgumentException exception){throw invalid();}
    }
    private static String url(long id,long revision){return "/products/"+id+"/image?revision="+revision;}
    private void requireProduct(long id){if(id<1)throw invalid();if(!products.existsById(id))throw missing();}
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_IMAGE","JPEG·PNG 이미지와 수정 버전을 확인하세요. 최대 8000px, 1600만 화소까지 지원해요.");}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"IMAGE_NOT_FOUND","표시할 상품 이미지가 없어요.");}
}
