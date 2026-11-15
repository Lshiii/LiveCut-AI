package io.livecut.sourceimports;

import static org.junit.jupiter.api.Assertions.*;
import io.livecut.jobs.JobException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SourceImportUrlTests {
    @ParameterizedTest @CsvSource({
        "https://youtu.be/abcdefghijk?si=share,https://www.youtube.com/watch?v=abcdefghijk",
        "https://m.youtube.com:443/watch?v=abcdefghijk&t=30,https://www.youtube.com/watch?v=abcdefghijk",
        "https://youtube.com/watch?v=abcdefghijk#t=10,https://www.youtube.com/watch?v=abcdefghijk",
        "https://www.bilibili.com/video/BV1xx411c7mD/?spm_id_from=share,https://www.bilibili.com/video/BV1xx411c7mD",
        "https://m.bilibili.com/video/av123?p=1,https://www.bilibili.com/video/av123"})
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
        "https://www.m.youtube.com/watch?v=abcdefghijk","https://youtube.com/%77atch?v=abcdefghijk"})
    void rejectsUnboundedOrAmbiguousSources(String input){assertEquals("INVALID_SOURCE_URL",assertThrows(JobException.class,()->SourceImportUrl.canonical(input)).getCode());}
}
