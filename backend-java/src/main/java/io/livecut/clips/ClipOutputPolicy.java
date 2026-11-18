package io.livecut.clips;

import java.util.regex.Pattern;

/** New ordinary requests can explicitly ask for several subjects in one exported clip. */
final class ClipOutputPolicy {
    static final String SINGLE_MONTAGE = "single_montage_v1";
    static final String MONTAGE_PROMPT = "montage-clips-v1";
    static final int MONTAGE_PROTOCOL = 7;

    private static final Pattern JOIN = Pattern.compile(
        "(?:剪辑?|拼接?|合并)(?:到|在|成)一起|(?:合成|拼成|拼接成|合并成|剪成)(?:一条|一个)(?:视频|短片|成片)?");
    private static final Pattern SEPARATE_OUTPUTS = Pattern.compile(
        "(?:分别|各自|各|分开)(?:剪辑?|生成|输出|导出|做成)|(?:分成|拆成|导出|生成|输出)(?:为|成)?(?:两|多|[二三四五六七八九十]|[2-9])[条个](?:独立的?)?(?:视频|短片|成片)");
    private static final Pattern QUOTED = Pattern.compile("“[^”]*”|「[^」]*」|\"[^\"]*\"");
    private static final Pattern NEGATIVE = Pattern.compile(
        "不要|不用|不需要|不必|不能|不可以|不想|不可|不得|禁止|请勿|避免|无需|别把|别将|(?:不|别|勿)(?=剪|拼|合)");

    static String detect(String instruction) {
        String request = QUOTED.matcher(instruction).replaceAll(" ");
        if (SEPARATE_OUTPUTS.matcher(request).find()) return null;
        for (String clause : request.split("[，,。；;！!？?\\n]")) {
            if (JOIN.matcher(clause).find() && !NEGATIVE.matcher(clause).find()) return SINGLE_MONTAGE;
        }
        return null;
    }

    static int requiredProtocol(String policy, AnswerClipSource.Frozen answer) {
        return SINGLE_MONTAGE.equals(policy) ? MONTAGE_PROTOCOL : AnswerClipSource.requiredProtocol(answer);
    }

    private ClipOutputPolicy() {}
}
