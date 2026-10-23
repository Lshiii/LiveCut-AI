package io.livecut.video;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class VideoRepository {
    private static final String COLUMNS = "id, original_filename, content_hash, source_key, size_bytes, media_validation_status, created_at";
    private static final RowMapper<VideoAsset> ROW = (row, index) -> new VideoAsset(
            row.getString("id"), row.getString("original_filename"), row.getString("content_hash"),
            row.getString("source_key"), row.getLong("size_bytes"), row.getString("media_validation_status"),
            row.getTimestamp("created_at").toInstant());
    private final JdbcTemplate jdbc;

    public VideoRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(VideoStorage.StoredVideo video) {
        jdbc.update("INSERT INTO video_asset (id, content_hash, source_key, original_filename, size_bytes, media_validation_status) VALUES (?, ?, ?, ?, ?, ?)",
                video.id(), video.contentHash(), video.sourceKey(), video.originalFilename(), video.sizeBytes(), "HEADER_CHECKED");
    }

    public Optional<VideoAsset> find(String id) {
        List<VideoAsset> assets = jdbc.query("SELECT " + COLUMNS + " FROM video_asset WHERE id = ?", ROW, id);
        return assets.stream().findFirst();
    }

    public List<VideoAsset> list(int limit, int offset) {
        return jdbc.query("SELECT " + COLUMNS + " FROM video_asset ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", ROW, limit, offset);
    }
}
