package io.livecut.agent;

import static io.livecut.agent.AgentNoteModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import io.livecut.clips.ClipRequestService;
import io.livecut.jobs.JobException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Pure, deterministic public projection. Model text can never supply an image or hyperlink destination. */
public final class AgentNoteRenderer {
    private AgentNoteRenderer() {}
    private static final int MAX_BYTES=2*1024*1024;
    private static final Pattern INLINE=Pattern.compile("`([^`\\r\\n]+)`|\\*\\*([^*\\r\\n]+)\\*\\*");
    private static final Pattern HEADING=Pattern.compile("^(#{1,6})[ \\t]+(.+)$");
    private static final Pattern ITEM=Pattern.compile("^([-+*]|[0-9]{1,9}[.)])[ \\t]+(.+)$");
    private static final Pattern TOKEN=Pattern.compile("\\S+");
    private static final Pattern SCHEME=Pattern.compile("(?i)^[a-z][a-z0-9+.-]*:.*");
    private static final Set<String> FRAME_FIELDS=Set.of("frame_id","requested_ms","timestamp_ms","source_pts","source_time_base","sha256","bytes","width","height");
    private static final Set<String> CITATION_FIELDS=Set.of("utterance_id","start_ms","end_ms","text");

    public static Document render(Snapshot snapshot){
        if(snapshot==null)throw invalid();
        // Both accessors defensively clone: obtain them once, outside every frame loop.
        JsonNode result=snapshot.result();Map<String,byte[]> images=snapshot.images();
        if(result==null||!result.isObject()||images==null||snapshot.durationMs()<=0||snapshot.durationMs()>AgentVisualEvidence.SAFE_INTEGER)throw invalid();
        checkedStrings(result);
        for(String id:Arrays.asList(snapshot.turnId(),snapshot.sessionId(),snapshot.jobId(),snapshot.videoId()))uuid(id);
        for(String hash:Arrays.asList(snapshot.sourceHash(),snapshot.transcriptHash(),snapshot.resultHash()))hash(hash);
        identity(result,"turn_id",snapshot.turnId());identity(result,"session_id",snapshot.sessionId());identity(result,"job_id",snapshot.jobId());identity(result,"video_id",snapshot.videoId());
        identity(result,"source_hash",snapshot.sourceHash());identity(result,"transcript_hash",snapshot.transcriptHash());
        if(!ClipRequestService.sha(ClipRequestService.canonicalJson(result)).equals(snapshot.resultHash()))throw invalid();
        String version=text(result,"schema_version"),scope=text(result,"answer_scope"),question=text(result,"question"),answer=text(result,"answer"),filename=checked(snapshot.sourceFilename());
        if(!Set.of("1.0","1.1","1.2").contains(version)||!Set.of("VIDEO","GENERAL","INSUFFICIENT").contains(scope)||filename.isBlank())throw invalid();
        Long focus=null;if(version.equals("1.2")){focus=number(result,"focus_timestamp_ms");if(focus>=snapshot.durationMs())throw invalid();}
        else if(result.has("focus_timestamp_ms"))throw invalid();
        JsonNode citations=array(result,"citations",24),provenance=result.path("provenance");
        var publicProvenance=AgentAnswerValidator.JSON.createObjectNode();for(String key:List.of("text_model_id","prompt_version","retrieval_mode"))publicProvenance.put(key,text(provenance,key));
        var publicCitations=AgentAnswerValidator.JSON.createArrayNode();Set<String> utteranceIds=new HashSet<>();long previousEnd=-1;
        for(JsonNode citation:citations){
            exact(citation,CITATION_FIELDS);String id=text(citation,"utterance_id");long start=number(citation,"start_ms"),end=number(citation,"end_ms");text(citation,"text");
            if(!id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")||!utteranceIds.add(id)||end<=start||end>snapshot.durationMs()||start<previousEnd)throw invalid();previousEnd=end;
            publicCitations.add(citation.deepCopy());
        }
        Map<String,JsonNode> observed=new HashMap<>();JsonNode visualCitations=AgentAnswerValidator.JSON.createArrayNode();
        if(!version.equals("1.0")){
            visualCitations=array(result,"visual_citations",32);
            for(JsonNode frame:array(result,"visual_evidence",32)){
                exact(frame,FRAME_FIELDS);String id=frameId(text(frame,"frame_id"));if(observed.put(id,frame)!=null)throw invalid();
            }
        }
        List<JsonNode> selectedFrames=new ArrayList<>();var publicVisualCitations=AgentAnswerValidator.JSON.createArrayNode();Set<String> selectedIds=new HashSet<>();String previousId="";long imageBytes=0;
        for(JsonNode citation:visualCitations){
            exact(citation,Set.of("frame_id","explanation"));String id=frameId(text(citation,"frame_id"));text(citation,"explanation");JsonNode frame=observed.get(id);
            if(frame==null||!selectedIds.add(id)||id.compareTo(previousId)<=0)throw invalid();previousId=id;
            long bytes=number(frame,"bytes"),width=number(frame,"width"),height=number(frame,"height"),actual=number(frame,"timestamp_ms"),requested=number(frame,"requested_ms");number(frame,"source_pts");
            if(bytes<4||bytes>1024*1024||width<1||width>1280||height<1||height>1280||actual>=snapshot.durationMs()||requested>=snapshot.durationMs()
                    ||!text(frame,"source_time_base").matches("[1-9][0-9]{0,9}/[1-9][0-9]{0,9}"))throw invalid();hash(text(frame,"sha256"));
            byte[] image=images.get(id);
            if(image==null||image.length!=bytes||(image[0]&255)!=255||(image[1]&255)!=216||(image[image.length-2]&255)!=255||(image[image.length-1]&255)!=217
                    ||!sha(image).equals(frame.path("sha256").asText()))throw invalid();
            imageBytes+=bytes;if(imageBytes>16*1024*1024)throw tooLarge();selectedFrames.add(frame);publicVisualCitations.add(citation.deepCopy());
        }
        if(!images.keySet().equals(selectedIds))throw invalid();
        var source=AgentAnswerValidator.JSON.createObjectNode().put("schema_version",1)
            .put("turn_id",snapshot.turnId()).put("session_id",snapshot.sessionId()).put("job_id",snapshot.jobId()).put("video_id",snapshot.videoId())
            .put("source_hash",snapshot.sourceHash()).put("transcript_hash",snapshot.transcriptHash()).put("result_hash",snapshot.resultHash())
            .put("source_filename",filename).put("duration_ms",snapshot.durationMs()).put("question",question).put("answer_scope",scope);
        source.set("provenance",publicProvenance);source.set("citations",publicCitations);source.set("visual_citations",publicVisualCitations);
        var publicFrames=source.putArray("visual_evidence");selectedFrames.forEach(frame->publicFrames.add(frame.deepCopy()));
        if(focus!=null)source.put("focus_timestamp_ms",focus);
        byte[] sourceBytes=AgentAnswerValidator.JSON.writeValueAsBytes(source);if(sourceBytes.length>MAX_BYTES)throw tooLarge();

        var out=new LimitedText();out.append("# 视频问答学习笔记\n\n视频：").append(plainBlock(filename)).append("\n\n");
        out.append("回答范围：").append(scopeLabel(scope)).append("\n\n");
        out.append(switch(scope){case "GENERAL"->"这是通用知识补充，不能当作视频中已经说过的话。\n\n";case "INSUFFICIENT"->"当前视频证据不足；本笔记保留原回答的不确定性。\n\n";default->"以下为基于本轮视频依据的 AI 回答，仍需结合来源核对。\n\n";});
        if(focus!=null)out.append("用户定位提示：").append(time(focus)).append("（").append(Long.toString(focus)).append(" ms）。这是查找提示，不是已观察画面的证明；实际画面时间见下方。\n\n");
        out.append("## 问题\n\n").append(plainBlock(question)).append("\n\n## 回答\n\n").append(answerMarkdown(answer)).append("\n\n## 原句依据\n\n");
        if(publicCitations.isEmpty())out.append("本轮没有原句引用，不能把截图中的文字当作已读取的语音原句。\n\n");
        for(JsonNode citation:publicCitations){
            long start=citation.path("start_ms").longValue(),end=citation.path("end_ms").longValue();
            out.append("### ").append(plain(citation.path("utterance_id").asText())).append(" · ").append(time(start)).append(" → ").append(time(end)).append("\n\n")
                .append("原视频全局区间：").append(Long.toString(start)).append("–").append(Long.toString(end)).append(" ms。\n\n")
                .append(plainBlock(citation.path("text").asText())).append("\n\n");
        }
        out.append("## 画面依据\n\n");
        if(selectedFrames.isEmpty())out.append("本轮没有引用截图。\n\n");
        else out.append("下列为实际引用的截图；图片文件包含在 ZIP 中，复制的文字笔记不内嵌图片。画面是抽样，不含原始音轨，不等于连续观看；画面不是原句剪辑锚点。\n\n");
        for(int index=0;index<selectedFrames.size();index++){
            JsonNode frame=selectedFrames.get(index),citation=publicVisualCitations.get(index);String id=frame.path("frame_id").asText();long actual=frame.path("timestamp_ms").longValue();
            out.append("### ").append(id).append(" · ").append(time(actual)).append("\n\n").append(plainBlock(citation.path("explanation").asText())).append("\n\n")
                .append("- 实际全局时间：").append(Long.toString(actual)).append(" ms\n- 请求时间：").append(Long.toString(frame.path("requested_ms").longValue())).append(" ms\n- 源 PTS / time base：")
                .append(Long.toString(frame.path("source_pts").longValue())).append(" / ").append(code(frame.path("source_time_base").asText())).append("\n- JPEG：").append(code(imagePath(id)))
                .append("，").append(Long.toString(frame.path("bytes").longValue())).append(" bytes，").append(Long.toString(frame.path("width").longValue())).append(" × ").append(Long.toString(frame.path("height").longValue()))
                .append("\n- SHA-256：").append(code(frame.path("sha256").asText())).append("\n\n");
        }
        out.append("## 来源记录\n\n- 视频 hash：").append(code(snapshot.sourceHash())).append("\n- 转写 hash：").append(code(snapshot.transcriptHash())).append("\n- 回答 hash：").append(code(snapshot.resultHash()))
            .append("\n- 任务：").append(code(snapshot.jobId())).append("\n- 会话 / 轮次：").append(code(snapshot.sessionId())).append(" / ").append(code(snapshot.turnId()))
            .append("\n- 模型 / 提示版本：").append(plain(publicProvenance.path("text_model_id").asText())).append(" / ").append(plain(publicProvenance.path("prompt_version").asText())).append("\n\n")
            .append("本笔记直接整理已完成的回答，没有重新生成摘要，不包含原视频或完整转写，也没有创建剪辑任务。\n");
        String markdown=out.toString();var packaged=new LimitedText();packaged.append(markdown);
        if(!selectedFrames.isEmpty()){
            packaged.append("\n## 离线截图\n\n");
            for(JsonNode frame:selectedFrames){String id=frame.path("frame_id").asText();packaged.append("![").append(id).append(" · ").append(time(frame.path("timestamp_ms").longValue())).append("](").append(imagePath(id)).append(")\n\n");}
        }
        return new Document(markdown,packaged.toString(),sourceBytes);
    }

    private record Part(String marker,List<String> lines) {}
    private interface Block {}
    private record Paragraph(List<String> lines) implements Block {}
    private record Heading(int level,String text) implements Block {}
    private record Items(boolean ordered,List<Part> items) implements Block {}
    private record Space(int lines) implements Block {}
    private static String answerMarkdown(String text){
        List<Block> blocks=new ArrayList<>();Paragraph paragraph=null;Items list=null;
        for(String line:normalize(checked(text)).split("\n",-1)){
            if(line.isBlank()){paragraph=null;list=null;Block last=blocks.isEmpty()?null:blocks.getLast();if(last instanceof Space space)blocks.set(blocks.size()-1,new Space(space.lines()+1));else blocks.add(new Space(1));continue;}
            Matcher heading=HEADING.matcher(line),item=ITEM.matcher(line);
            if(heading.matches()){blocks.add(new Heading(Math.min(6,heading.group(1).length()+2),heading.group(2)));paragraph=null;list=null;continue;}
            if(item.matches()){
                boolean ordered=Character.isDigit(item.group(1).charAt(0));if(list==null||list.ordered()!=ordered){list=new Items(ordered,new ArrayList<>());blocks.add(list);}list.items().add(new Part(item.group(1),new ArrayList<>(List.of(item.group(2)))));paragraph=null;continue;
            }
            if(list!=null&&(line.startsWith("  ")||line.startsWith("\t"))){list.items().getLast().lines().add(line);continue;}
            list=null;if(paragraph==null){paragraph=new Paragraph(new ArrayList<>());blocks.add(paragraph);}paragraph.lines().add(line);
        }
        StringBuilder out=new StringBuilder();
        for(Block block:blocks){
            if(block instanceof Space space){out.append("\n".repeat(space.lines()+1));continue;}
            if(!out.isEmpty()&&out.charAt(out.length()-1)!='\n')out.append("\n\n");
            if(block instanceof Heading heading)out.append("#".repeat(heading.level())).append(' ').append(inline(heading.text()));
            else if(block instanceof Paragraph paragraphBlock)out.append(lines(paragraphBlock.lines(),""));
            else if(block instanceof Items items){
                boolean regular=regularNumbers(items);
                for(int i=0;i<items.items().size();i++){
                    if(i>0)out.append('\n');Part part=items.items().get(i);String marker=items.ordered()?(regular?part.marker():"- "+plain(part.marker())):part.marker();
                    out.append(marker).append(' ').append(lines(part.lines(),"    "));
                }
            }
        }
        return out.toString();
    }
    private static boolean regularNumbers(Items items){
        if(!items.ordered())return false;int first=-1;
        for(int i=0;i<items.items().size();i++){String marker=items.items().get(i).marker();if(!marker.matches("[1-9][0-9]{0,8}\\."))return false;int value=Integer.parseInt(marker.substring(0,marker.length()-1));if(i==0)first=value;else if(value!=first+i)return false;}
        return true;
    }
    private static String lines(List<String> lines,String indent){StringJoiner out=new StringJoiner("  \n"+indent);for(String line:lines)out.add(inline(line));return out.toString();}
    private static String inline(String text){
        Matcher matcher=INLINE.matcher(text);StringBuilder out=new StringBuilder();int cursor=0;
        while(matcher.find()){
            int start=matcher.start(),end=matcher.end();boolean isCode=matcher.group(1)!=null;char marker=isCode?'`':'*';int closing=end-(isCode?1:2);
            if(start>0&&text.charAt(start-1)==marker||end<text.length()&&text.charAt(end)==marker||escaped(text,start)||escaped(text,closing))continue;
            out.append(plain(text.substring(cursor,start)));
            if(isCode)out.append(code(matcher.group(1)));
            else{
                String content=matcher.group(2);int left=0,right=content.length();while(left<right&&Character.isWhitespace(content.charAt(left)))left++;while(right>left&&Character.isWhitespace(content.charAt(right-1)))right--;
                out.append(plain(content.substring(0,left)));if(left<right)out.append("**").append(inline(content.substring(left,right))).append("**");out.append(plain(content.substring(right)));
            }
            cursor=end;
        }
        return out.append(plain(text.substring(cursor))).toString();
    }
    private static boolean escaped(String text,int position){int slashes=0;while(position>0&&text.charAt(--position)=='\\')slashes++;return slashes%2==1;}
    private static String plainBlock(String text){StringJoiner out=new StringJoiner("  \n");for(String line:normalize(checked(text)).split("\n",-1))out.add(plain(line));return out.toString();}
    private static String plain(String text){
        Matcher token=TOKEN.matcher(text);StringBuilder out=new StringBuilder();int cursor=0;
        while(token.find()){
            out.append(escape(text.substring(cursor,token.start())));String value=token.group();
            boolean link=SCHEME.matcher(value).matches()||value.toLowerCase(Locale.ROOT).contains("www.")||value.contains("://")||value.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
            out.append(link?code(value):escape(value));cursor=token.end();
        }
        out.append(escape(text.substring(cursor)));
        // Only physical leading indentation needs shielding; ordinary spaces stay readable.
        if(!out.isEmpty()&&(out.charAt(0)==' '||out.charAt(0)=='\t')){char first=out.charAt(0);out.replace(0,1,first==' '?"&#32;":"&#9;");}
        return out.toString();
    }
    private static String escape(String text){
        StringBuilder out=new StringBuilder();for(int i=0;i<text.length();i++){char c=text.charAt(i);
            switch(c){case '&'->out.append("&amp;");case '<'->out.append("&lt;");case '>'->out.append("&gt;");
                default->{if(c<32&&c!='\t')out.append("\\u%04x".formatted((int)c));else if("\\`*_{}[]()#+-.!|~:".indexOf(c)>=0)out.append('\\').append(c);else out.append(c);}}
        }
        return out.toString();
    }
    private static String code(String text){int longest=0,run=0;for(int i=0;i<text.length();i++){if(text.charAt(i)=='`')longest=Math.max(longest,++run);else run=0;}String mark="`".repeat(longest+1);boolean padding=text.startsWith("`")||text.endsWith("`")||!text.isBlank()&&(text.startsWith(" ")||text.endsWith(" "));return mark+(padding?" ":"")+text+(padding?" ":"")+mark;}
    private static String normalize(String value){return value.replace("\r\n","\n").replace('\r','\n');}
    private static String scopeLabel(String scope){return switch(scope){case "VIDEO"->"视频依据（VIDEO）";case "GENERAL"->"通用知识补充（GENERAL）";default->"证据不足（INSUFFICIENT）";};}
    private static String time(long millis){return "%02d:%02d:%02d.%03d".formatted(millis/3600000,(millis/60000)%60,(millis/1000)%60,millis%1000);}
    private static String imagePath(String id){return "images/"+frameId(id)+".jpg";}
    private static String frameId(String id){if(!id.matches("f00(?:0[1-9]|[12][0-9]|3[0-2])"))throw invalid();return id;}
    private static void identity(JsonNode node,String field,String expected){if(!text(node,field).equals(expected))throw invalid();}
    private static void uuid(String value){try{if(value==null||!UUID.fromString(value).toString().equals(value))throw invalid();}catch(IllegalArgumentException failure){throw invalid();}}
    private static void hash(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw invalid();}
    private static String text(JsonNode node,String field){JsonNode value=node.path(field);if(!value.isString()||value.stringValue().isBlank())throw invalid();return checked(value.stringValue());}
    private static String checked(String value){if(value==null)throw invalid();for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))throw invalid();}else if(Character.isLowSurrogate(c))throw invalid();}return value;}
    private static void checkedStrings(JsonNode node){if(node.isString())checked(node.stringValue());else if(node.isObject()){for(String field:node.propertyNames()){checked(field);checkedStrings(node.path(field));}}else if(node.isArray())for(JsonNode item:node)checkedStrings(item);}
    private static long number(JsonNode node,String field){JsonNode value=node.path(field);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<0||value.longValue()>AgentVisualEvidence.SAFE_INTEGER)throw invalid();return value.longValue();}
    private static JsonNode array(JsonNode node,String field,int maximum){JsonNode value=node.path(field);if(!value.isArray()||value.size()>maximum)throw invalid();return value;}
    private static void exact(JsonNode node,Set<String> fields){if(!node.isObject()||!node.propertyNames().equals(fields))throw invalid();}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception error){throw new IllegalStateException(error);}}
    private static JobException invalid(){return new JobException(HttpStatus.CONFLICT,"AGENT_NOTE_SOURCE_INVALID","The verified note snapshot is inconsistent");}
    private static JobException tooLarge(){return new JobException(HttpStatus.PAYLOAD_TOO_LARGE,"AGENT_NOTE_TOO_LARGE","The complete note exceeds its document limit");}
    private static final class LimitedText {
        private final StringBuilder text=new StringBuilder();private int bytes;
        LimitedText append(String value){int size=value.getBytes(StandardCharsets.UTF_8).length;if(size>MAX_BYTES-bytes)throw tooLarge();bytes+=size;text.append(value);return this;}
        @Override public String toString(){return text.toString();}
    }
}
