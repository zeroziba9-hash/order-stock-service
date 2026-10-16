package com.zeroziba.orderstock.product;

import java.awt.geom.AffineTransform;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Read only bounded EXIF orientation; all original metadata is discarded on encoding. */
final class ImageOrientation {
    private ImageOrientation() { }
    static int read(byte[] bytes) {
        if(bytes.length<4||(bytes[0]&255)!=255||(bytes[1]&255)!=216)return 1;
        int offset=2;
        while(offset+4<=bytes.length){
            if((bytes[offset++]&255)!=255)return 1;
            while(offset<bytes.length&&(bytes[offset]&255)==255)offset++;
            if(offset>=bytes.length)return 1;
            int marker=bytes[offset++]&255;
            if(marker==218||marker==217)return 1;
            if(marker==1||(marker>=208&&marker<=215))continue;
            if(offset+2>bytes.length)return 1;
            int length=((bytes[offset]&255)<<8)|(bytes[offset+1]&255);
            if(length<2||length>bytes.length-offset)return 1;
            if(marker==225&&length>=16&&bytes[offset+2]=='E'&&bytes[offset+3]=='x'&&bytes[offset+4]=='i'&&bytes[offset+5]=='f'&&bytes[offset+6]==0&&bytes[offset+7]==0){
                int result=tiff(bytes,offset+8,length-8);if(result!=1)return result;
            }
            offset+=length;
        }
        return 1;
    }
    private static int tiff(byte[] bytes,int start,int length){
        if(length<8)return 1;
        var data=ByteBuffer.wrap(bytes,start,length).slice();
        if(data.get(0)=='I'&&data.get(1)=='I')data.order(ByteOrder.LITTLE_ENDIAN);
        else if(data.get(0)=='M'&&data.get(1)=='M')data.order(ByteOrder.BIG_ENDIAN);else return 1;
        if(Short.toUnsignedInt(data.getShort(2))!=42)return 1;
        long raw=Integer.toUnsignedLong(data.getInt(4));if(raw<8||raw>length-2)return 1;
        int directory=(int)raw,count=Math.min(Short.toUnsignedInt(data.getShort(directory)),100);
        for(int i=0;i<count;i++){
            long entry=(long)directory+2+12L*i;if(entry>length-12)return 1;
            int p=(int)entry;
            if(Short.toUnsignedInt(data.getShort(p))==274&&Short.toUnsignedInt(data.getShort(p+2))==3&&data.getInt(p+4)==1){
                int orientation=Short.toUnsignedInt(data.getShort(p+8));return orientation>=1&&orientation<=8?orientation:1;
            }
        }
        return 1;
    }
    static AffineTransform transform(int orientation,int width,int height){
        return switch(orientation){
            case 2 -> new AffineTransform(-1,0,0,1,width,0);
            case 3 -> new AffineTransform(-1,0,0,-1,width,height);
            case 4 -> new AffineTransform(1,0,0,-1,0,height);
            case 5 -> new AffineTransform(0,1,1,0,0,0);
            case 6 -> new AffineTransform(0,1,-1,0,height,0);
            case 7 -> new AffineTransform(0,-1,-1,0,height,width);
            case 8 -> new AffineTransform(0,-1,1,0,0,width);
            default -> new AffineTransform();
        };
    }
}
