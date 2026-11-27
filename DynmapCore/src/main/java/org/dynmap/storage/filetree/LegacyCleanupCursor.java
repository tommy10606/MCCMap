package org.dynmap.storage.filetree;

import java.io.IOException;
import org.dynmap.ConfigurationNode;
import org.dynmap.utils.ScanCursor;

/** Only acknowledged files are checkpointed; replacement proofs are repeated after a restart. */
public final class LegacyCleanupCursor {
    int source,variant;
    ScanCursor files=new ScanCursor();
    transient FileTreeMapStorage.LegacyCandidate candidate;
    public long examined,deleted,bytes,retained;
    public LegacyCleanupCursor() {}
    public LegacyCleanupCursor(ConfigurationNode saved)throws IOException {
        source=saved.getInteger("source",0);variant=saved.getInteger("variant",0);
        if(source<0||source>100||variant<0||variant>100)throw new IOException("Invalid legacy cleanup cursor");
        var node=saved.getNode("files");if(node!=null)files=new ScanCursor(node);
        examined=Math.max(0,Long.parseLong(saved.getString("examined","0")));deleted=Math.max(0,Long.parseLong(saved.getString("deleted","0")));
        bytes=Math.max(0,Long.parseLong(saved.getString("bytes","0")));retained=Math.max(0,Long.parseLong(saved.getString("retained","0")));
    }
    public ConfigurationNode save() {
        var node=new ConfigurationNode();node.put("source",source);node.put("variant",variant);node.put("files",files.save());
        node.put("examined",examined);node.put("deleted",deleted);node.put("bytes",bytes);node.put("retained",retained);return node;
    }
}
