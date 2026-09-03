package com.mills.jobsoverflow.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.util.Formatting;
import net.minecraft.entity.boss.BossBar;
import java.util.IdentityHashMap;

public final class JobsOverflowClient implements ClientModInitializer {
    public static final int MAX_LEVEL = 200;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = MinecraftClient.getInstance().runDirectory.toPath()
            .resolve("config").resolve("jobs-overflow.json");

    // Matches the default Jobs Reborn bossbar format:
    // Lvl 200 Miner: 5000/5000 xp (+125) $100
    private static final Pattern JOBS_BAR = Pattern.compile(
            "^Lvl\\s+(\\d+)\\s+(.+?):\\s*([0-9.,]+)\\s*/\\s*([0-9.,]+)\\s*xp(?:\\s*\\(([+-]?[0-9.,]+)\\))?.*$",
            Pattern.CASE_INSENSITIVE);

    private static final String UNKNOWN_SERVER = "unknown";

    // server key -> (job key -> overflow xp)
    private static final Map<String, Map<String, Double>> OVERFLOW = new LinkedHashMap<>();

    // names the mod will look for on the scoreboard, e.g. "cherry", "tulip"
    private static final Set<String> KNOWN_SERVERS = new LinkedHashSet<>();

    private static String currentServer = UNKNOWN_SERVER;
    private static String manualServer = null; // non-null while locked via /jobsoverflow setserver
    private static int tickCounter = 0;
    private static final Map<String, Double> LAST_GAIN = new LinkedHashMap<>();
    private static final Map<String, Integer> LAST_GAIN_TICK = new LinkedHashMap<>();
    private static final int STALE_TICKS = 100; // ~5 seconds of inactivity resets the sessions
    private static final Map<String, Integer> LAST_VIRTUAL_LEVEL = new LinkedHashMap<>();
    @Override
    public void onInitializeClient() {
        if (KNOWN_SERVERS.isEmpty()) {
            KNOWN_SERVERS.add("cherry");
            KNOWN_SERVERS.add("tulip");
            KNOWN_SERVERS.add("spirit");
            KNOWN_SERVERS.add("lotus");
        }
        load();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tickCounter++;
            if (tickCounter % 20 == 0) { // roughly once a second
                detectServer();
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("jobsoverflow")
                    .executes(ctx -> {
                        showAll();
                        return 1;
                    })
                    .then(ClientCommandManager.literal("all")
                            .executes(ctx -> {
                                showAllServers();
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("server")
                            .executes(ctx -> {
                                MinecraftClient client = MinecraftClient.getInstance();
                                if (client.player != null) {
                                    Formatting color = getServerColor(currentServer);
                                    client.player.sendMessage(
                                            prefixText()
                                                    .append(Text.literal("Current server is '"))
                                                    .append(Text.literal(capitalize(currentServer)).formatted(color))
                                                    .append(Text.literal("'" + (manualServer != null ? " (manually locked)" : " (auto-detected)"))),
                                            false);
                                }
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("setserver")
                            .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String name = normaliseJob(StringArgumentType.getString(ctx, "name"));
                                        manualServer = name;
                                        currentServer = name;
                                        KNOWN_SERVERS.add(name);
                                        save();
                                        send("Locked current server to '" + name + "'.");
                                        return 1;
                                    })))
                    .then(ClientCommandManager.literal("autoserver")
                            .executes(ctx -> {
                                manualServer = null;
                                send("Back to auto-detecting the server from the scoreboard.");
                                detectServer();
                                save();
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("display")
                            .then(ClientCommandManager.literal("xp")
                                    .executes(ctx -> {
                                        displayMode = "xp";
                                        save();
                                        send("Display set to raw XP numbers.");
                                        return 1;
                                    }))
                            .then(ClientCommandManager.literal("levels")
                                    .executes(ctx -> {
                                        displayMode = "levels";
                                        save();
                                        send("Display set to cosmetic levels.");
                                        return 1;
                                    }))
                            .then(ClientCommandManager.literal("off")
                                    .executes(ctx -> {
                                        displayMode = "off";
                                        save();
                                        send("Overflow display turned off.");
                                        return 1;
                                    })))
                    .then(ClientCommandManager.literal("addserver")
                            .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String name = normaliseJob(StringArgumentType.getString(ctx, "name"));
                                        KNOWN_SERVERS.add(name);
                                        save();
                                        send("Now watching for server '" + name + "' on the scoreboard.");
                                        return 1;
                                    })))
                    .then(ClientCommandManager.literal("reset")
                            .executes(ctx -> {
                                OVERFLOW.remove(currentServer);
                                save();
                                send("Reset all tracked XP for '" + currentServer + "'.");
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("resetall")
                            .executes(ctx -> {
                                OVERFLOW.clear();
                                save();
                                send("Reset all tracked XP for every server.");
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("resetjob")
                            .then(ClientCommandManager.argument("job", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String job = StringArgumentType.getString(ctx, "job");
                                        String key = normaliseJob(job);
                                        Map<String, Double> serverMap = OVERFLOW.get(currentServer);
                                        if (serverMap != null) {
                                            serverMap.remove(key);
                                        }
                                        save();
                                        send("Reset " + job + " on '" + currentServer + "'.");
                                        return 1;
                                    }))));
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> save());
    }

    public static void onBossBarName(BossBar bar, String rawTitle) {
        RAW_TITLES.put(bar, rawTitle);

        Matcher matcher = JOBS_BAR.matcher(rawTitle.replace('\u00a0', ' ').trim());
        if (!matcher.matches()) {
            return;
        }

        int level;
        double xp;
        double maxXp;
        double gain;
        try {
            level = Integer.parseInt(matcher.group(1));
            xp = parseNumber(matcher.group(3));
            maxXp = parseNumber(matcher.group(4));
            if (matcher.group(5) == null) {
                return;
            }
            gain = parseNumber(matcher.group(5));
        } catch (NumberFormatException ignored) {
            return;
        }

        boolean isMaxed = maxXp > 0 && xp >= maxXp - 0.0001;
        if (level < MAX_LEVEL || gain <= 0 || !isMaxed) {
            return;
        }

        String job = matcher.group(2).trim();
        String key = normaliseJob(job);
        String trackKey = currentServer + "|" + key;

        double lastGain = LAST_GAIN.getOrDefault(trackKey, 0.0);
        int lastTick = LAST_GAIN_TICK.getOrDefault(trackKey, -STALE_TICKS - 1);
        if (tickCounter - lastTick > STALE_TICKS) {
            lastGain = 0.0; // bar went quiet long enough, treat this as a fresh session
        }

        double delta = gain >= lastGain ? gain - lastGain : gain;

        LAST_GAIN.put(trackKey, gain);
        LAST_GAIN_TICK.put(trackKey, tickCounter);

        if (delta > 0) {
            Map<String, Double> serverMap = OVERFLOW.computeIfAbsent(currentServer, s -> new LinkedHashMap<>());
            serverMap.merge(key, delta, Double::sum);
            save();
            checkLevelUp(trackKey, job, maxXp, serverMap.get(key));
        }
    }

    public static Double getOverflow(String job) {
        Map<String, Double> serverMap = OVERFLOW.get(currentServer);
        if (serverMap == null) return 0.0;
        return serverMap.getOrDefault(normaliseJob(job), 0.0);
    }

    public static String getCurrentServer() {
        return currentServer;
    }

    public static ParsedJob parseFromBar(BossBar bar) {
        String raw = RAW_TITLES.get(bar);
        return raw == null ? null : parse(raw);
    }

    public static Text getOverrideName(BossBar bar) {
        if ("off".equals(displayMode)) {
            return null;
        }

        ParsedJob job = parseFromBar(bar);
        if (job == null || job.level() < MAX_LEVEL) {
            return null;
        }

        boolean isMaxed = job.maxXp() > 0 && job.xp() >= job.maxXp() - 0.0001;
        if (!isMaxed) {
            return null;
        }

        double overflow = getOverflow(job.job());

        if ("xp".equals(displayMode)) {
            String text = "Lvl " + job.level() + " " + job.job() + " (Overflow: " + formatNumber(overflow) + " XP)";
            return Text.literal(text);
        }

        if ("levels".equals(displayMode)) {
            VirtualLevel virtual = computeVirtualLevel(job.maxXp(), overflow);
            String text = "Lvl " + virtual.level() + " " + job.job()
                    + " " + formatNumber(virtual.xpInto()) + "/" + formatNumber(virtual.xpForLevel()) + " xp";
            return Text.literal(text);
        }

        return null;
    }

    private static String displayMode = "xp"; // "xp", "levels", or "off"
    private static final String MOD_NAME = "JobsFlow";

    private static MutableText prefixText() {
        return Text.literal("[" + MOD_NAME + "] ").formatted(Formatting.AQUA);
    }
    private static final Map<BossBar, String> RAW_TITLES = new IdentityHashMap<>();

    public static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001) {
            return String.format(Locale.ROOT, "%,d", (long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%,.2f", value);
    }

    public static ParsedJob parse(String rawTitle) {
        Matcher matcher = JOBS_BAR.matcher(rawTitle.replace('\u00a0', ' ').trim());
        if (!matcher.matches()) return null;
        try {
            int level = Integer.parseInt(matcher.group(1));
            double xp = parseNumber(matcher.group(3));
            double maxXp = parseNumber(matcher.group(4));
            double gain = matcher.group(5) == null ? 0 : parseNumber(matcher.group(5));
            return new ParsedJob(matcher.group(2).trim(), level, xp, maxXp, gain);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
// ---- server detection ----

    private static void detectServer() {
        if (manualServer != null) {
            currentServer = manualServer;
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }

        Scoreboard scoreboard = client.world.getScoreboard();
        ScoreboardObjective sidebar = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (sidebar == null) {
            return;
        }

        String found = findKnownServer(sidebar.getDisplayName().getString());

        if (found == null) {
            for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(sidebar)) {
                found = findKnownServer(entry.owner());

                if (found == null) {
                    Team team = scoreboard.getScoreHolderTeam(entry.owner());
                    if (team != null) {
                        String prefix = team.getPrefix() != null ? team.getPrefix().getString() : "";
                        String suffix = team.getSuffix() != null ? team.getSuffix().getString() : "";
                        found = findKnownServer(prefix + " " + suffix);
                    }
                }

                if (found != null) {
                    break;
                }
            }
        }

        if (found != null && !found.equals(currentServer)) {
            currentServer = found;
            sendServerChangeMessage(currentServer);
        }
    }

    private static String findKnownServer(String rawText) {
        if (rawText == null) return null;
        String stripped = rawText.replaceAll("\u00a7.", "").toLowerCase(Locale.ROOT);
        for (String server : KNOWN_SERVERS) {
            if (stripped.contains(server)) {
                return server;
            }
        }
        return null;
    }

    private static double parseNumber(String value) {
        return Double.parseDouble(value.replace(",", ""));
    }

    private static String normaliseJob(String job) {
        return job.trim().toLowerCase(Locale.ROOT);
    }

    private static final Map<String, int[]> JOB_GRADIENTS = new LinkedHashMap<>();
    static {
        JOB_GRADIENTS.put("farmer", new int[]{0x31C300, 0x88FF6A});
        putJobColor("digger", 0x8B4513);      // brown
        putJobColor("miner", 0xD3D3D3);       // light gray
        putJobColor("florist", 0xFF69B4);     // pink
        putJobColor("fisher", 0x3B82F6);      // blue
        putJobColor("hunter", 0xE53935);      // red
        putJobColor("rancher", 0xFF8C00);     // orange
        putJobColor("smither", 0x808080);     // gray
        putJobColor("woodcutter", 0x800000);  // maroon
    }

    private static void putJobColor(String job, int baseColor) {
        JOB_GRADIENTS.put(job, new int[]{baseColor, lighten(baseColor, 0.5)});
    }

    private static int lighten(int color, double amount) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = (int) (r + (255 - r) * amount);
        g = (int) (g + (255 - g) * amount);
        b = (int) (b + (255 - b) * amount);
        return (r << 16) | (g << 8) | b;
    }

    public static int[] getJobGradient(String job) {
        int[] g = JOB_GRADIENTS.get(normaliseJob(job));
        if (g == null) {
            int base = 0xFFAA00;
            return new int[]{base, lighten(base, 0.5)};
        }
        return g;
    }

    private static final Formatting[] COLOR_PALETTE = {

            Formatting.LIGHT_PURPLE, Formatting.YELLOW, Formatting.AQUA, Formatting.GREEN,
            Formatting.GOLD, Formatting.RED, Formatting.BLUE, Formatting.DARK_AQUA
    };

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder();
        for (String part : s.split(" ")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    private static Formatting getServerColor(String serverKey) {
        int index = 0;
        for (String s : KNOWN_SERVERS) {
            if (s.equals(serverKey)) break;
            index++;
        }
        return COLOR_PALETTE[index % COLOR_PALETTE.length];
    }

    private static void showAll() {
        Map<String, Double> serverMap = OVERFLOW.get(currentServer);
        if (serverMap == null || serverMap.isEmpty()) {
            sendColoredServerLine("No overflow XP tracked yet on '", currentServer, "'.");
            return;
        }
        sendColoredServerLine("Overflow XP for '", currentServer, "':");
        serverMap.forEach((job, xp) -> send("  " + job + ": " + formatNumber(xp) + " XP"));
    }

    private static void showAllServers() {
        if (OVERFLOW.isEmpty()) {
            send("No overflow XP tracked yet.");
            return;
        }
        OVERFLOW.forEach((server, jobs) -> {
            sendColoredServerLine("Server '", server, "':");
            jobs.forEach((job, xp) -> send("  " + job + ": " + formatNumber(xp) + " XP"));
        });
    }

    private static void send(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(prefixText().append(Text.literal(message)), false);
        }
    }

    private static void sendColoredServerLine(String before, String serverKey, String after) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        Formatting color = getServerColor(serverKey);
        client.player.sendMessage(
                prefixText()
                        .append(Text.literal(before))
                        .append(Text.literal(capitalize(serverKey)).formatted(color))
                        .append(Text.literal(after)),
                false);
    }
    private static void checkLevelUp(String trackKey, String jobDisplayName, double jobMaxXpAt200, double newTotalOverflow) {
        VirtualLevel virtual = computeVirtualLevel(jobMaxXpAt200, newTotalOverflow);
        int newLevel = virtual.level();
        int prevLevel = LAST_VIRTUAL_LEVEL.getOrDefault(trackKey, MAX_LEVEL + 1);
        if (newLevel > prevLevel) {
            sendLevelUpMessage(jobDisplayName, newLevel);
        }
        LAST_VIRTUAL_LEVEL.put(trackKey, newLevel);
    }

    private static void sendLevelUpMessage(String jobDisplayName, int newLevel) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        client.player.sendMessage(
                prefixText().append(
                        Text.literal("Congrats you have reached level " + newLevel + " in " + jobDisplayName + "!!!")
                                .formatted(Formatting.AQUA)),
                false);
    }

    private static void sendServerChangeMessage(String serverKey) {
        sendColoredServerLine("Detected server change to '", serverKey, "'.");
    }

    private static void load() {
        try {
            Files.createDirectories(FILE.getParent());
            if (!Files.exists(FILE)) {
                save();
                return;
            }
            try (Reader reader = Files.newBufferedReader(FILE)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

                if (root.has("knownServers")) {
                    JsonArray servers = root.getAsJsonArray("knownServers");
                    for (JsonElement el : servers) {
                        KNOWN_SERVERS.add(el.getAsString().toLowerCase(Locale.ROOT));
                    }
                }

                if (root.has("manualServer") && !root.get("manualServer").isJsonNull()) {
                    manualServer = root.get("manualServer").getAsString();
                    currentServer = manualServer;
                }

                if (root.has("displayMode")) {
                    displayMode = root.get("displayMode").getAsString();
                }

                if (root.has("overflow")) {
                    JsonObject servers = root.getAsJsonObject("overflow");
                    for (String serverKey : servers.keySet()) {
                        JsonObject jobs = servers.getAsJsonObject(serverKey);
                        Map<String, Double> jobMap = new LinkedHashMap<>();
                        for (String jobKey : jobs.keySet()) {
                            jobMap.put(jobKey, jobs.get(jobKey).getAsDouble());
                        }
                        OVERFLOW.put(serverKey, jobMap);
                    }
                } else if (root.has("jobs")) {
                    JsonObject jobs = root.getAsJsonObject("jobs");
                    Map<String, Double> jobMap = new LinkedHashMap<>();
                    for (String jobKey : jobs.keySet()) {
                        jobMap.put(jobKey, jobs.get(jobKey).getAsDouble());
                    }
                    OVERFLOW.put(UNKNOWN_SERVER, jobMap);
                    System.out.println("[Jobs Overflow] Migrated legacy save data into server bucket '" + UNKNOWN_SERVER + "'.");
                }
            }
        } catch (Exception e) {
            System.err.println("[Jobs Overflow] Could not load data: " + e.getMessage());
        }
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("maxLevel", MAX_LEVEL);
            root.addProperty("displayMode", displayMode);

            JsonArray servers = new JsonArray();
            KNOWN_SERVERS.forEach(servers::add);
            root.add("knownServers", servers);

            if (manualServer != null) {
                root.addProperty("manualServer", manualServer);
            }

            JsonObject overflow = new JsonObject();
            OVERFLOW.forEach((serverKey, jobs) -> {
                JsonObject jobsObj = new JsonObject();
                jobs.forEach(jobsObj::addProperty);
                overflow.add(serverKey, jobsObj);
            });
            root.add("overflow", overflow);

            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            System.err.println("[Jobs Overflow] Could not save data: " + e.getMessage());
        }
    }

    public record ParsedJob(String job, int level, double xp, double maxXp, double gain) {}

    public record VirtualLevel(int level, double xpInto, double xpForLevel) {}

    private static double xpRequiredForLevel(int level) {
        return 2.0 * level * level + 50.0 * level;
    }




    public static VirtualLevel computeVirtualLevel(double jobMaxXpAt200, double overflowXp) {
        double baseFormulaValue = xpRequiredForLevel(MAX_LEVEL);
        double scale = (jobMaxXpAt200 > 0 && baseFormulaValue > 0) ? (jobMaxXpAt200 / baseFormulaValue) : 1.0;

        int level = MAX_LEVEL + 1;
        double remaining = overflowXp;
        double threshold = scale * xpRequiredForLevel(level);
        while (remaining >= threshold) {
            remaining -= threshold;
            level++;
            threshold = scale * xpRequiredForLevel(level);
        }
        return new VirtualLevel(level, remaining, threshold);
    }

    public static String getDisplayMode() {
        return displayMode;
    }
}

