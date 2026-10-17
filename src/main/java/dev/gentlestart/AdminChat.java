package dev.gentlestart;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.players.PlayerList;

/** Chat navigation and prefilled edits; all actions use permission-checked commands. */
final class AdminChat {
    private static final String COMMAND = "/gentlestart admin ";
    private static final int PAGE_SIZE = 8;

    private AdminChat() {
    }

    static List<Component> home(ProtectionConfig config) {
        return List.of(title("管理"),
                Component.literal("全局状态：" + (config.enabled() ? "启用 " : "停用 "))
                        .append(button("切换", "enabled " + !config.enabled(), false, "只修改全局开关")),
                Component.literal("每 tick 时间条件检查人数：" + config.playersPerTick() + " ")
                        .append(button("编辑", "checksPerTick " + config.playersPerTick(), true, "只修改轮询人数，按回车保存")),
                button("默认规则", "ui defaults", false, "设置全服默认保护规则")
                        .append(button("玩家覆盖", "ui players", false, "选择在线玩家或已有配置的 UUID"))
                        .append(button("重载文件", "reload", false, "从配置文件重新加载规则"))
                        .append(button("命令帮助", "help", false, "查看完整管理员命令")));
    }

    static List<Component> features(ProtectionConfig config, UUID target) {
        List<Component> lines = new ArrayList<>();
        lines.add(title(target == null ? "默认规则" : "玩家 " + target));
        var profile = target == null ? config.defaultProfile() : config.profile(target);
        for (Feature feature : Feature.values()) {
            double minutes = profile.window(feature).minutes();
            String state = minutes == 0 ? "关闭" : minutes < 0 ? "长期" : minutes + " 分钟";
            lines.add(button(feature.displayName(), scope(target) + " " + feature.configName(), false, "查看并编辑 " + feature.configName())
                    .append(Component.literal(" " + state).withStyle(ChatFormatting.GRAY)));
        }
        if (target != null) {
            lines.add(button("保护状态", "player " + target, false, "查看取消原因和已用免死次数"));
        }
        lines.add(button("返回首页", "ui", false, "返回管理员首页"));
        return lines;
    }

