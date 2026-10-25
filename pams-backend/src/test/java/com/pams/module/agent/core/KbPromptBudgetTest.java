package com.pams.module.agent.core;
import com.pams.module.knowledge.retriever.ScoredChunk;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class KbPromptBudgetTest {
    ScoredChunk chunk(int id,String text) { return new ScoredChunk(id,"manual",(long)id,"manual:"+id,0,"资料"+id,"正文",text,"T1","normal",null,1.0); }
    @Test void exactPromptSetExcludesEmptyAndOverBudgetSources() {
        List<ScoredChunk> candidates=new ArrayList<>(); candidates.add(chunk(0," "));
        for(int i=1;i<=10;i++) candidates.add(chunk(i,"事".repeat(700)));
        var actual=KbPrompts.selectFragments(candidates);
        assertThat(actual).hasSize(6);
        String prompt=KbPrompts.buildUserMessage("问题",actual);
        assertThat(prompt).contains("[6]","资料6").doesNotContain("资料7","[7]");
        var mapped=new CitationMapper(List.of()).map("已知事实[7]",actual,200);
        assertThat(mapped.citations()).isEmpty();
    }
    @Test void citationCannotExposeAnUnsentTailAndMetadataMatchesActualInput() {
        var original=chunk(1,"原".repeat(610)+"未发送的尾部");
        var selected=KbPrompts.selectFragments(List.of(original));
        assertThat(selected.getFirst().content()).hasSize(600).doesNotContain("未发送的尾部").contains("已截断");
        var mapped=new CitationMapper(List.of()).map("依据[1]",selected,10000);
        assertThat(mapped.citations().getFirst().snippet()).doesNotContain("未发送的尾部");
        assertThat(KbPrompts.fragmentMetadata(selected.getFirst())).containsEntry("truncated",true).containsEntry("chars",600);
        assertThat(KbPrompts.fragmentMetadata(selected.getFirst()).get("sha256").toString()).hasSize(64);
    }
}
