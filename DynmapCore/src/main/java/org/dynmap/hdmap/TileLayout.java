package org.dynmap.hdmap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.dynmap.MapType.ImageFormat;
import org.json.simple.JSONObject;
/** Isolate incompatible raster grids while retaining stable configured map names. */
public final class TileLayout {
    private TileLayout() {}
    static String namespace(String prefix,HDPerspective perspective,int tileScale,int boost,ImageFormat format) {
        var json=new JSONObject();perspective.addClientConfiguration(json);
        String key=prefix+"\n"+json.get("worldtomap")+"\n"+tileScale+"\n"+boost+"\n"+format.getID();
        try {
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))).substring(0,16);
            return prefix.substring(0,Math.min(prefix.length(),100))+"_r"+digest;
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    static int compatibleShift(HDPerspective old,HDPerspective current) {
        int shift=signedCompatibleShift(old,current);return shift < 0 ? -1 : shift;
    }
    static int signedCompatibleShift(HDPerspective old,HDPerspective current) {
        if(old==null||current==null)return Integer.MIN_VALUE;
        String pattern="_(?:vlowres|lowres|medres|hires|vhires|xhires)$";
        if(!old.getName().matches("iso_[A-Z]+_(30|60|90)"+pattern)||!old.getName().replaceAll(pattern,"").equals(current.getName().replaceAll(pattern,"")))return Integer.MIN_VALUE;
        var a=new JSONObject();var b=new JSONObject();old.addClientConfiguration(a);current.addClientConfiguration(b);
        var first=(java.util.List<?>)a.get("worldtomap");var second=(java.util.List<?>)b.get("worldtomap");
        if(first==null||second==null||first.size()!=9||second.size()!=9)return Integer.MIN_VALUE;
        double ratio=current.getScale()/old.getScale();int shift=(int)Math.round(Math.log(ratio)/Math.log(2));
        if(shift < -4 || shift > 4 || Math.abs(ratio-Math.scalb(1.0,shift))>1e-8)return Integer.MIN_VALUE;
        for(int i=0;i<9;i++){double expected=((Number)first.get(i)).doubleValue()*(i<6?ratio:1);if(Math.abs(expected-((Number)second.get(i)).doubleValue())>1e-7)return Integer.MIN_VALUE;}
        return shift;
    }
}
