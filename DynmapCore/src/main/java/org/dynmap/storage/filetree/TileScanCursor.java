package org.dynmap.storage.filetree;
import java.io.IOException;
import org.dynmap.ConfigurationNode;
import org.dynmap.utils.ScanCursor;
public final class TileScanCursor {
    int variant;
    ScanCursor files=new ScanCursor();
    public TileScanCursor(){}
    public TileScanCursor(ConfigurationNode saved)throws IOException{variant=saved.getInteger("variant",0);if(variant<0||variant>100)throw new IOException("Invalid tile scan variant");var node=saved.getNode("files");if(node!=null)files=new ScanCursor(node);}
    public synchronized ConfigurationNode save(){var node=new ConfigurationNode();node.put("variant",variant);node.put("files",files.save());return node;}
}
