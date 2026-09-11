package com.logicompress.supplement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 补充实验结果导出：同时写 CSV（原始数据，便于复核/重算）与 Markdown（论文可直接粘贴的表格）。
 *
 * <p>两种格式一一对应，文件名相同、后缀不同；Markdown 首行是表题，便于逐表贴进论文第 6 章。
 */
public final class SupplementTables {

    /** 数值格式：与论文表格一致（均值 2 位、字节率 4 位） */
    public static String r2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    public static String r4(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private SupplementTables() {
    }

    /** 写 CSV（UTF-8，无 BOM；列头 + 数据行） */
    public static void csv(Path dir, String name, String[] header, List<String[]> rows) throws IOException {
        Files.createDirectories(dir);
        List<String> lines = new ArrayList<>(rows.size() + 1);
        lines.add(String.join(",", header));
        for (String[] r : rows) lines.add(String.join(",", r));
        Files.write(dir.resolve(name + ".csv"), lines, StandardCharsets.UTF_8);
    }

    /**
     * 写 Markdown 表格（论文可直接粘贴）。
     *
     * @param title 表题，如 "表 6-7 静态合并 vs ST-DBSCAN 对比"
     * @param note  表下注释（口径说明），可为 null
     */
    public static void md(Path dir, String name, String title, String note,
                          String[] header, List<String[]> rows) throws IOException {
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(title).append("**\n\n");
        sb.append("| ").append(String.join(" | ", header)).append(" |\n");
        sb.append("|").append(String.join("|", java.util.Collections.nCopies(header.length, "---"))).append("|\n");
        for (String[] r : rows) sb.append("| ").append(String.join(" | ", r)).append(" |\n");
        if (note != null && !note.isEmpty()) sb.append("\n> ").append(note).append("\n");
        Files.write(dir.resolve(name + ".md"), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** CSV + Markdown 一起写 */
    public static void both(Path dir, String name, String title, String note,
                            String[] header, List<String[]> rows) throws IOException {
        csv(dir, name, header, rows);
        md(dir, name, title, note, header, rows);
    }
}
