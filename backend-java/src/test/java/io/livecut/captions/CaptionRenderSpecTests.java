package io.livecut.captions;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import io.livecut.jobs.JobException;
import io.livecut.jobs.ArtifactVerifier.SourcePart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CaptionRenderSpecTests {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @TempDir Path root;
    static ObjectNode spec(){return (ObjectNode)JSON.valueToTree(Map.ofEntries(
        Map.entry("schema_version",1),Map.entry("profile_version",CaptionRenderSpec.VERSION),Map.entry("source_export_id","11111111-1111-4111-8111-111111111111"),Map.entry("source_clip_id","22222222-2222-4222-8222-222222222222"),Map.entry("source_snapshot_hash","a".repeat(64)),
        Map.entry("input_media",Map.of("key","jobs/source/clip.mp4","sha256","b".repeat(64),"size_bytes",128,"duration_ms",3000,"width",641,"height",359,"has_audio",true)),
        Map.entry("captions",Map.of("revision",0,"content_hash","c".repeat(64),"cues_key","caption-render/cues/input.json","cues_sha256","d".repeat(64),"duration_ms",3000)),
        Map.entry("style_preset","WHITE_OUTLINE_V1"),Map.entry("frame_preset","SOURCE"),Map.entry("output",Map.of("width",642,"height",360)),
        Map.entry("font",Map.of("profile_id",CaptionRenderSpec.FONT_PROFILE,"family",CaptionRenderSpec.FONT_FAMILY,"key","caption-render/fonts/fixed.otf","sha256",CaptionRenderSpec.FONT_HASH,"size_bytes",CaptionRenderSpec.FONT_SIZE)),
        Map.entry("timing_basis","CLIP_RELATIVE_ASS_CS_FLOOR")));}
    @Test void exactSpecSupportsAllPresetsAndOnlyEvenSourcePadding(){
        for(String style:CaptionRenderSpec.STYLES)for(String frame:CaptionRenderSpec.FRAMES){var node=spec().put("style_preset",style).put("frame_preset",frame);var output=(ObjectNode)node.path("output");
            output.put("width",frame.equals("LANDSCAPE_720")?1280:frame.equals("PORTRAIT_720")?720:642).put("height",frame.equals("LANDSCAPE_720")?720:frame.equals("PORTRAIT_720")?1280:360);
            assertDoesNotThrow(()->CaptionRenderSpec.validate(node));}
    }
    @Test void unknownNestedFieldsTraversalTypesIdentityFontAndGeometryCannotEnterFrozenSpec(){
        List<Consumer<ObjectNode>> mutations=List.of(n->n.put("extra",1),n->((ObjectNode)n.path("input_media")).put("extra",1),
            n->((ObjectNode)n.path("captions")).remove("duration_ms"),n->n.put("schema_version",1.0),n->n.put("source_clip_id","invalid"),n->n.put("style_preset","custom"),
            n->((ObjectNode)n.path("input_media")).put("key","../external"),n->((ObjectNode)n.path("input_media")).put("key","a//b"),n->((ObjectNode)n.path("input_media")).put("key","C:\\file"),
            n->((ObjectNode)n.path("input_media")).put("has_audio","true"),n->((ObjectNode)n.path("input_media")).put("width",4097),n->((ObjectNode)n.path("output")).put("width",640),
            n->((ObjectNode)n.path("font")).put("sha256","0".repeat(64)),n->((ObjectNode)n.path("font")).put("size_bytes",1),n->((ObjectNode)n.path("captions")).put("revision",9007199254740992L));
        for(var mutate:mutations){var node=spec();mutate.accept(node);assertThrows(IllegalArgumentException.class,()->CaptionRenderSpec.validate(node));}
    }
    private CaptionModels.Snapshot caption(List<CaptionModels.Cue> cues){return new CaptionModels.Snapshot("export","clip","标题","/content",0,"CONFIRMED",List.of(0L),"c".repeat(64),new CaptionModels.Source("a".repeat(64),"b".repeat(64),"d".repeat(64),3000,2990,"SOURCE_EDL_SENTENCE_MS",List.of(new SourcePart(0,3000))),cues);}
    private CaptionModels.Cue cue(String text,long start,long end){return new CaptionModels.Cue("c000001",0,"u1",0,3000,0,3000,start,end,text,text,false,false,false);}
    @Test void cuesAreCanonicalImmutablePlainTextAndHashBound()throws Exception{
        var assets=new CaptionRenderAssets(root,root.resolve("absent.otf"));String text="中文 {\\N} <b>字面文本</b>\n下一行";
        var first=assets.cues(caption(List.of(cue(text,5,1005))));var second=assets.cues(caption(List.of(cue(text,5,1005))));assertEquals(first,second);
        byte[] bytes=Files.readAllBytes(root.resolve(String.valueOf(first.get("cues_key"))));assertEquals(first.get("cues_sha256"),CaptionRenderAssets.hash(bytes));
        JsonNode decoded=JSON.readTree(bytes);assertEquals(Set.of("schema_version","duration_ms","cues"),Set.copyOf(decoded.propertyNames()));assertEquals(text,decoded.path("cues").get(0).path("text").asText());assertEquals(5,decoded.path("cues").get(0).path("start_ms").longValue());
        assertEquals(new String(bytes,StandardCharsets.UTF_8),io.livecut.clips.ClipRequestService.canonicalJson(decoded));
        Files.write(root.resolve(String.valueOf(first.get("cues_key"))),new byte[]{0});assertEquals("CAPTION_RENDER_SOURCE_UNAVAILABLE",assertThrows(JobException.class,()->assets.cues(caption(List.of(cue(text,5,1005))))).getCode());
    }
    @Test void emptyAndQuantizedEmptyCuesAreExplicitErrorsWithoutPublishedInputs(){
        var assets=new CaptionRenderAssets(root,root.resolve("absent.otf"));assertEquals("CAPTION_RENDER_EMPTY",assertThrows(JobException.class,()->assets.cues(caption(List.of()))).getCode());
        assertEquals("CAPTION_RENDER_TIMING_UNSUPPORTED",assertThrows(JobException.class,()->assets.cues(caption(List.of(cue("短句",1,9))))).getCode());
        assertFalse(Files.exists(root.resolve("caption-render")));
    }
    @Test void missingOrWrongFixedFontFailsClosedWithoutPublishing()throws Exception{
        Path font=root.resolve("font.otf");var assets=new CaptionRenderAssets(root.resolve("store"),font);
        assertEquals("CAPTION_RENDER_FONT_UNAVAILABLE",assertThrows(JobException.class,assets::font).getCode());Files.write(font,new byte[]{1,2,3});
        assertEquals("CAPTION_RENDER_FONT_UNAVAILABLE",assertThrows(JobException.class,assets::font).getCode());assertFalse(Files.exists(root.resolve("store")));
    }
    @Test void renderTextRejectsDelAndC1BeforeQueuingWhileKeepingPlainUnicode(){
        var assets=new CaptionRenderAssets(root,root.resolve("absent.otf"));
        for(int code:List.of(127,128,133,159))assertEquals("CAPTION_RENDER_INVALID_INPUT",assertThrows(JobException.class,()->assets.cues(caption(List.of(cue("文字"+(char)code,0,1000))))).getCode());
        assertDoesNotThrow(()->assets.cues(caption(List.of(cue("中文\t换行\n\\N {原文} <b>标签</b>",0,1000)))));
    }
}
