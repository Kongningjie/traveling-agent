package com.lawrence.travel.bootstrap;

import com.lawrence.travel.infrastructure.document.IsolatedDocumentParser;
import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** 仅合成夹具直调解析器；生产不在Web线程加载复杂原件。 */
class DocumentParserTest {
    @TempDir Path root;

    /** 四类各三份实际解析，必须有定位正文及不完整性说明。 */
    @Test
    void twelveValidFixturesHaveRealTextAndLocations() throws Exception {
        for (var path : DocumentFixtures.valid(root)) {
            var report =
                    IsolatedDocumentParser.parse(
                            path, path.toString().substring(path.toString().lastIndexOf('.') + 1));
            assertThat(report.code()).isEqualTo("OK");
            assertThat(report.sections()).isNotEmpty();
            assertThat(report.sections().getFirst().position()).isEqualTo(1);
            assertThat(report.notice()).contains("OCR", "表格");
        }
    }

    /** 不支持类型及损坏PDF／DOCX不能伪装READY。 */
    @Test
    void corruptAndUnsupportedFilesReject() throws Exception {
        var path = root.resolve("broken");
        Files.writeString(path, "not a document");
        for (var kind : java.util.List.of("pdf", "docx", "zip"))
            assertThat(IsolatedDocumentParser.parse(path, kind).code()).isNotEqualTo("OK");
    }

    /** 非UTF8和NUL不是可读文本。 */
    @Test
    void invalidUtf8AndBinaryTextReject() throws Exception {
        var p = root.resolve("text");
        Files.write(p, new byte[] {(byte) 0xff, (byte) 0xfe});
        assertThat(IsolatedDocumentParser.parse(p, "txt").code()).isEqualTo("DOCUMENT_ENCODING");
        Files.write(p, new byte[] {0, 1});
        assertThat(IsolatedDocumentParser.parse(p, "md").code()).isEqualTo("DOCUMENT_ENCODING");
    }

    /** 20万字符及10MiB边界严格，超限不截断成功。 */
    @Test
    void textAndByteLimitsRejectWithoutPartialSuccess() throws Exception {
        var p = root.resolve("large");
        Files.writeString(p, "x".repeat(200000));
        assertThat(IsolatedDocumentParser.parse(p, "txt").code()).isEqualTo("OK");
        Files.writeString(p, "x".repeat(200001));
        assertThat(IsolatedDocumentParser.parse(p, "md").code()).isEqualTo("DOCUMENT_TEXT_LIMIT");
        Files.write(p, new byte[10485761]);
        assertThat(IsolatedDocumentParser.parse(p, "txt").code()).isEqualTo("DOCUMENT_INPUT_LIMIT");
    }

    /** PDF最多100页，空文本层明确不做OCR。 */
    @Test
    void pdfPagesAndScannedDocumentReject() throws Exception {
        var p = root.resolve("pages.pdf");
        DocumentFixtures.pdf(p, 101);
        assertThat(IsolatedDocumentParser.parse(p, "pdf").code())
                .isEqualTo("DOCUMENT_COMPLEXITY_LIMIT");
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(p.toFile());
        }
        assertThat(IsolatedDocumentParser.parse(p, "pdf").code()).isEqualTo("DOCUMENT_NO_TEXT");
    }

    /** 带密码的PDF不可读取。 */
    @Test
    void encryptedPdfRejects() throws Exception {
        var p = root.resolve("encrypted.pdf");
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.protect(
                    new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                            "synthetic-owner",
                            "synthetic-reader",
                            new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            doc.save(p.toFile());
        }
        assertThat(IsolatedDocumentParser.parse(p, "pdf").code()).isEqualTo("DOCUMENT_ENCRYPTED");
    }

    /** zip路径穿越、宏及XML实体不交给POI。 */
    @Test
    void archivePathMacroAndEntitiesReject() throws Exception {
        for (String name :
                java.util.List.of("../outside", "word/vbaProject.bin", "word/document.xml")) {
            var p = root.resolve("active.docx");
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(p))) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                zip.write(
                        "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            assertThat(IsolatedDocumentParser.parse(p, "docx").code())
                    .isEqualTo(
                            "DOCUMENT_ACTIVE_CONTENT".equals(name)
                                    ? "DOCUMENT_ACTIVE_CONTENT"
                                    : name.equals("word/document.xml")
                                            ? "DOCUMENT_ACTIVE_CONTENT"
                                            : "DOCUMENT_COMPLEXITY_LIMIT");
        }
    }

    /** 高压缩比原件没有机会解压占满宿主机磁盘。 */
    @Test
    void zipExpansionRatioRejects() throws Exception {
        var p = root.resolve("bomb.docx");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(p))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write(new byte[2 * 1024 * 1024]);
            zip.closeEntry();
        }
        assertThat(IsolatedDocumentParser.parse(p, "docx").code())
                .isEqualTo("DOCUMENT_EXPANSION_LIMIT");
    }
}
