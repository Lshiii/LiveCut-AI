package io.livecut.downloads;

import java.nio.file.Path;
import io.livecut.captions.CaptionBundleWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** A single capacity/quota pool covers both subtitle materials and MP4-only archives. */
@Configuration
public class DeliveryBundleConfig {
    @Bean(destroyMethod="close")
    public CaptionBundleWriter deliveryBundleWriter(@Value("${livecut.storage.root}") String root,
            @Value("${livecut.captions.package-max-bytes:2147483648}") long maxBytes,
            @Value("${livecut.captions.package-slots:2}") int slots,
            @Value("${livecut.captions.package-temporary-quota:4294967296}") long quota,
            @Value("${livecut.captions.package-build-seconds:180}") long seconds){
        return new CaptionBundleWriter(Path.of(root),maxBytes,slots,quota,seconds,900);
    }
}
