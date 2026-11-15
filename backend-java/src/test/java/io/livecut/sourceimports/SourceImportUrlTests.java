package io.livecut.sourceimports;

import static org.junit.jupiter.api.Assertions.*;
import io.livecut.jobs.JobException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SourceImportUrlTests {
    @ParameterizedTest @CsvSource({
        "https://youtu.be/abcdefghijk?si=share,https://www.youtube.com/watch?v=abcdefghijk",
        "https://m.youtube.com:443/watch?v=abcdefghijk&t=30,https://www.youtube.com/watch?v=abcdefghijk",
        "https://youtube.com/watch?v=abcdefghijk#t=10,https://www.youtube.com/watch?v=abcdefghijk",
        "https://www.bilibili.com/video/BV1xx411c7mD/?spm_id_from=share,https://www.bilibili.com/video/BV1xx411c7mD",
        "https://m.bilibili.com/video/av123?p=1,https://www.bilibili.com/video/av123",
        "https://www.douyin.com/video/7413274302916906278?enter_from=share,https://www.douyin.com/video/7413274302916906278",
        "https://douyin.com/video/123456789012345/,https://www.douyin.com/video/123456789012345",
        "https://m.douyin.com:443/video/1234567890123456789012?share_app_id=1128,https://www.douyin.com/video/1234567890123456789012",
        "https://v.douyin.com/Ab_123-X/?share_token=tracking,https://v.douyin.com/Ab_123-X/",
        "https://V.DOUYIN.COM/a,https://v.douyin.com/a/"})
    void canonicalizesOnlySupportedSingleVideos(String input,String expected){assertEquals(expected,SourceImportUrl.canonical(input));}
    @ParameterizedTest @ValueSource(strings={
        "http://youtu.be/abcdefghijk","https://user@youtube.com/watch?v=abcdefghijk",
        "https://youtube.com:8443/watch?v=abcdefghijk","https://youtube.com.evil.example/watch?v=abcdefghijk",
        "https://127.0.0.1/watch?v=abcdefghijk","file:///tmp/a.mp4","https://youtube.com/playlist?list=x",
        "https://youtube.com/live/abcdefghijk","https://youtube.com/shorts/abcdefghijk","https://youtube.com/embed/abcdefghijk",
        "https://youtube.com/watch?v=abcdefghijk&list=123","https://youtube.com/watch?v=abcdefghijk&v=12345678901",
        "https://youtube.com/watch?v=abcdefghijk&index=2",
        "https://youtube.com/watch?v=short","https://youtube.com/watch?v=abcdefghijk&x=%0d%0aheader",
        "https://youtube.com/watch?v=abcdefghijk\n","https://youtu.be/abcdefghijk/extra",
        "https://bilibili.com/video/BV1xx411c7mD?p=2","https://bilibili.com/video/av0",
        "https://live.bilibili.com/123","https://bilibili.com/video/BV1xx411c7mD/../../live/123",
        "https://www.m.youtube.com/watch?v=abcdefghijk","https://youtube.com/%77atch?v=abcdefghijk",
        "http://www.douyin.com/video/7413274302916906278","https://user@v.douyin.com/Ab_123-X/",
        "https://www.douyin.com:8443/video/7413274302916906278","https://douyin.com.evil.example/video/7413274302916906278",
        "https://www.douyin.com/video/12345678901234","https://www.douyin.com/video/12345678901234567890123",
        "https://www.douyin.com/video/7413274302916906278/extra","https://www.douyin.com/share/video/7413274302916906278",
        "https://www.douyin.com/user/123456789012345","https://live.douyin.com/123456789012345",
        "https://v.douyin.com/","https://v.douyin.com/a/b/","https://v.douyin.com/a//",
        "https://www.v.douyin.com/abc/","https://m.v.douyin.com/abc/","https://v.douyin.com/%61bc/",
        "https://www.douyin.com/video/7413274302916906278?list=123","https://v.douyin.com/abc/?playlist=123",
        "https://v.douyin.com/abc/?index=2","https://v.douyin.com/abc/?modal_id=7413274302916906278",
        "https://www.douyin.com/video/7413274302916906278?aweme_id=7413274302916906278",
        "https://v.douyin.com/abc/?item_id=7413274302916906278","https://v.douyin.com/abc/?item_ids=1%2C2",
        "https://www.douyin.com/video/7413274302916906278?video_id=other",
        "https://v.douyin.com/abc/?share=1&share=2","https://v.douyin.com/abc/?share=%0a",
        "https://v.douyin.com/a/ https://v.douyin.com/b/"})
    void rejectsUnboundedOrAmbiguousSources(String input){assertEquals("INVALID_SOURCE_URL",assertThrows(JobException.class,()->SourceImportUrl.canonical(input)).getCode());}
    @Test void shortLinksKeepTheirExactBoundedIdentityWithoutResolution() {
        String code="a".repeat(64);
        assertEquals("https://v.douyin.com/"+code+"/",SourceImportUrl.canonical("https://v.douyin.com/"+code));
        assertEquals("INVALID_SOURCE_URL",assertThrows(JobException.class,()->SourceImportUrl.canonical("https://v.douyin.com/"+code+"a/")).getCode());
    }
}
