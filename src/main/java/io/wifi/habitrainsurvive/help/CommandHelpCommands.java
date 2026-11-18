package io.wifi.habitrainsurvive.help;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.wifi.habitrainsurvive.HabiTrainSurvive;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.*;
import net.minecraft.network.chat.*;
import java.io.IOException;
import java.util.*;

public final class CommandHelpCommands {
    public static final int PAGE_SIZE = 6;
    private static volatile CommandHelpIndex<CommandSourceStack> index;
    private CommandHelpCommands() { }
    public static CommandHelpIndex<CommandSourceStack> index() { return index; }
    public static void clear() { index = null; }
    public static void finish(CommandHelpIndex.Snapshot<CommandSourceStack> before, CommandDispatcher<CommandSourceStack> dispatcher) {
        try { index = CommandHelpIndex.between(before, dispatcher, HelpMetadata.loadDefaults()); }
        catch (IOException | RuntimeException e) { throw new IllegalStateException("指令帮助目录加载失败", e); }
        List<String> missing = index.missingDescriptions();
        if (!missing.isEmpty()) HabiTrainSurvive.LOGGER.warn("指令帮助缺少精确说明，共{}项: {}", missing.size(), missing.stream().limit(12).toList());
    }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("habi").then(Commands.literal("help")
                .executes(ctx -> show(ctx.getSource(), "all", 1))
                .then(Commands.literal("categories").executes(ctx -> show(ctx.getSource(), "categories", 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(ctx -> show(ctx.getSource(), "categories", IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.argument("query", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> {
                            String text = builder.getRemaining(); int split = text.lastIndexOf(' ');
                            if (split >= 0) {
                                String category = HelpMetadata.categoryId(text.substring(0, split).trim());
                                if (category != null && index != null) {
                                    int count = category.equals("categories") ? HelpMetadata.CATEGORIES.size() : index.inCategory(category).size();
                                    int size = category.equals("categories") ? 8 : PAGE_SIZE;
                                    int pages = Math.max(1, (count + size - 1) / size);
                                    List<String> choices = new ArrayList<>();
                                    for (int page = 1; page <= Math.min(pages, 20); page++) choices.add(String.valueOf(page));
                                    if (pages > 20) choices.add(String.valueOf(pages));
                                    return SharedSuggestionProvider.suggest(choices, builder.createOffset(builder.getStart() + split + 1));
                                }
                            }
                            List<String> choices = new ArrayList<>(List.of("all", "categories", "1"));
                            HelpMetadata.CATEGORIES.forEach(category -> { choices.add(category.id()); choices.add(category.name()); });
                            return SharedSuggestionProvider.suggest(choices, builder);
                        })
                        .executes(ctx -> {
                            try { HelpQuery query = HelpQuery.parse(StringArgumentType.getString(ctx, "query")); return show(ctx.getSource(), query.category(), query.page()); }
                            catch (IllegalArgumentException e) { ctx.getSource().sendFailure(Component.literal(e.getMessage())); return 0; }
                        }))));
    }
    public static int show(CommandSourceStack source, String category, int pageNumber) {
        CommandHelpIndex<CommandSourceStack> snapshot = index;
        if (snapshot == null) { source.sendFailure(Component.literal("帮助目录尚未就绪，请稍候重试。")); return 0; }
        String selected = HelpMetadata.categoryId(category);
        if (selected == null) { source.sendFailure(Component.literal("未知分类；使用 /habi help categories 查看分类。")); return 0; }
        try {
            if (selected.equals("categories")) return categories(source, snapshot, pageNumber);
            HelpPage<CommandHelpIndex.Entry<CommandSourceStack>> page = HelpPage.of(snapshot.inCategory(selected), pageNumber, PAGE_SIZE);
            line(source, Component.literal("══ Habi 指令帮助 · " + HelpMetadata.categoryName(selected) + " ══").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            line(source, Component.literal("第 " + page.page() + "/" + page.pages() + " 页 · " + page.total() + " 种语法（含别名）").withStyle(ChatFormatting.GRAY));
            if (page.items().isEmpty()) line(source, Component.literal("此分类暂无指令。").withStyle(ChatFormatting.GRAY));
            for (var entry : page.items()) {
                boolean allowed = entry.allows(source);
                String usage = entry.syntax();
                if (entry.literalPath().equals("habi help") && usage.endsWith("<query>")) usage = "/habi help <分类或页码> [页码]";
                MutableComponent command = Component.literal(usage).withStyle(allowed ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY);
                command.withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, entry.suggestion()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("点击填入聊天框，不会自动执行\n" + entry.syntax() + "\n权限: " + entry.info().permission()))));
                line(source, command.append(Component.literal(" — " + entry.info().description()).withStyle(ChatFormatting.GRAY)));
                line(source, Component.literal(allowed ? "  [入口权限通过] " : "  [入口权限不足] ").withStyle(allowed ? ChatFormatting.GREEN : ChatFormatting.RED)
                        .append(Component.literal("所需权限: " + entry.info().permission()).withStyle(ChatFormatting.GRAY)));
            }
            line(source, Component.literal("提示：领主/成员身份、余额、物品、战斗等条件仍会在执行时检查。").withStyle(ChatFormatting.DARK_GRAY));
            line(source, navigation(selected, page.previous(), page.next()));
            line(source, categoryLinks());
            return 1;
        } catch (IllegalArgumentException e) { source.sendFailure(Component.literal(e.getMessage() + "；可点击分类目录重新选择。")); return 0; }
    }
    private static int categories(CommandSourceStack source, CommandHelpIndex<CommandSourceStack> snapshot, int number) {
        HelpPage<HelpMetadata.Category> page = HelpPage.of(HelpMetadata.CATEGORIES, number, 8);
        line(source, Component.literal("══ Habi 帮助分类 · " + page.page() + "/" + page.pages() + " 页 ══").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (HelpMetadata.Category category : page.items()) {
            int count = snapshot.inCategory(category.id()).size();
            line(source, nav("[" + category.name() + "]", "/habi help " + category.id() + " 1")
                    .append(Component.literal(" · " + category.id() + " · " + count + " 种语法").withStyle(ChatFormatting.GRAY)));
        }
        line(source, navigation("categories", page.previous(), page.next()));
        line(source, Component.literal("用法: /habi help <页码> 或 /habi help <分类> [页码]；支持中文分类和Tab补全。").withStyle(ChatFormatting.GRAY));
        return 1;
    }
    private static MutableComponent categoryLinks() {
        return nav("[分类目录]", "/habi help categories").append(Component.literal("  "))
                .append(nav("[商城]", "/habi help systemshop")).append(Component.literal("  "))
                .append(nav("[传送/家]", "/habi help travel")).append(Component.literal("  "))
                .append(nav("[领地]", "/habi help claim")).append(Component.literal("  "))
                .append(nav("[管理]", "/habi help admin"));
    }
    private static MutableComponent navigation(String category, int previous, int next) {
        MutableComponent row = Component.empty();
        if (previous > 0) row.append(nav("[上一页]", "/habi help " + category + " " + previous));
        else row.append(Component.literal("[上一页]").withStyle(ChatFormatting.DARK_GRAY));
        row.append(Component.literal("  ")).append(nav("[全部首页]", "/habi help 1")).append(Component.literal("  "));
        if (next > 0) row.append(nav("[下一页]", "/habi help " + category + " " + next));
        else row.append(Component.literal("[下一页]").withStyle(ChatFormatting.DARK_GRAY));
        return row;
    }
    private static MutableComponent nav(String name, String command) {
        return Component.literal(name).withStyle(ChatFormatting.GOLD).withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }
    private static void line(CommandSourceStack source, Component text) {
        if (source.getPlayer() != null) source.getPlayer().sendSystemMessage(text);
        else source.sendSuccess(() -> text, false);
    }
}
