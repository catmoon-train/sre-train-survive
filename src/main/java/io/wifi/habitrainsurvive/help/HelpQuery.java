package io.wifi.habitrainsurvive.help;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

/** Greedy built-in string argument keeps server-only help compatible with vanilla clients. */
public record HelpQuery(String category, int page) {
    public static HelpQuery parse(String text) {
        StringReader reader = new StringReader(text.trim());
        if (!reader.canRead()) return new HelpQuery("all", 1);
        String first = token(reader);
        reader.skipWhitespace();
        if (first.matches("[+-]?\\d+")) {
            if (reader.canRead()) throw new IllegalArgumentException("用法: /habi help <页码> 或 /habi help <分类> [页码]");
            return new HelpQuery("all", pageNumber(first));
        }
        String category = HelpMetadata.categoryId(first);
        if (category == null) throw new IllegalArgumentException("未知分类“" + first + "”；使用 /habi help categories 查看分类");
        int page = reader.canRead() ? pageNumber(token(reader)) : 1;
        reader.skipWhitespace();
        if (reader.canRead()) throw new IllegalArgumentException("分类后只能有一个页码，例如 /habi help 商城 2");
        return new HelpQuery(category, page);
    }
    private static String token(StringReader reader) {
        if (StringReader.isQuotedStringStart(reader.peek())) {
            try { return reader.readQuotedString(); }
            catch (CommandSyntaxException e) { throw new IllegalArgumentException("分类名称的引号未闭合"); }
        }
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
        return reader.getString().substring(start, reader.getCursor());
    }
    private static int pageNumber(String text) {
        try { int page = Integer.parseInt(text); if (page > 0) return page; }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("页码必须是大于0的有效整数");
    }
}
