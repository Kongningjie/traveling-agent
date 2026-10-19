package com.lawrence.travel.bootstrap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
/** 受支持四类各3份合成原件，不含真实用户资料。 */
final class DocumentFixtures {
    /** 命名空间。 */
    private DocumentFixtures() { }
    /** @param root 临时目录 @return 12个有效夹具，PDF／DOCX含页或表格变化 */
    static List<Path> valid(Path root) throws Exception {
        var result=new java.util.ArrayList<Path>();
        for(int i=1;i<=3;i++) {
            Path pdf=root.resolve("trip"+i+".pdf");pdf(pdf,i);result.add(pdf);
            Path docx=root.resolve("trip"+i+".docx");try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument()) {doc.createParagraph().createRun().setText("城市旅行计划 "+i);if(i>1)doc.createTable().getRow(0).getCell(0).setText("Day one museum");try(var out=Files.newOutputStream(docx)){doc.write(out);}}result.add(docx);
            for(String kind:List.of("txt","md")) {Path text=root.resolve("trip"+i+"."+kind);Files.writeString(text,(kind.equals("md")?"# 旅行计划\n":"")+"杭州游览计划 "+i+"\n预算和交通尚待确认");result.add(text);}
        }return result;
    }
    /** @param path 目标 @param pages 页数；纯文本PDF，不借助系统字体 */
    static void pdf(Path path,int pages) throws Exception {try(var doc=new org.apache.pdfbox.pdmodel.PDDocument()) {for(int i=0;i<pages;i++) {var page=new org.apache.pdfbox.pdmodel.PDPage();doc.addPage(page);try(var out=new org.apache.pdfbox.pdmodel.PDPageContentStream(doc,page)) {out.beginText();out.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);out.newLineAtOffset(50,700);out.showText("Travel itinerary page "+i);out.endText();}}doc.save(path.toFile());}}
}
