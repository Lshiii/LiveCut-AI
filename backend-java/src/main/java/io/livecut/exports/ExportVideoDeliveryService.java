package io.livecut.exports;

import java.util.List;
import io.livecut.downloads.DeliveryBundleWriter;
import org.springframework.stereotype.Service;

/** Explicit MP4 selection; neither caption initialization nor any model belongs to this path. */
@Service
public class ExportVideoDeliveryService {
    private final ExportService exports;
    private final DeliveryBundleWriter bundles;
    public ExportVideoDeliveryService(ExportService exports,DeliveryBundleWriter bundles){this.exports=exports;this.bundles=bundles;}
    public DeliveryBundleWriter.Prepared bundle(String exportId,List<String> selected){
        var plan=exports.videoPackagePlan(exportId,selected);
        return bundles.prepareVideos(plan,clip->exports.videoPackageContent(plan,clip));
    }
}
