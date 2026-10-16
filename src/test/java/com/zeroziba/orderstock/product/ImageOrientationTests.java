package com.zeroziba.orderstock.product;

import static org.assertj.core.api.Assertions.*;
import java.awt.geom.Point2D;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ImageOrientationTests {
    @Test void malformedAndTruncatedMetadataFallsBackWithoutReadingOutsideTheInput(){
        var random=new Random(42);for(int length=0;length<300;length++){byte[] bytes=new byte[length];random.nextBytes(bytes);assertThat(ImageOrientation.read(bytes)).isBetween(1,8);}
        assertThat(ImageOrientation.read(new byte[]{(byte)255,(byte)216,(byte)255,(byte)225,(byte)255,(byte)255})).isEqualTo(1);
    }
    @Test void allEightTransformsMapImageCornersInsideTheirOrientedBounds(){
        for(int orientation=1;orientation<=8;orientation++){
            var transform=ImageOrientation.transform(orientation,30,10);double width=orientation>=5?10:30,height=orientation>=5?30:10;
            for(var point:new Point2D[]{new Point2D.Double(0,0),new Point2D.Double(30,0),new Point2D.Double(0,10),new Point2D.Double(30,10)}){
                var result=transform.transform(point,null);assertThat(result.getX()).isBetween(0d,width);assertThat(result.getY()).isBetween(0d,height);
            }
        }
    }
}
