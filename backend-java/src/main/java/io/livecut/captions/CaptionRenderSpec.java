package io.livecut.captions;

import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Exact v1 frozen contract; reused by the export reader and result verifier. */
public final class CaptionRenderSpec {
    private CaptionRenderSpec() {}
    public static final String PROFILE="h264-aac-burn-v1",VERSION="caption-burn-v1";
    public static final String FONT_PROFILE="noto-sans-cjk-sc-2.004",FONT_FAMILY="Noto Sans CJK SC";
    public static final String FONT_HASH="2c76254f6fc379fddfce0a7e84fb5385bb135d3e399294f6eeb6680d0365b74b";
    public static final long FONT_SIZE=16437364,MAX_SAFE=9007199254740991L;
    public static final Set<String> STYLES=Set.of("WHITE_OUTLINE_V1","DARK_BOX_V1"),FRAMES=Set.of("SOURCE","LANDSCAPE_720","PORTRAIT_720","PORTRAIT_BLUR_720");
    public static final Set<String> INPUT_REQUIRES_NEW_RENDER=Set.of("CAPTION_RENDER_GLYPH_UNSUPPORTED","CAPTION_RENDER_TEXT_TOO_LARGE","CAPTION_RENDER_TIMING_UNSUPPORTED");
    public static boolean requiresNewRender(Object code){return code instanceof String&&INPUT_REQUIRES_NEW_RENDER.contains(code);}
    public record OutputSize(int width,int height) {}
    public static OutputSize outputSize(String frame,int sourceWidth,int sourceHeight){
        if(frame==null||sourceWidth<2||sourceWidth>4096||sourceHeight<2||sourceHeight>4096)throw invalid();
        return switch(frame){
            case "SOURCE"->new OutputSize((sourceWidth+1)/2*2,(sourceHeight+1)/2*2);
            case "LANDSCAPE_720"->new OutputSize(1280,720);
            case "PORTRAIT_720","PORTRAIT_BLUR_720"->new OutputSize(720,1280);
            default->throw invalid();
        };
    }
    public static void validate(JsonNode value){
        fields(value,Set.of("schema_version","profile_version","source_export_id","source_clip_id","source_snapshot_hash","input_media","captions","style_preset","frame_preset","output","font","timing_basis"));
        if(number(value,"schema_version",1,1)!=1||!VERSION.equals(text(value,"profile_version"))||!"CLIP_RELATIVE_ASS_CS_FLOOR".equals(text(value,"timing_basis")))throw invalid();
        uuid(text(value,"source_export_id"));uuid(text(value,"source_clip_id"));hash(text(value,"source_snapshot_hash"));
        String style=text(value,"style_preset"),frame=text(value,"frame_preset");if(!STYLES.contains(style)||!FRAMES.contains(frame))throw invalid();
        JsonNode input=value.path("input_media");fields(input,Set.of("key","sha256","size_bytes","duration_ms","width","height","has_audio"));
        key(text(input,"key"));hash(text(input,"sha256"));number(input,"size_bytes",1,MAX_SAFE);number(input,"duration_ms",1,MAX_SAFE);
        int width=(int)number(input,"width",2,4096),height=(int)number(input,"height",2,4096);if(!input.path("has_audio").isBoolean())throw invalid();
        JsonNode captions=value.path("captions");fields(captions,Set.of("revision","content_hash","cues_key","cues_sha256","duration_ms"));
        number(captions,"revision",0,MAX_SAFE);hash(text(captions,"content_hash"));key(text(captions,"cues_key"));hash(text(captions,"cues_sha256"));number(captions,"duration_ms",1,MAX_SAFE);
        JsonNode output=value.path("output");fields(output,Set.of("width","height"));long outWidth=number(output,"width",2,4096),outHeight=number(output,"height",2,4096);
        var expected=outputSize(frame,width,height);
        if(outWidth!=expected.width()||outHeight!=expected.height())throw invalid();
        JsonNode font=value.path("font");fields(font,Set.of("profile_id","family","key","sha256","size_bytes"));
        if(!FONT_PROFILE.equals(text(font,"profile_id"))||!FONT_FAMILY.equals(text(font,"family"))||!FONT_HASH.equals(text(font,"sha256"))||number(font,"size_bytes",FONT_SIZE,FONT_SIZE)!=FONT_SIZE)throw invalid();
        key(text(font,"key"));
    }
    public static void fields(JsonNode value,Set<String> names){if(value==null||!value.isObject()||!Set.copyOf(value.propertyNames()).equals(names))throw invalid();}
    public static long number(JsonNode value,String name,long minimum,long maximum){JsonNode n=value.path(name);if(!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<minimum||n.longValue()>maximum)throw invalid();return n.longValue();}
    public static String text(JsonNode value,String name){JsonNode n=value.path(name);if(!n.isString()||n.stringValue().isEmpty())throw invalid();return n.stringValue();}
    public static void hash(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    public static void key(String value){if(value==null||value.length()>1024||!value.matches("[A-Za-z0-9_./-]+")||value.startsWith("/"))throw invalid();for(String part:value.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw invalid();}
    private static void uuid(String value){try{if(!UUID.fromString(value).toString().equals(value))throw invalid();}catch(RuntimeException error){throw invalid();}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid frozen caption render specification");}
}
