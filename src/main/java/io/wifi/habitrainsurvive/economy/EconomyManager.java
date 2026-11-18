package io.wifi.habitrainsurvive.economy;

import io.wifi.habitrainsurvive.data.ServerDataManager;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class EconomyManager {
    private static final EconomyManager INSTANCE = new EconomyManager();

    public static class Storage {
        public double startingBalance = 1000.0;
        public String currencySymbol = "金币";
        public Map<UUID, EconomyAccount> accounts = new ConcurrentHashMap<>();
        public List<TransactionRecord> recentTransactions = new ArrayList<>();
        public Map<String, ExchangeReceipt> exchangeReceipts = new LinkedHashMap<>();
    }

    private Storage storage = new Storage();
    private Path storagePath;

    public static EconomyManager get() {
        return INSTANCE;
    }

    public synchronized void load(Path path) {
        // A corrupt snapshot must not silently discard committed exchange receipts.
        try {
            this.storage = Files.exists(path)
                    ? ServerDataManager.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Storage.class)
                    : new Storage();
            if (this.storage == null) throw new IOException("经济数据为空");
        } catch (Exception e) {
            throw new IllegalStateException("经济数据加载失败，原文件未被覆盖: " + path, e);
        }
        storagePath = path;
        if (this.storage.accounts == null) {
            this.storage.accounts = new ConcurrentHashMap<>();
        }
        if (this.storage.recentTransactions == null) {
            this.storage.recentTransactions = new ArrayList<>();
        }
        if (this.storage.exchangeReceipts == null) this.storage.exchangeReceipts = new LinkedHashMap<>();
    }

    public synchronized void save(Path path) {
        ServerDataManager.writeJson(path, this.storage);
    }

    /** Existing UUID is authoritative; name fallback supports servers using different UUID modes. */
    public synchronized EconomyAccount exchangeAccount(UUID uuid, String name) {
        EconomyAccount account = uuid == null ? null : storage.accounts.get(uuid);
        if (account != null) return account;
        if (name == null || name.isBlank()) throw new NoSuchElementException("未找到生存金币账户，请先进入生存服");
        List<EconomyAccount> matches = storage.accounts.values().stream()
                .filter(a -> a.name != null && a.name.equalsIgnoreCase(name)).toList();
        if (matches.size() != 1) throw new NoSuchElementException("生存金币账户不存在或名称不唯一，请联系管理员");
        return matches.getFirst();
    }

    /** Called on the server thread. Commit balance and idempotency receipt together before publishing either. */
    public synchronized ExchangeReceipt exchangeAdjust(UUID uuid, String name, String requestId, long deltaCents) throws IOException {
        ExchangeReceipt.validate(requestId, deltaCents);
        EconomyAccount account = exchangeAccount(uuid, name);
        ExchangeReceipt previous = storage.exchangeReceipts.get(requestId);
        if (previous != null) {
            previous.check(account.uuid, deltaCents);
            return previous;
        }
        if (storagePath == null) throw new IOException("经济数据尚未初始化");
        ExchangeReceipt receipt = ExchangeReceipt.create(account.uuid, account.balance, deltaCents);
        Storage next = ServerDataManager.GSON.fromJson(ServerDataManager.GSON.toJson(storage), Storage.class);
        EconomyAccount updated = next.accounts.get(account.uuid);
        updated.balance = receipt.balanceCents() / 100.0;
        updated.lastUpdated = System.currentTimeMillis();
        next.exchangeReceipts.put(requestId, receipt);
        next.recentTransactions.add(new TransactionRecord(deltaCents < 0 ? account.uuid : null,
                deltaCents > 0 ? account.uuid : null, Math.abs(deltaCents) / 100.0, "VirtualProxy exchange " + requestId));
        if (next.recentTransactions.size() > 500) next.recentTransactions.removeFirst();
        persistExchange(storagePath, next);
        account.balance = updated.balance;
        account.lastUpdated = updated.lastUpdated;
        storage.exchangeReceipts = next.exchangeReceipts;
        storage.recentTransactions = next.recentTransactions;
        return receipt;
    }

    private static void persistExchange(Path path, Storage next) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".exchange.tmp");
        try {
            Files.writeString(temp, ServerDataManager.GSON.toJson(next), StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            // No non-atomic fallback: an unsupported filesystem must fail before money changes.
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }

    public String format(double amount) {
        return String.format("%.2f %s", amount, storage.currencySymbol);
    }

    public String getCurrencySymbol() {
        return storage.currencySymbol;
    }

    public EconomyAccount getOrCreateAccount(UUID uuid, String fallbackName) {
        return storage.accounts.compute(uuid, (id, existing) -> {
            if (existing == null) {
                return new EconomyAccount(id, fallbackName != null ? fallbackName : id.toString().substring(0, 8), storage.startingBalance);
            }
            if (fallbackName != null && !fallbackName.isBlank()) {
                existing.name = fallbackName;
            }
            return existing;
        });
    }

    public EconomyAccount getOrCreateAccount(ServerPlayer player) {
        return getOrCreateAccount(player.getUUID(), player.getGameProfile().getName());
    }

    public Optional<EconomyAccount> findByName(String name) {
        return storage.accounts.values().stream()
                .filter(acc -> acc.name != null && acc.name.equalsIgnoreCase(name))
                .findFirst();
    }

    public double getBalance(UUID uuid) {
        return getOrCreateAccount(uuid, null).balance;
    }

    public boolean hasBalance(UUID uuid, double amount) {
        return amount >= 0 && getBalance(uuid) >= amount - 1e-6;
    }

    public synchronized boolean withdraw(UUID uuid, double amount, String reason) {
        if (amount < 0) return false;
        EconomyAccount acc = getOrCreateAccount(uuid, null);
        if (acc.balance < amount - 1e-6) {
            return false;
        }
        acc.balance = Math.max(0.0, Math.round((acc.balance - amount) * 100.0) / 100.0);
        acc.lastUpdated = System.currentTimeMillis();
        recordTransaction(uuid, null, amount, reason);
        return true;
    }

    public synchronized void deposit(UUID uuid, double amount, String reason) {
        if (amount <= 0) return;
        EconomyAccount acc = getOrCreateAccount(uuid, null);
        acc.balance = Math.round((acc.balance + amount) * 100.0) / 100.0;
        acc.lastUpdated = System.currentTimeMillis();
        recordTransaction(null, uuid, amount, reason);
    }

    public synchronized void setBalance(UUID uuid, double amount, String reason) {
        EconomyAccount acc = getOrCreateAccount(uuid, null);
        acc.balance = Math.max(0.0, Math.round(amount * 100.0) / 100.0);
        acc.lastUpdated = System.currentTimeMillis();
        recordTransaction(null, uuid, acc.balance, "SET: " + reason);
    }

    public synchronized boolean transfer(UUID from, UUID to, double amount, String reason) {
        if (amount <= 0 || from.equals(to)) return false;
        if (!hasBalance(from, amount)) return false;
        EconomyAccount sender = getOrCreateAccount(from, null);
        EconomyAccount receiver = getOrCreateAccount(to, null);
        sender.balance = Math.max(0.0, Math.round((sender.balance - amount) * 100.0) / 100.0);
        receiver.balance = Math.round((receiver.balance + amount) * 100.0) / 100.0;
        sender.lastUpdated = System.currentTimeMillis();
        receiver.lastUpdated = System.currentTimeMillis();
        recordTransaction(from, to, amount, reason);
        return true;
    }

    private void recordTransaction(UUID from, UUID to, double amount, String reason) {
        storage.recentTransactions.add(new TransactionRecord(from, to, amount, reason));
        if (storage.recentTransactions.size() > 500) {
            storage.recentTransactions.remove(0);
        }
    }

    public List<EconomyAccount> getTopAccounts(int limit) {
        return storage.accounts.values().stream()
                .sorted(Comparator.comparingDouble((EconomyAccount a) -> a.balance).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    public double getStartingBalance() {
        return storage.startingBalance;
    }

    public void setStartingBalance(double amount) {
        if (!Double.isFinite(amount) || amount < 0 || amount > 1e12) throw new IllegalArgumentException("初始余额必须为0到1万亿");
        storage.startingBalance = Math.round(amount * 100) / 100.0;
        ServerDataManager.saveAll();
    }
}
