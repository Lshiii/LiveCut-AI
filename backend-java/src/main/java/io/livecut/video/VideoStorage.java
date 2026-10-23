package io.livecut.video;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

/** Disk-streamed, immutable source storage. Header checks are not a real probe. */
public class VideoStorage {
    private static final long HARD_LIMIT = 1L << 30;
    private static final Pattern SOURCE_KEY = Pattern.compile("videos/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.mp4");
    private static final Set<String> MP4_BRANDS = Set.of("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "MSNV", "dash");
    private final Path root;
    private final Path videos;
    private final long maxSizeBytes;

    public record StoredVideo(String id, String originalFilename, String contentHash, String sourceKey, long sizeBytes) {}

    public VideoStorage(Path root, long maxSizeBytes) {
        if (root == null || maxSizeBytes <= 0 || maxSizeBytes > HARD_LIMIT) {
            throw new IllegalArgumentException("Storage root and a size limit of 1..1073741824 bytes are required");
        }
        this.maxSizeBytes = maxSizeBytes;
        try {
            Files.createDirectories(root.toAbsolutePath().normalize());
            this.root = root.toRealPath();
            this.videos = this.root.resolve("videos");
            Files.createDirectories(videos);
            verifyDirectory();
        } catch (IOException exception) {
            throw storageError();
        }
    }

    public StoredVideo store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new VideoApiException(HttpStatus.BAD_REQUEST, "EMPTY_VIDEO", "Select a nonempty MP4 file");
        }
        String filename = displayFilename(file.getOriginalFilename());
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            throw new VideoApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_VIDEO", "Only MP4 uploads are supported");
        }
        if (file.getSize() > maxSizeBytes) { throw tooLarge(); }
        String id = UUID.randomUUID().toString();
        String sourceKey = "videos/" + id + ".mp4";
        Path temporary = null;
        try {
            verifyDirectory();
            temporary = Files.createTempFile(videos, ".upload-", ".tmp");
            MessageDigest digest = sha256();
            byte[] prefix = new byte[64];
            int prefixLength = 0;
            long size = 0;
            try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) { continue; }
                    size += count;
                    if (size > maxSizeBytes) { throw tooLarge(); }
                    int copied = Math.min(count, prefix.length - prefixLength);
                    if (copied > 0) {
                        System.arraycopy(buffer, 0, prefix, prefixLength, copied);
                        prefixLength += copied;
                    }
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            validateHeader(prefix, prefixLength, size);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Path target = resolve(sourceKey);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { throw storageError(); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            return new StoredVideo(id, filename, HexFormat.of().formatHex(digest.digest()), sourceKey, size);
        } catch (IOException exception) {
            throw storageError();
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* no other files are touched */ }
            }
        }
    }

    /** Only database-owned UUID keys resolve; a valid but absent file may resolve. */
    public Path resolve(String sourceKey) {
        if (sourceKey == null || !SOURCE_KEY.matcher(sourceKey).matches()) {
            throw new VideoApiException(HttpStatus.BAD_REQUEST, "INVALID_STORAGE_KEY", "Invalid video source reference");
        }
        try {
            verifyDirectory();
            Path target = root.resolve(sourceKey).normalize();
            if (!target.startsWith(root) || Files.isSymbolicLink(target)) { throw storageError(); }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !target.toRealPath().startsWith(root))) {
                throw storageError();
            }
            return target;
        } catch (IOException exception) {
            throw storageError();
        }
    }

    public void delete(StoredVideo video) {
        if (!video.sourceKey().equals("videos/" + video.id() + ".mp4")) { throw storageError(); }
        try { Files.deleteIfExists(resolve(video.sourceKey())); }
        catch (IOException exception) { throw storageError(); }
    }

    private void verifyDirectory() throws IOException {
        if (Files.isSymbolicLink(videos) || !Files.isDirectory(videos, LinkOption.NOFOLLOW_LINKS) || !videos.toRealPath().startsWith(root)) {
            throw storageError();
        }
    }

    private static String displayFilename(String raw) {
        if (raw == null) { throw new VideoApiException(HttpStatus.BAD_REQUEST, "INVALID_FILENAME", "A display filename is required"); }
        String value = raw.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).strip();
        if (value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new VideoApiException(HttpStatus.BAD_REQUEST, "INVALID_FILENAME", "A valid display filename is required");
        }
        if (value.codePointCount(0, value.length()) > 255) {
            throw new VideoApiException(HttpStatus.BAD_REQUEST, "INVALID_FILENAME", "Display filename must not exceed 255 characters");
        }
        return value;
    }

    private static void validateHeader(byte[] prefix, int count, long size) {
        if (size == 0) { throw new VideoApiException(HttpStatus.BAD_REQUEST, "EMPTY_VIDEO", "Select a nonempty MP4 file"); }
        if (count < 16 || !"ftyp".equals(new String(prefix, 4, 4, StandardCharsets.US_ASCII))) { throw invalidHeader(); }
        long boxSize = Integer.toUnsignedLong(ByteBuffer.wrap(prefix, 0, 4).getInt());
        int brandOffset = 8;
        if (boxSize == 1 && count >= 24) {
            boxSize = ByteBuffer.wrap(prefix, 8, 8).getLong();
            brandOffset = 16;
        }
        if (boxSize < brandOffset + 8 || boxSize > size || !MP4_BRANDS.contains(new String(prefix, brandOffset, 4, StandardCharsets.US_ASCII))) {
            throw invalidHeader();
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("JDK SHA-256 provider is required"); }
    }
    private static VideoApiException invalidHeader() { return new VideoApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INVALID_VIDEO_HEADER", "File does not have a supported MP4 header; full media probing is still required"); }
    private static VideoApiException tooLarge() { return new VideoApiException(HttpStatus.PAYLOAD_TOO_LARGE, "VIDEO_TOO_LARGE", "Video exceeds the configured upload size limit"); }
    private static VideoApiException storageError() { return new VideoApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Video storage is unavailable"); }
}
