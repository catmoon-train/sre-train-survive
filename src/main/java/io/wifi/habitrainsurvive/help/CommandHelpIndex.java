package io.wifi.habitrainsurvive.help;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.*;
import java.util.*;

/** Captures only executable routes added/replaced by this mod, including aliases into owned subtrees. */
public final class CommandHelpIndex<S> {
    private record State<S>(List<CommandNode<S>> nodes, Command<S> command, CommandNode<S> redirect) {
        CommandNode<S> leaf() { return nodes.get(nodes.size() - 1); }
    }
    public static final class Snapshot<S> {
        private final Map<String, State<S>> paths = new LinkedHashMap<>();
    }
    public record Entry<S>(String syntax, String literalPath, String suggestion, List<CommandNode<S>> gates,
                           HelpMetadata.Rule info, boolean documented) {
        public Entry { gates = List.copyOf(gates); }
        public boolean allows(S source) { return gates.stream().allMatch(node -> node.canUse(source)); }
    }
    private final List<Entry<S>> entries;
    private final HelpMetadata metadata;
    private CommandHelpIndex(List<Entry<S>> entries, HelpMetadata metadata) { this.entries = List.copyOf(entries); this.metadata = metadata; }
    public List<Entry<S>> entries() { return entries; }
    public HelpMetadata metadata() { return metadata; }
    public List<String> missingDescriptions() { return entries.stream().filter(entry -> !entry.documented).map(Entry::literalPath).distinct().sorted().toList(); }
    public List<Entry<S>> inCategory(String id) { return id.equals("all") ? entries : entries.stream().filter(entry -> entry.info.category().equals(id)).toList(); }

    public static <S> Snapshot<S> snapshot(CommandDispatcher<S> dispatcher) {
        Snapshot<S> result = new Snapshot<>();
        Set<CommandNode<S>> stack = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CommandNode<S> child : dispatcher.getRoot().getChildren()) walk(child, List.of(), result, stack);
        return result;
    }
    private static <S> void walk(CommandNode<S> node, List<CommandNode<S>> prefix, Snapshot<S> result, Set<CommandNode<S>> stack) {
        if (!stack.add(node)) return;
        List<CommandNode<S>> path = new ArrayList<>(prefix); path.add(node);
        result.paths.put(syntax(path), new State<>(List.copyOf(path), node.getCommand(), node.getRedirect()));
        for (CommandNode<S> child : node.getChildren()) walk(child, path, result, stack);
        stack.remove(node);
    }
    public static <S> CommandHelpIndex<S> between(Snapshot<S> before, CommandDispatcher<S> dispatcher, HelpMetadata metadata) {
        Snapshot<S> after = snapshot(dispatcher);
        Map<String, State<S>> owned = new LinkedHashMap<>();
        for (var pair : after.paths.entrySet()) {
            State<S> old = before.paths.get(pair.getKey()), state = pair.getValue();
            if (state.command != null && (old == null || old.command != state.command)) owned.put(pair.getKey(), state);
        }
        // Only newly registered redirects are expanded, not vanilla execute -> root or unrelated mods.
        List<State<S>> physical = List.copyOf(owned.values());
        for (var pair : after.paths.entrySet()) {
            State<S> alias = pair.getValue(), old = before.paths.get(pair.getKey());
            if (alias.redirect == null || old != null && old.redirect == alias.redirect) continue;
            for (State<S> target : physical) {
                int index = identityIndex(target.nodes, alias.redirect);
                // Bare redirects do not inherit the target root's executor in Brigadier.
                if (index < 0 || index + 1 == target.nodes.size()) continue;
                List<CommandNode<S>> path = new ArrayList<>(alias.nodes);
                path.addAll(target.nodes.subList(index + 1, target.nodes.size()));
                owned.putIfAbsent(syntax(path), new State<>(List.copyOf(path), target.command, null));
            }
        }
        List<Entry<S>> entries = new ArrayList<>();
        for (State<S> state : owned.values()) {
            String literal = state.nodes.stream().filter(node -> node instanceof LiteralCommandNode<?>)
                    .map(CommandNode::getName).reduce((a, b) -> a + " " + b).orElseThrow();
            HelpMetadata.Match match = metadata.match(literal);
            StringBuilder suggestion = new StringBuilder("/");
            boolean first = true;
            for (CommandNode<S> node : state.nodes) {
                if (node instanceof ArgumentCommandNode<?, ?>) { suggestion.append(' '); break; }
                if (!first) suggestion.append(' ');
                suggestion.append(node.getName()); first = false;
            }
            entries.add(new Entry<>(syntax(state.nodes), literal, suggestion.toString(), state.nodes, match.rule(), match.exact()));
        }
        entries.sort(Comparator.<Entry<S>>comparingInt(entry -> HelpMetadata.categoryOrder(entry.info.category())).thenComparing(Entry::syntax));
        return new CommandHelpIndex<>(entries, metadata);
    }
    private static <S> int identityIndex(List<CommandNode<S>> nodes, CommandNode<S> target) {
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i) == target) return i;
        return -1;
    }
    private static <S> String syntax(List<CommandNode<S>> nodes) {
        return "/" + nodes.stream().map(CommandNode::getUsageText).reduce((a, b) -> a + " " + b).orElse("");
    }
}
