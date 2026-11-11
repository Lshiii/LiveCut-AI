package io.livecut.captions;

import java.nio.file.Path;
import io.livecut.downloads.DeliveryBundleWriter;

/** Compatibility name for the original subtitle-package API and standalone writer callers. */
public final class CaptionBundleWriter extends DeliveryBundleWriter {
    public CaptionBundleWriter(Path storageRoot,long maxBytes,int concurrency,long temporaryQuota,long buildSeconds,long lifetimeSeconds){
        super(storageRoot,maxBytes,concurrency,temporaryQuota,buildSeconds,lifetimeSeconds);
    }
}