    static List<Component> rule(ProtectionConfig config, UUID target, Feature feature, int page) {
        List<Component> lines = new ArrayList<>();
        var fields = fields(config, target, feature);
        int pages = Math.max(1, (fields.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages);
        lines.add(title((target == null ? "默认 · " : "玩家 " + target + " · ") + feature.displayName() + " " + current + "/" + pages));
        lines.add(Component.literal("点击编辑预填命令，修改末尾值后回车；每次只保存这一项。悬停查看说明。")
                .withStyle(ChatFormatting.GRAY));
        String prefix = "ui set " + (target == null ? "defaults" : target) + " " + feature.configName() + " ";
        fields.entrySet().stream().skip((current - 1L) * PAGE_SIZE).limit(PAGE_SIZE).forEach(entry -> {
            String field = entry.getKey();
            JsonElement value = entry.getValue();
            String raw = value.toString();
            boolean bool = value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            String shown = raw.length() > 55 ? raw.substring(0, 52) + "..." : raw;
            lines.add(Component.literal(field + " = " + shown + " ")
                    .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(fieldName(field) + "\n" + raw))))
                    .append(button(bool ? "切换" : "编辑", prefix + field + " " + (bool ? !value.getAsBoolean() : raw),
                            !bool, fieldName(field) + (bool ? "；点击立即切换" : "；修改末尾值后回车保存，列表使用 JSON 数组"))));
        });
        String scope = scope(target) + " " + feature.configName();
        MutableComponent navigation = Component.empty();
        if (current > 1) navigation.append(button("上一页", scope + " " + (current - 1), false, "上一页"));
        if (current < pages) navigation.append(button("下一页", scope + " " + (current + 1), false, "下一页"));
        navigation.append(button("返回功能", scope(target), false, "选择其他保护功能"));
        lines.add(navigation);
        if (target != null) {
            lines.add(button("恢复本功能默认配置", "player " + target + " clear " + feature.configName(), true,
                    "预填命令，回车后删除本功能的玩家覆盖，不重置持久状态")
                    .append(button("重置取消状态", "player " + target + " reset " + feature.configName(), true,
                            "预填命令，回车后重置本功能状态；免死功能会清零已用次数")));
        }
        return lines;
    }

    static Map<String, JsonElement> fields(ProtectionConfig config, UUID target, Feature feature) {
        JsonObject rule = ProtectionConfig.encode(target == null ? config.defaultProfile() : config.profile(target))
                .getAsJsonObject(feature.id());
        Map<String, JsonElement> result = new LinkedHashMap<>();
        collect(rule, "", result);
        return result;
    }

    private static void collect(JsonObject object, String prefix, Map<String, JsonElement> fields) {
        object.entrySet().forEach(entry -> {
            String path = prefix + entry.getKey();
            if (entry.getValue().isJsonObject()) {
                collect(entry.getValue().getAsJsonObject(), path + ".", fields);
            } else {
                fields.put(path, entry.getValue());
            }
        });
    }

    static int fieldPage(ProtectionConfig config, UUID target, Feature feature, String field) {
        return new ArrayList<>(fields(config, target, feature).keySet()).indexOf(field) / PAGE_SIZE + 1;
    }

    static List<Component> players(ProtectionConfig config, PlayerList online, int page) {
        var ids = new LinkedHashSet<UUID>();
        online.getPlayers().forEach(player -> ids.add(player.getUUID()));
        config.configuredPlayers().stream().sorted().forEach(ids::add);
        int pages = Math.max(1, (ids.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages);
        List<Component> lines = new ArrayList<>();
        lines.add(title("选择玩家 " + current + "/" + pages));
        ids.stream().skip((current - 1L) * PAGE_SIZE).limit(PAGE_SIZE).forEach(id -> {
            var player = online.getPlayer(id);
            lines.add(button(player == null ? id.toString() : player.getGameProfile().name(), "ui player " + id, false,
                    id + (player == null ? "（离线）" : "（在线）")));
        });
        MutableComponent navigation = Component.empty();
        if (current > 1) navigation.append(button("上一页", "ui players " + (current - 1), false, "上一页"));
        if (current < pages) navigation.append(button("下一页", "ui players " + (current + 1), false, "下一页"));
        lines.add(navigation.append(button("按名字查找", "ui select ", true, "输入玩家名后回车"))
                .append(button("按UUID查找", "ui player ", true, "输入完整 UUID 后回车，可配置离线玩家"))
                .append(button("返回首页", "ui", false, "返回管理员首页")));
        return lines;
    }

    private static MutableComponent title(String text) {
        return Component.literal("━━ Gentle Start · " + text + " ━━").withStyle(ChatFormatting.GOLD);
    }

    private static MutableComponent button(String label, String command, boolean suggest, String help) {
        return Component.literal("[" + label + "] ").withStyle(style -> style.withColor(ChatFormatting.AQUA)
                .withClickEvent(suggest ? new ClickEvent.SuggestCommand(COMMAND + command) : new ClickEvent.RunCommand(COMMAND + command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(help))));
    }

    private static String scope(UUID target) {
        return target == null ? "ui defaults" : "ui player " + target;
    }

    private static String fieldName(String field) {
        return switch (field) {
            case "minutes" -> "累计游玩分钟上限；负数长期，0 关闭";
            case "ratio" -> "经验保留比例（0～1）";
            case "uses" -> "免死总次数（整数，-1 不限）";
            case "health" -> "免死恢复生命（正数，2 点 = 1 颗心）";
            case "invulnerabilitySeconds" -> "每次免死后的独立无敌秒数（整数，0～86400）";
            case "durationSeconds" -> "每次无敌秒数（整数，0～86400）";
            case "lifetimeSeconds" -> "掉落物寿命（整数秒，1～86400）";
            case "reduction" -> "伤害减免比例（0～1）";
            case "multiplier" -> "怪物感知范围倍数（0～1）";
            case "removeOnAttack" -> "主动攻击时解除 PvP 保护";
            case "keep.all" -> "保留全部物品";
            case "cancel.requireAll" -> "需要达成全部取消条件，历史达成也计入";
            case "cancel.experienceLevel" -> "取消等级阈值（非负整数，0 不使用）";
            case "cancel.playMinutes" -> "取消游玩分钟阈值（0 不使用）";
            default -> field + "，列表整体替换";
        };
    }

}
