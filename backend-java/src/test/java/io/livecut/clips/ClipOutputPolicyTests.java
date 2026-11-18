package io.livecut.clips;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClipOutputPolicyTests {
    @Test void onlyExplicitInstructionsToCombineUseOneOutput() {
        for (String instruction : new String[]{
                "把开头的qwen系列模型开源宣传和qwen3 embedding和reranker模型介绍剪辑到一起",
                "把两段剪到一起", "把访谈和开场合成一条", "把三个讲解拼成一个视频", "把不同类别的例子剪到一起"}) {
            assertEquals(ClipOutputPolicy.SINGLE_MONTAGE, ClipOutputPolicy.detect(instruction), instruction);
        }
        for (String instruction : new String[]{
                "别把两段剪到一起", "不要合成一条", "把两段剪到一起，另将两段分别导出",
                "提取两个章节", "截取讲到“把三段剪到一起”的原句", "分别找到两个模型的介绍"}) {
            assertNull(ClipOutputPolicy.detect(instruction), instruction);
        }
    }
}
