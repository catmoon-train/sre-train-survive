package io.wifi.habitrainsurvive.quest;

import java.util.ArrayList;
import java.util.List;

/** JSON data model. Public fields are intentional: this is the file format edited by the HTML tool. */
public final class QuestDefinition {
    private QuestDefinition() { }

    public static final class Task {
        public String type = "";
        public String target = "";
        public long amount = 1;
        public String display = "";

        public Task() { }
        public Task(String type, String target, long amount, String display) {
            this.type = type; this.target = target; this.amount = amount; this.display = display;
        }
    }

    public static final class Reward {
        public String type = "coin";
        public double amount;
        public String item = "";
        public int count = 1;

        public Reward() { }
        public Reward(String type, double amount, String item, int count) {
            this.type = type; this.amount = amount; this.item = item; this.count = count;
        }
    }

    public static final class Quest {
        public String id = "";
        public String title = "";
        public String description = "";
        public String icon = "minecraft:paper";
        public List<Task> tasks = new ArrayList<>();
        public List<Reward> rewards = new ArrayList<>();
        public int x;
        public int y;
        public List<String> requires = new ArrayList<>();
        public boolean hidden;

        public String label() { return title == null || title.isBlank() ? id : title; }
    }

    public static final class Chapter {
        public String id = "";
        public String title = "";
        public String description = "";
        public String icon = "minecraft:grass_block";
        public List<Quest> quests = new ArrayList<>();

        public String label() { return title == null || title.isBlank() ? id : title; }
    }

    public static final class Settings {
        public int dailyCount = 5;
        public int weeklyCount = 5;
        public String timeZone = "Asia/Shanghai";
    }

    public static final class Bundle {
        public Settings settings = new Settings();
        public List<Quest> daily = new ArrayList<>();
        public List<Quest> weekly = new ArrayList<>();
        public List<Chapter> chapters = new ArrayList<>();
    }
}
