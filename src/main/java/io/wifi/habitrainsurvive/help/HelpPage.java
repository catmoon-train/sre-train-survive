package io.wifi.habitrainsurvive.help;

import java.util.List;

public record HelpPage<T>(List<T> items, int page, int pages, int total) {
    public HelpPage { items = List.copyOf(items); }
    public static <T> HelpPage<T> of(List<T> source, int page, int size) {
        if (size < 1) throw new IllegalArgumentException("每页数量必须大于0");
        int pages = source.isEmpty() ? 1 : 1 + (source.size() - 1) / size;
        if (page < 1 || page > pages) throw new IllegalArgumentException("页码必须为 1.." + pages);
        int start = (page - 1) * size;
        return new HelpPage<>(source.subList(start, Math.min(source.size(), start + size)), page, pages, source.size());
    }
    public int previous() { return page > 1 ? page - 1 : 0; }
    public int next() { return page < pages ? page + 1 : 0; }
}
