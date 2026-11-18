package io.wifi.habitrainsurvive.help;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.*;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandHelpIndexTest {
    private static LiteralArgumentBuilder<Integer> literal(String name) { return LiteralArgumentBuilder.literal(name); }
    private static RequiredArgumentBuilder<Integer, String> word(String name) { return RequiredArgumentBuilder.argument(name, StringArgumentType.word()); }
    private static HelpMetadata metadata() {
        return HelpMetadata.read(new StringReader("""
                {"aliases":{"res":"claim"},"commands":[
                {"path":"claim","category":"claim","description":"领地菜单","permission":"所有玩家"},
                {"path":"claim create","category":"claim","description":"创建领地","permission":"所有玩家；执行时检查费用"},
                {"path":"claim flag","category":"claim","description":"修改保护","permission":"领地主人或OP2"},
                {"path":"title","category":"survival","description":"自定义称号菜单","permission":"OP2"},
                {"path":"title create","category":"survival","description":"新建称号","permission":"OP2"}]}
                """));
    }
    @Test void onlyNewExecutorsAreIncludedAndVanillaSharedBranchesRemainExcluded() {
        var dispatcher = new CommandDispatcher<Integer>();
        dispatcher.register(literal("unrelated").executes(ctx -> 1));
        dispatcher.register(literal("title").requires(level -> level >= 2).then(word("vanillaTarget").executes(ctx -> 1)));
        var before = CommandHelpIndex.snapshot(dispatcher);
        dispatcher.register(literal("title").executes(ctx -> 2).then(literal("create").requires(level -> level >= 2).executes(ctx -> 3)));
        var index = CommandHelpIndex.between(before, dispatcher, metadata());
        assertEquals(List.of("/title", "/title create"), index.entries().stream().map(CommandHelpIndex.Entry::syntax).toList());
        assertFalse(index.entries().getFirst().allows(0));
        assertTrue(index.entries().getFirst().allows(2));
        assertTrue(index.missingDescriptions().isEmpty());
    }
    @Test void redirectAliasesIncludeOwnedChildrenButNotNonExecutableBareAlias() {
        var dispatcher = new CommandDispatcher<Integer>(); var before = CommandHelpIndex.snapshot(dispatcher);
        var root = dispatcher.register(literal("claim").executes(ctx -> 1).then(literal("create").then(word("name").executes(ctx -> 1))));
        dispatcher.register(literal("res").redirect(root));
        var index = CommandHelpIndex.between(before, dispatcher, metadata());
        assertEquals(Set.of("/claim", "/claim create <name>", "/res create <name>"), new HashSet<>(index.entries().stream().map(CommandHelpIndex.Entry::syntax).toList()));
        var alias = index.entries().stream().filter(entry -> entry.syntax().startsWith("/res")).findFirst().orElseThrow();
        assertEquals("创建领地", alias.info().description());
        assertEquals("/res create ", alias.suggestion());
        assertTrue(index.missingDescriptions().isEmpty());
    }
    @Test void literalsAfterArgumentsUseSpecificDescriptionsAndSafeSuggestions() {
        var dispatcher = new CommandDispatcher<Integer>(); var before = CommandHelpIndex.snapshot(dispatcher);
        dispatcher.register(literal("claim").then(word("name").then(literal("flag").then(word("flag").executes(ctx -> 1)))));
        var entry = CommandHelpIndex.between(before, dispatcher, metadata()).entries().getFirst();
        assertEquals("/claim <name> flag <flag>", entry.syntax()); assertEquals("claim flag", entry.literalPath());
        assertEquals("修改保护", entry.info().description()); assertEquals("/claim ", entry.suggestion());
    }
    @Test void permissionsCheckEveryAncestorRatherThanJustLeaf() {
        var dispatcher = new CommandDispatcher<Integer>(); var before = CommandHelpIndex.snapshot(dispatcher);
        dispatcher.register(literal("claim").requires(level -> level >= 1).then(literal("flag").requires(level -> level >= 3).executes(ctx -> 1)));
        var entry = CommandHelpIndex.between(before, dispatcher, metadata()).entries().getFirst();
        assertFalse(entry.allows(0)); assertFalse(entry.allows(2)); assertTrue(entry.allows(3));
    }
    @Test void unrelatedExistingRedirectsAreNotExpandedIntoHelp() {
        var dispatcher = new CommandDispatcher<Integer>();
        dispatcher.register(literal("execute").redirect(dispatcher.getRoot()));
        var before = CommandHelpIndex.snapshot(dispatcher);
        dispatcher.register(literal("claim").executes(ctx -> 1));
        var entries = CommandHelpIndex.between(before, dispatcher, metadata()).entries();
        assertEquals(1, entries.size()); assertEquals("/claim", entries.getFirst().syntax());
    }
    @Test void fallbackIsVisibleButReportedAsMissingInsteadOfSilentlyClaimingCoverage() {
        var dispatcher = new CommandDispatcher<Integer>(); var before = CommandHelpIndex.snapshot(dispatcher);
        dispatcher.register(literal("claim").then(literal("new_feature").executes(ctx -> 1)));
        var index = CommandHelpIndex.between(before, dispatcher, metadata());
        assertEquals(List.of("claim new_feature"), index.missingDescriptions());
        assertEquals("claim", index.entries().getFirst().info().category());
    }
    @Test void ordinaryAliasWithOwnExecutorIsIncluded() {
        var dispatcher = new CommandDispatcher<Integer>(); var before = CommandHelpIndex.snapshot(dispatcher);
        var root = dispatcher.register(literal("claim").executes(ctx -> 1));
        dispatcher.register(literal("res").executes(ctx -> 1).redirect(root));
        assertEquals(2, CommandHelpIndex.between(before, dispatcher, metadata()).entries().size());
    }
}
