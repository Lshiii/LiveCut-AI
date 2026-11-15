package io.livecut.sourceimports;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;

/** Deliberately bounded single-video URL grammar, not a generic URL fetcher. */
public final class SourceImportUrl {
    private SourceImportUrl() {}
    public static String canonical(String value) {
        try {
            if (value == null || value.length() > 2048 || value.isBlank()
                || value.chars().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))) throw invalid();
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getHost() == null) throw invalid();
            String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (decoded.chars().anyMatch(Character::isISOControl)) throw invalid();
            String originalHost = uri.getHost().toLowerCase(Locale.ROOT);
            String host = originalHost;
            if (host.startsWith("www.")) host = host.substring(4);
            else if (host.startsWith("m.")) host = host.substring(2);
            String path = uri.getRawPath();
            Map<String,String> query = query(uri.getRawQuery());
            if (query.containsKey("list") || query.containsKey("playlist") || query.containsKey("index")) throw invalid();
            if (host.equals("youtube.com") && path.equals("/watch")) {
                return youtube(query.get("v"));
            }
            if (host.equals("youtu.be") && path.matches("/[A-Za-z0-9_-]{11}/?")) {
                if (query.containsKey("v")) throw invalid();
                return youtube(path.substring(1).replace("/", ""));
            }
            if (host.equals("bilibili.com") && path.matches("/video/(BV[A-Za-z0-9]{10}|av[1-9][0-9]{0,19})/?")) {
                if (query.containsKey("p") && !"1".equals(query.get("p"))) throw invalid();
                return "https://www.bilibili.com" + path.replaceAll("/$", "");
            }
            if (host.equals("douyin.com") || originalHost.equals("v.douyin.com")) {
                // Do not silently discard a second video identity as tracking data.
                for (String identity : java.util.Set.of("modal_id", "aweme_id", "item_id", "item_ids", "video_id")) {
                    if (query.containsKey(identity)) throw invalid();
                }
                if (host.equals("douyin.com") && path.matches("/video/[0-9]{15,22}/?")) {
                    return "https://www.douyin.com" + path.replaceAll("/$", "");
                }
                // Keep a short link's identity: only the guarded downloader resolves it.
                if (originalHost.equals("v.douyin.com") && path.matches("/[A-Za-z0-9_-]{1,64}/?")) {
                    return "https://v.douyin.com" + path.replaceAll("/$", "") + "/";
                }
            }
            throw invalid();
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    private static String youtube(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) throw invalid();
        return "https://www.youtube.com/watch?v=" + id;
    }
    private static Map<String,String> query(String raw) {
        Map<String,String> result = new HashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String part : raw.split("&", -1)) {
            String[] entry = part.split("=", 2);
            String name = URLDecoder.decode(entry[0], StandardCharsets.UTF_8);
            String val = entry.length == 2 ? URLDecoder.decode(entry[1], StandardCharsets.UTF_8) : "";
            if (name.isEmpty() || result.putIfAbsent(name, val) != null) throw invalid();
        }
        return result;
    }
    private static JobException invalid() {
        return new JobException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_URL", "Use a supported public HTTPS single-video YouTube, Bilibili or Douyin URL");
    }
}
