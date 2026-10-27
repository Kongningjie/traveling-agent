import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.JavaFormatterOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 源码维护工具：通过内存字符串调用固定格式器，避免Windows文件映射后原地截断失败。 */
public final class FormatJavaSource {
    /** 工具类不可实例化。 */
    private FormatJavaSource() {}

    /**
     * 检查或统一源码排版；不修改import、修饰符顺序及字符串内容，不输出源码正文。
     *
     * @param args 首项为check/apply，其余为受脚本筛选的源码路径
     * @throws Exception 文件读写或固定格式器执行失败
     */
    public static void main(String[] args) throws Exception {
        boolean check = args[0].equals("check");
        var options = JavaFormatterOptions.builder()
                .style(JavaFormatterOptions.Style.AOSP)
                .formatJavadoc(false)
                .reorderModifiers(false)
                .build();
        var formatter = new Formatter(options);
        int changed = 0;
        for (int index = 1; index < args.length; index++) {
            var file = Path.of(args[index]);
            String source = Files.readString(file, StandardCharsets.UTF_8);
            // 仅formatSource，不调用修复import或重写长字符串的附加步骤。
            String formatted = formatter.formatSource(source);
            if (!formatted.equals(source)) {
                changed++;
                if (!check) {
                    // IDE可能映射原文件；同目录完整写入后替换，避免截断失败或留下半份源码。
                    Path pending = Files.createTempFile(file.toAbsolutePath().getParent(), ".source-format-", ".tmp");
                    try {
                        Files.writeString(pending, formatted, StandardCharsets.UTF_8);
                        Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(pending);
                    }
                }
            }
        }
        if (check && changed > 0) throw new IllegalStateException("JAVA_LAYOUT_INVALID_FILES=" + changed);
    }
}
