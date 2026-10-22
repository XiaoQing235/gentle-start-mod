package dev.gentlestart;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.server.level.ServerPlayer;

public final class ProtectionCommands {
    private static final DynamicCommandExceptionType ERROR = new DynamicCommandExceptionType(message ->
            Component.literal("[Gentle Start] " + message));

    private ProtectionCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("gentlestart").executes(guard(context -> status(context,
                context.getSource().getPlayerOrException().getUUID())));
        root.then(Commands.literal("status").executes(guard(context -> status(context,
                context.getSource().getPlayerOrException().getUUID()))));
        root.then(Commands.literal("help").executes(context -> {
            reply(context, "/gentlestart status 查看个人保护；管理员使用 /gentlestart admin，并按 Tab 补全。", false);
            return 1;
        }));
        var admin = Commands.literal("admin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(guard(context -> context.getSource().getEntity() instanceof ServerPlayer
                        ? chat(context, AdminChat.home(engine().config())) : adminHelp(context)));
        admin.then(Commands.literal("help").executes(ProtectionCommands::adminHelp));
        var ui = Commands.literal("ui").executes(guard(context -> chat(context, AdminChat.home(engine().config()))));
        ui.then(Commands.literal("defaults").executes(guard(context -> chat(context, AdminChat.features(engine().config(), null))))
                .then(feature(false).executes(guard(context -> openRule(context, null, 1)))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(guard(context ->
                                openRule(context, null, IntegerArgumentType.getInteger(context, "page")))))));
        ui.then(Commands.literal("player").then(uuidArgument("uuid")
                .executes(guard(context -> chat(context, AdminChat.features(engine().config(), uuid(context)))))
                .then(feature(false).executes(guard(context -> openRule(context, uuid(context), 1)))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(guard(context ->
                                openRule(context, uuid(context), IntegerArgumentType.getInteger(context, "page"))))))));
        ui.then(Commands.literal("players").executes(guard(context -> openPlayers(context, 1)))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(guard(context ->
                        openPlayers(context, IntegerArgumentType.getInteger(context, "page"))))));
        ui.then(Commands.literal("select").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(guard(context -> {
            var selected = GameProfileArgument.getGameProfiles(context, "player");
            if (selected.size() != 1) {
                throw new IllegalArgumentException("请选择一名玩家。");
            }
            return chat(context, AdminChat.features(engine().config(), selected.iterator().next().id()));
        }))));
        ui.then(Commands.literal("set").then(Commands.argument("target", StringArgumentType.word())
                .then(feature(false).then(Commands.argument("field", StringArgumentType.word())
                        .then(Commands.argument("value", StringArgumentType.greedyString()).executes(guard(context -> {
                    String target = StringArgumentType.getString(context, "target");
                    UUID id = target.equals("defaults") ? null : UUID.fromString(target);
                    Feature selected = Feature.parse(StringArgumentType.getString(context, "feature"));
                    String field = StringArgumentType.getString(context, "field");
                    if (!AdminChat.fields(engine().config(), id, selected).containsKey(field)) {
                        throw new IllegalArgumentException("未知配置字段：" + field);
                    }
                    JsonObject patch = new JsonObject();
                    JsonObject parent = patch;
                    String[] path = field.split("\\.");
                    for (int index = 0; index < path.length - 1; index++) {
                        JsonObject child = new JsonObject();
                        parent.add(path[index], child);
                        parent = child;
                    }
                    parent.add(path[path.length - 1], JsonParser.parseString(StringArgumentType.getString(context, "value")));
                    update(id, selected, patch);
                    reply(context, "已保存 " + selected.displayName() + "（" + selected.configName() + "）." + field + "。", true);
                    return openRule(context, id, AdminChat.fieldPage(engine().config(), id, selected, field));
                })))))));
        admin.then(ui);
        admin.then(Commands.literal("settings").then(Commands.argument("enabled", BoolArgumentType.bool())
                .then(Commands.argument("playersPerTick", IntegerArgumentType.integer(1)).executes(guard(context -> {
                    JsonObject document = engine().config().document();
                    document.addProperty("enabled", BoolArgumentType.getBool(context, "enabled"));
                    document.addProperty("playersPerTick", IntegerArgumentType.getInteger(context, "playersPerTick"));
                    engine().replaceConfig(ProtectionConfig.parse(document));
                    reply(context, "全局设置已保存。", true);
                    return context.getSource().getEntity() instanceof ServerPlayer
                            ? chat(context, AdminChat.home(engine().config())) : 1;
                })))));
        admin.then(Commands.literal("lookup").then(Commands.argument("player", GameProfileArgument.gameProfile())
                .executes(guard(context -> {
                    for (var player : GameProfileArgument.getGameProfiles(context, "player")) {
                        reply(context, player.name() + " = " + player.id(), false);
                    }
                    return 1;
                }))));
        admin.then(Commands.literal("reload").executes(guard(context -> {
            engine().reload();
            reply(context, "配置已重载；已保存的取消状态、免死次数和死亡快照保持有效。", true);
            return 1;
        })));
        admin.then(Commands.literal("enabled").then(Commands.argument("value", BoolArgumentType.bool()).executes(guard(context -> {
            JsonObject rootJson = engine().config().document();
            rootJson.addProperty("enabled", BoolArgumentType.getBool(context, "value"));
            engine().replaceConfig(ProtectionConfig.parse(rootJson));
            reply(context, "全局启用状态已保存。", true);
            return context.getSource().getEntity() instanceof ServerPlayer
                    ? chat(context, AdminChat.home(engine().config())) : 1;
        }))));
        admin.then(Commands.literal("checksPerTick").then(Commands.argument("value", IntegerArgumentType.integer(1)).executes(guard(context -> {
            JsonObject document = engine().config().document();
            document.addProperty("playersPerTick", IntegerArgumentType.getInteger(context, "value"));
            engine().replaceConfig(ProtectionConfig.parse(document));
            reply(context, "每 tick 轮询人数已保存。", true);
            return context.getSource().getEntity() instanceof ServerPlayer
                    ? chat(context, AdminChat.home(engine().config())) : 1;
        }))));
        admin.then(Commands.literal("list").executes(guard(context -> list(context, 1)))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(guard(context ->
                        list(context, IntegerArgumentType.getInteger(context, "page"))))));

        var player = uuidArgument("uuid").executes(guard(context -> status(context, uuid(context))));
        player.then(Commands.literal("show").executes(guard(context -> {
            reply(context, new GsonBuilder().setPrettyPrinting().create().toJson(
                    ConfigFormat.externalProfile(ProtectionConfig.encode(engine().config().profile(uuid(context))))), false);
            return 1;
        })));
        player.then(Commands.literal("set").then(feature(false).then(Commands.argument("minutes", DoubleArgumentType.doubleArg())
                .executes(guard(context -> setMinutes(context, uuid(context), false))))));
        player.then(Commands.literal("grant").then(feature(false).then(Commands.argument("minutes", DoubleArgumentType.doubleArg())
                .executes(guard(context -> setMinutes(context, uuid(context), true))))));
        player.then(Commands.literal("rule").then(feature(false).then(Commands.argument("json", StringArgumentType.greedyString())
                .executes(guard(context -> editRule(context, uuid(context)))))));
        player.then(Commands.literal("clear").then(feature(true).executes(guard(context -> {
            UUID id = uuid(context);
            String selected = StringArgumentType.getString(context, "feature");
            if (selected.equals("all")) {
                engine().replaceConfig(engine().config().withoutPlayer(id));
            } else {
                JsonObject override = override(id);
                override.remove(Feature.parse(selected).id());
                engine().replaceConfig(engine().config().withPlayer(id, override));
            }
            reply(context, "UUID 配置覆盖已移除，恢复继承默认规则；持久状态未重置。", true);
            return 1;
        }))));
        player.then(Commands.literal("cancel").then(feature(true).executes(guard(context -> {
            for (Feature selected : features(context)) {
                engine().states().cancel(uuid(context), selected, "admin: " + context.getSource().getTextName());
            }
            reply(context, "保护已取消；玩家状态已排队保存。", true);
            return 1;
        }))));
        player.then(Commands.literal("reset").then(feature(true).executes(guard(context -> {
            String selected = StringArgumentType.getString(context, "feature");
            engine().states().reset(uuid(context), selected.equals("all") ? null : Feature.parse(selected));
            recheckPlayer(context, uuid(context));
            reply(context, "取消状态和对应条件历史已重置并排队保存；当前仍满足的自动取消条件会再次生效。", true);
            return 1;
        }))));
        player.then(Commands.literal("revives").then(Commands.argument("used", IntegerArgumentType.integer(0)).executes(guard(context -> {
            engine().states().setRevivesUsed(uuid(context), IntegerArgumentType.getInteger(context, "used"));
            reply(context, "已使用的免死次数已更新并排队保存。", true);
            return 1;
        }))));
        admin.then(Commands.literal("player").then(player));

        var defaults = Commands.literal("defaults").executes(context -> {
            reply(context, new GsonBuilder().setPrettyPrinting().create().toJson(ConfigFormat.externalDocument(engine().config().document()).get("defaults")), false);
            return 1;
        });
        defaults.then(Commands.literal("set").then(feature(false).then(Commands.argument("minutes", DoubleArgumentType.doubleArg())
                .executes(guard(context -> setMinutes(context, null, false))))));
        defaults.then(Commands.literal("rule").then(feature(false).then(Commands.argument("json", StringArgumentType.greedyString())
                .executes(guard(context -> editRule(context, null))))));
        admin.then(defaults);
        admin.then(Commands.literal("copy").then(uuidArgument("source")
                .then(uuidArgument("target").executes(guard(context -> {
                    UUID source = UuidArgument.getUuid(context, "source");
                    UUID target = UuidArgument.getUuid(context, "target");
                    engine().replaceConfig(engine().config().withPlayer(target, ProtectionConfig.encode(engine().config().profile(source))));
                    reply(context, "已复制生效配置；次数与取消状态不会从源玩家复制。", true);
                    return 1;
                })))));
        root.then(admin);
        dispatcher.register(root);
    }

    private static ProtectionEngine engine() {
        return GentleStart.engine();
    }

    private static int adminHelp(CommandContext<CommandSourceStack> context) {
        for (String command : List.of("ui [defaults|players|player <UUID>]", "settings <true|false> <每tick人数>", "checksPerTick <每tick人数>",
                "lookup <玩家名或选择器>", "list [页码]", "enabled <true|false>", "reload",
                "defaults set <功能> <分钟>", "defaults rule <功能> <JSON对象>",
                "player <UUID> [show|set|grant|rule|clear|cancel|reset|revives]", "copy <源UUID> <目标UUID>")) {
            reply(context, "/gentlestart admin " + command, false);
        }
        reply(context, "deathCorpse：先关闭同一配置的 keepInventory，再设 minutes；mode 为 barrel 或 player，saveExperience 控制经验瓶。", false);
        return 1;
    }

    private static int chat(CommandContext<CommandSourceStack> context, List<Component> lines) {
        lines.forEach(line -> context.getSource().sendSuccess(() -> line, false));
        return 1;
    }

    private static int openRule(CommandContext<CommandSourceStack> context, UUID target, int page) {
        return chat(context, AdminChat.rule(engine().config(), target,
                Feature.parse(StringArgumentType.getString(context, "feature")), page));
    }

    private static int openPlayers(CommandContext<CommandSourceStack> context, int page) {
        return chat(context, AdminChat.players(engine().config(), context.getSource().getServer().getPlayerList(), page));
    }

    private static UUID uuid(CommandContext<CommandSourceStack> context) {
        return UuidArgument.getUuid(context, "uuid");
    }

    private static RequiredArgumentBuilder<CommandSourceStack, UUID> uuidArgument(String name) {
        return Commands.argument(name, UuidArgument.uuid()).suggests((context, builder) -> {
            var ids = new java.util.LinkedHashSet<>(engine().config().configuredPlayers());
            context.getSource().getServer().getPlayerList().getPlayers().forEach(player -> ids.add(player.getUUID()));
            return SharedSuggestionProvider.suggest(ids.stream().map(UUID::toString).toList(), builder);
        });
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> feature(boolean all) {
        return Commands.argument("feature", StringArgumentType.word()).suggests((context, builder) -> {
            var names = new java.util.ArrayList<>(Arrays.stream(Feature.values()).map(Feature::configName).toList());
            if (all) {
                names.add("all");
            }
            return SharedSuggestionProvider.suggest(names, builder);
        });
    }

    private static List<Feature> features(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "feature");
        return name.equals("all") ? List.of(Feature.values()) : List.of(Feature.parse(name));
    }

    private static JsonObject override(UUID id) {
        JsonObject players = engine().config().document().getAsJsonObject("players");
        return players.has(id.toString()) ? players.getAsJsonObject(id.toString()).deepCopy() : new JsonObject();
    }

    private static int setMinutes(CommandContext<CommandSourceStack> context, UUID id, boolean fromNow) {
        double minutes = DoubleArgumentType.getDouble(context, "minutes");
        if (fromNow && minutes > 0) {
            ServerPlayer player = context.getSource().getServer().getPlayerList().getPlayer(id);
            if (player == null) {
                throw new IllegalArgumentException("grant 正数分钟需要玩家在线；离线 UUID 可使用 set 设置累计游玩时间阈值。");
            }
            minutes += ProtectionEngine.playedTicks(player) / 1200.0;
        }
        JsonObject patch = new JsonObject();
        patch.addProperty("minutes", minutes);
        update(id, Feature.parse(StringArgumentType.getString(context, "feature")), patch);
        if (fromNow) {
            engine().states().reset(id, Feature.parse(StringArgumentType.getString(context, "feature")));
            recheckPlayer(context, id);
        }
        reply(context, "保护期限已保存（负数长期、0 禁用、正数为累计游玩分钟阈值）。", true);
        return 1;
    }

    private static void recheckPlayer(CommandContext<CommandSourceStack> context, UUID id) {
        ServerPlayer player = context.getSource().getServer().getPlayerList().getPlayer(id);
        if (player != null) {
            engine().checkConditions(player);
        }
    }

    private static int editRule(CommandContext<CommandSourceStack> context, UUID id) {
        JsonObject patch = JsonParser.parseString(StringArgumentType.getString(context, "json")).getAsJsonObject();
        update(id, Feature.parse(StringArgumentType.getString(context, "feature")), patch);
        reply(context, "规则已校验并保存。", true);
        return 1;
    }

    private static void update(UUID id, Feature feature, JsonObject patch) {
        ProtectionConfig current = engine().config();
        if (id == null) {
            JsonObject document = current.document();
            JsonObject defaults = document.getAsJsonObject("defaults");
            defaults.add(feature.id(), ProtectionConfig.merge(defaults.getAsJsonObject(feature.id()), patch));
            engine().replaceConfig(ProtectionConfig.parse(document));
        } else {
            JsonObject override = override(id);
            JsonObject previous = override.has(feature.id()) ? override.getAsJsonObject(feature.id()) : new JsonObject();
            override.add(feature.id(), ProtectionConfig.merge(previous, patch));
            engine().replaceConfig(current.withPlayer(id, override));
        }
    }

    private static int status(CommandContext<CommandSourceStack> context, UUID id) {
        ServerPlayer player = context.getSource().getServer().getPlayerList().getPlayer(id);
        var profile = engine().config().profile(id);
        reply(context, id + (player == null ? "（离线：剩余时间需上线后计算）" : "，累计游玩 "
                + String.format(java.util.Locale.ROOT, "%.2f", ProtectionEngine.playedTicks(player) / 1200.0) + " 分钟"), false);
        for (Feature feature : Feature.values()) {
            reply(context, feature.displayName() + "（" + feature.configName() + "）：" + featureStatus(id, player, feature), false);
        }
        var revive = profile.revive();
        reply(context, "免死已使用 " + engine().states().get(id).revivesUsed() + "，总次数 "
                + (revive.uses() < 0 ? "不限" : revive.uses()), false);
        if (player != null) {
            reply(context, "本次免死无敌剩余 " + Math.ceil(engine().reviveInvulnerabilityTicks(player) / 20.0) + " 秒", false);
            reply(context, "本次重生无敌剩余 " + Math.ceil(engine().respawnInvulnerabilityTicks(player) / 20.0) + " 秒", false);
        }
        return 1;
    }

    static String featureStatus(UUID id, ServerPlayer player, Feature feature) {
        var config = engine().config();
        var profile = config.profile(id);
        var window = profile.window(feature);
        if (!config.enabled()) return "全局禁用";
        if (window.minutes() == 0) return "禁用";
        if (player != null) engine().active(player, feature); // Reconcile time-based cancellation before reporting it.
        String reason = engine().states().get(id).cancellation(feature);
        if (reason != null) {
            if (reason.equals("offensive player damage")) reason = "参与玩家攻击";
            else if (reason.startsWith("auto: ")) reason = "达到自动取消条件：" + reason.substring(6);
            else if (reason.startsWith("admin: ")) reason = "管理员取消：" + reason.substring(7);
            return "已取消（" + reason + "）";
        }
        if (player != null && player.isSpectator()) return "旁观模式不适用";
        if (player != null && !window.includes(ProtectionEngine.playedTicks(player))) return "已过期";
        if (feature == Feature.REVIVE && profile.revive().uses() == 0) return "不授予免死（次数为 0）";
        if (feature == Feature.REVIVE && profile.revive().uses() >= 0
                && engine().states().get(id).revivesUsed() >= profile.revive().uses()) return "免死次数已用尽";
        if (feature == Feature.INVENTORY) {
            var keep = profile.inventory().keep();
            if (!keep.all() && keep.categories().isEmpty() && keep.items().isEmpty() && keep.tags().isEmpty() && keep.slots().isEmpty()) {
                return "未选择保留物品";
            }
        }
        if (feature == Feature.EXPERIENCE && profile.experience().ratio() == 0) return "不保留经验（比例为 0）";
        if (feature == Feature.DAMAGE_REDUCTION && profile.damageReduction().reduction() == 0) return "不减伤（比例为 0）";
        if (feature == Feature.MOB_RANGE && profile.mobRange().multiplier() == 1) return "使用原版感知范围（倍数为 1）";
        if (feature == Feature.RESPAWN_PROTECTION && profile.respawnProtection().durationSeconds() == 0) return "不授予无敌（时长为 0）";
        if (feature == Feature.EFFECTS && profile.effects().list().isEmpty()) return "未配置效果";
        if (player == null) return window.minutes() < 0 ? "配置为长期；离线，当前生效状态待上线核对"
                : "离线；累计阈值 " + window.minutes() + " 分钟，剩余时间待上线核对";
        if (!player.isAlive()) return "当前已死亡，等待重生";
        return window.minutes() < 0 ? "生效中（长期）" : "生效中，剩余 "
                + (long) Math.ceil((window.minutes() * 1200 - ProtectionEngine.playedTicks(player)) / 20) + " 秒";
    }

    private static int list(CommandContext<CommandSourceStack> context, int page) {
        List<UUID> ids = engine().config().configuredPlayers().stream().sorted().toList();
        reply(context, "UUID 覆盖共 " + ids.size() + " 个，第 " + page + " 页（每页 20 个）", false);
        ids.stream().skip((page - 1L) * 20).limit(20).forEach(id -> reply(context, id.toString(), false));
        return 1;
    }

    private static Command<CommandSourceStack> guard(Command<CommandSourceStack> action) {
        return context -> {
            try {
                return action.run(context);
            } catch (IllegalArgumentException | IllegalStateException | JsonParseException exception) {
                if (context.getInput().startsWith("gentlestart admin ui set ")
                        && context.getSource().getEntity() instanceof ServerPlayer player) {
                    String target = StringArgumentType.getString(context, "target");
                    String reopen = "/gentlestart admin ui " + (target.equals("defaults") ? "defaults" : "player " + target)
                            + " " + StringArgumentType.getString(context, "feature");
                    player.sendSystemMessage(Component.literal("[重新查看配置]")
                            .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(reopen))));
                }
                throw ERROR.create(exception.getMessage());
            }
        };
    }

    private static void reply(CommandContext<CommandSourceStack> context, String message, boolean notifyAdmins) {
        context.getSource().sendSuccess(() -> Component.literal("[Gentle Start] " + message), notifyAdmins);
    }
}
