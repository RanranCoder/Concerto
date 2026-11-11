package com.pams.module.agent.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class WordPageReviewTest {
    @Test void flagsOnlyThePagesWithVisibleGeometryProblems() throws Exception {
        var report = WordPageReview.parse("""
                <html><body><doc>
                <page width="612" height="792"><word xMin="30" yMin="40" xMax="90" yMax="58">标题</word></page>
                <page width="612" height="792"></page>
                <page width="612" height="792"><word xMin="600" yMin="40" xMax="630" yMax="58">表格</word></page>
                </doc></body></html>
                """);
        assertThat(report.status()).isEqualTo("ISSUES");
        assertThat(report.pages()).isEqualTo(3);
        assertThat(report.issues()).containsExactly(new WordPageReview.Issue(2, "EMPTY_PAGE"),
                new WordPageReview.Issue(3, "TEXT_OUTSIDE_PAGE"));
    }

    @Test void rendererAbsenceIsRecordedWithoutClaimingSuccessfulReview() {
        var report = new WordPageReview("", "").inspect(new byte[0]);
        assertThat(report.status()).isEqualTo("NOT_CONFIGURED");
        assertThat(report.pages()).isZero();
    }

    @Test void importsOrdinaryWordPackagesAndSkipsExternalOrActiveContentsBeforeRendering() throws Exception {
        byte[] ordinary = NativePlanDraftWriter.render("<p>内部策划书</p><table><tr><td>预算</td><td>3000</td></tr></table>");
        assertThat(ImportedWordRenderSafety.canRender(ordinary)).isTrue();

        byte[] externalLink;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createHyperlinkRun("https://example.invalid/document").setText("外部资源");
            document.write(output);
            externalLink = output.toByteArray();
        }
        assertThat(ImportedWordRenderSafety.canRender(externalLink)).isFalse();
        assertThat(new WordPageReview("missing-renderer", "missing-pdf-tool").inspectRevision(externalLink).status())
                .isEqualTo("UNSUPPORTED_PACKAGE");

        byte[] activeField;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().getCTP().addNewFldSimple().setInstr("INCLUDETEXT");
            document.write(output);
            activeField = output.toByteArray();
        }
        assertThat(ImportedWordRenderSafety.canRender(activeField)).isFalse();
    }
}
