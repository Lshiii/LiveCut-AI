package io.livecut.video;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(VideoConfiguration.StorageProperties.class)
public class VideoConfiguration {
    @ConfigurationProperties(prefix = "livecut.storage")
    public record StorageProperties(Path root, long maxVideoBytes) {}

    @Bean
    VideoStorage videoStorage(StorageProperties properties) {
        return new VideoStorage(properties.root(), properties.maxVideoBytes());
    }
}
