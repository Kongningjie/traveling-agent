package com.lawrence.travel.infrastructure.document;

import com.lawrence.travel.application.document.Documents;
import com.lawrence.travel.application.document.DocumentFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** 只在每作业隔离容器或受控夹具测试中执行；Web装配不创建该解析器。 */
public final class IsolatedDocumentParser {
    private static final com.lawrence.travel.application.document.DocumentLimits LIMITS =
            com.lawrence.travel.application.document.DocumentLimits.from(System::getenv);
    public static final String VERSION = "pdfbox-3.0.8_poi-5.5.1_v1";

    /** 纯解析命名空间。 */
    private IsolatedDocumentParser() {}

    /**
     * @param file 固定只读原件
     * @param kind 白名单
     * @return 完整有界页段报告；不执行URL、脚本、宏或OCR
     */
    public static Documents.Report parse(Path file, String kind) {
        try {
            if (Files.size(file) == 0 || Files.size(file) > LIMITS.bytes())
                throw new DocumentFailure("DOCUMENT_INPUT_LIMIT");
            var sections = new ArrayList<Documents.Section>();
            switch (kind) {
                case "txt", "md" -> {
                    byte[] bytes = Files.readAllBytes(file);
                    var decoder =
                            java.nio.charset.StandardCharsets.UTF_8
                                    .newDecoder()
                                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(
                                            java.nio.charset.CodingErrorAction.REPORT);
                    String text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                    if (text.indexOf(0) >= 0) throw new DocumentFailure("DOCUMENT_ENCODING");
                    add(sections, 1, text.startsWith("\uFEFF") ? text.substring(1) : text);
                }
                case "pdf" -> {
                    try (var doc = Loader.loadPDF(file.toFile())) {
                        if (doc.isEncrypted()) throw new DocumentFailure("DOCUMENT_ENCRYPTED");
                        if (doc.getNumberOfPages() > LIMITS.pages()
                                || doc.getDocument().getXrefTable().size() > 20000)
                            throw new DocumentFailure("DOCUMENT_COMPLEXITY_LIMIT");
                        var catalog = doc.getDocumentCatalog();
                        if (catalog.getOpenAction() != null
                                || catalog.getNames() != null
                                        && (catalog.getNames().getJavaScript() != null
                                                || catalog.getNames().getEmbeddedFiles() != null))
                            throw new DocumentFailure("DOCUMENT_ACTIVE_CONTENT");
                        for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                            var stripper = new PDFTextStripper();
                            stripper.setStartPage(page);
                            stripper.setEndPage(page);
                            add(sections, page, stripper.getText(doc));
                        }
                    }
                }
                case "docx" -> {
                    zipCheck(file);
                    ZipSecureFile.setMinInflateRatio(0.01);
                    ZipSecureFile.setMaxEntrySize(LIMITS.expanded());
                    ZipSecureFile.setMaxTextSize(LIMITS.text());
                    try (var stream = Files.newInputStream(file);
                            var doc = new XWPFDocument(stream)) {
                        int paragraph = 0;
                        for (var element : doc.getBodyElements()) {
                            if (element instanceof org.apache.poi.xwpf.usermodel.XWPFParagraph p)
                                add(sections, ++paragraph, p.getText());
                            else if (element instanceof org.apache.poi.xwpf.usermodel.XWPFTable t)
                                add(sections, ++paragraph, t.getText());
                        }
                    }
                }
                default -> throw new DocumentFailure("DOCUMENT_UNSUPPORTED");
            }
            if (sections.stream().allMatch(s -> s.text().isBlank()))
                throw new DocumentFailure("DOCUMENT_NO_TEXT");
            return new Documents.Report(
                    VERSION, "OK", sections, "仅提取可读文本；未做OCR。页内顺序、复杂表格和版式可能不完整，请与原件核对；未读取文档内链接。");
        } catch (DocumentFailure rejected) {
            return failure(rejected.getMessage());
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException encrypted) {
            return failure("DOCUMENT_ENCRYPTED");
        } catch (java.nio.charset.CharacterCodingException encoding) {
            return failure("DOCUMENT_ENCODING");
        } catch (Exception corrupt) {
            return failure("DOCUMENT_CORRUPT");
        }
    }

    /**
     * @param sections 已读正文
     * @param position 页段
     * @param text 当前正文；完整内容超限则整件失败，不截断成功
     */
    private static void add(List<Documents.Section> sections, int position, String text) {
        if (sections.size() >= 10000
                || text.length() + sections.stream().mapToLong(s -> s.text().length()).sum()
                        > LIMITS.text()) throw new DocumentFailure("DOCUMENT_TEXT_LIMIT");
        if (!text.isBlank()) sections.add(new Documents.Section(position, text));
    }

    /**
     * @param path OOXML原件；在POI之前验证展开量、路径、条目数和XML实体；不访问外部资源
     */
    private static void zipCheck(Path path)
            throws java.io.IOException, javax.xml.stream.XMLStreamException {
        long total = 0;
        int count = 0;
        boolean document = false;
        var factory = javax.xml.stream.XMLInputFactory.newFactory();
        factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (var zip = new ZipFile(path.toFile())) {
            var entries = zip.entries();
            var names = new java.util.HashSet<String>();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                String name = e.getName();
                if (!names.add(name)
                        || ++count > 1000
                        || name.contains("..")
                        || name.startsWith("/")
                        || name.contains("\\")
                        || name.contains(":")
                        || name.toLowerCase(java.util.Locale.ROOT).contains("vbaproject")
                        || name.startsWith("word/embeddings/"))
                    throw new DocumentFailure("DOCUMENT_COMPLEXITY_LIMIT");
                if (e.isDirectory()) continue;
                byte[] bytes;
                try (var in = zip.getInputStream(e)) {
                    bytes = in.readNBytes((int) (LIMITS.expanded() - total + 1));
                }
                total += bytes.length;
                if (total > LIMITS.expanded()
                        || e.getCompressedSize() > 0
                                && bytes.length
                                        > Math.max(1024 * 1024, e.getCompressedSize() * 100))
                    throw new DocumentFailure("DOCUMENT_EXPANSION_LIMIT");
                if (name.equals("word/document.xml")) document = true;
                if (name.endsWith(".xml") || name.endsWith(".rels")) {
                    var xml =
                            factory.createXMLStreamReader(new java.io.ByteArrayInputStream(bytes));
                    try {
                        while (xml.hasNext()) {
                            int event = xml.next();
                            if (event == javax.xml.stream.XMLStreamConstants.DTD
                                    || event
                                            == javax.xml.stream.XMLStreamConstants.ENTITY_REFERENCE)
                                throw new DocumentFailure("DOCUMENT_ACTIVE_CONTENT");
                        }
                    } finally {
                        xml.close();
                    }
                }
            }
        }
        if (!document) throw new DocumentFailure("DOCUMENT_FORMAT_MISMATCH");
    }

    /**
     * @param code 固定原因
     * @return 不含异常或部分正文的失败报告
     */
    public static Documents.Report failure(String code) {
        return new Documents.Report(
                VERSION, code, List.of(), "文件未成功解析，不支持扫描件／OCR、加密及活动内容；可替换为文本型文件。");
    }

    /**
     * @param args 容器只接受固定解析模式及格式，不接受任意路径
     */
    public static void main(String[] args) {
        var result =
                args.length == 1
                        ? parse(Path.of("/input/document.bin"), args[0])
                        : failure("DOCUMENT_INPUT_LIMIT");
        System.out.print(new com.lawrence.travel.infrastructure.travel.TravelJson().encode(result));
    }
}
