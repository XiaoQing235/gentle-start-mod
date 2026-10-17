package dev.gentlestart;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;

/** Human-facing names are separate from stable feature IDs used in player history. */
final class ConfigFormat {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigFormat() {
    }

    static JsonObject read(JsonObject input) {
        JsonObject root = input.deepCopy();
        rename(root, "timeConditionChecksPerTick", "playersPerTick");
        profiles(root, false);
        return root;
    }

    private static void profiles(JsonObject root, boolean writing) {
        if (root.has("defaults") && root.get("defaults").isJsonObject()) {
            profile(root.getAsJsonObject("defaults"), writing);
        }
        if (root.has("players") && root.get("players").isJsonObject()) {
            root.getAsJsonObject("players").entrySet().forEach(entry -> {
                if (entry.getValue().isJsonObject()) profile(entry.getValue().getAsJsonObject(), writing);
            });
        }
    }

    static JsonObject externalProfile(JsonObject input) {
        JsonObject result = input.deepCopy();
        profile(result, true);
        return result;
    }

    private static void profile(JsonObject profile, boolean writing) {
        for (Feature feature : Feature.values()) {
            rename(profile, writing ? feature.id() : feature.configName(), writing ? feature.configName() : feature.id());
        }
    }

    private static void rename(JsonObject object, String from, String to) {
        if (from.equals(to) || !object.has(from)) return;
        if (object.has(to)) throw new IllegalArgumentException("Do not specify both " + from + " and " + to);
        object.add(to, object.remove(from));
    }

    static JsonObject externalDocument(JsonObject document) {
        JsonObject root = document.deepCopy();
        rename(root, "playersPerTick", "timeConditionChecksPerTick");
        profiles(root, true);
        return root;
    }

    static String write(JsonObject document) {
        JsonObject root = externalDocument(document);
        StringBuilder result = new StringBuilder("// Gentle Start 配置（UTF-8，支持 // 和 /* */ 注释）。\n"
                + "// 修改后执行 /gentlestart admin reload；管理员保存会重新生成标准注释，不保留自定义注释。\n"
                + "// minutes 是累计游玩分钟上限：0 关闭，负数永久，正数到期结束；所有秒数按 20 tick/秒计算。\n"
                + "// players 中用玩家 UUID 设置部分覆盖；未填写字段继承 defaults，数组整体替换。\n");
        appendObject(result, root, 0);
        return result.append('\n').toString();
    }

    private static void appendObject(StringBuilder out, JsonObject object, int depth) {
        out.append("{\n");
        int index = 0;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String indent = "  ".repeat(depth + 1);
            out.append(indent).append("// ").append(description(entry.getKey())).append('\n');
            out.append(indent).append(GSON.toJson(entry.getKey())).append(": ");
            if (entry.getValue().isJsonObject()) {
                appendObject(out, entry.getValue().getAsJsonObject(), depth + 1);
            } else {
                out.append(GSON.toJson(entry.getValue()).replace("\n", "\n" + indent));
            }
            if (++index < object.size()) out.append(',');
            out.append('\n');
        }
        out.append("  ".repeat(depth)).append('}');
    }

    private static String description(String name) {
        return switch (name) {
            case "enabled" -> "模组总开关：true 启用，false 停用。";
            case "timeConditionChecksPerTick" -> "每 tick 轮询时间取消条件的玩家数，整数 >= 1；其他条件由事件触发。";
            case "defaults" -> "所有玩家的默认规则。";
            case "players" -> "玩家 UUID 的部分覆盖配置，空对象表示全部使用默认规则。";
            case "keepInventory" -> "死亡时保留背包及装备中选定的物品。";
            case "keepExperience" -> "死亡时保留经验。";
            case "keepScore" -> "死亡时保留玩家分数。";
            case "preventDeath" -> "受到致命伤害时原地免死。";
            case "protectionEffects" -> "保护期间持续给予的药水效果。";
            case "pvpProtection" -> "防止其他玩家造成伤害。";
            case "damageReduction" -> "受到伤害时按比例减免。";
            case "mobDetectionRange" -> "缩小怪物对受保护玩家的感知范围。";
            case "respawnInvulnerability" -> "重生或原地免死后的短暂无敌。";
            case "protectDeathDrops" -> "保护死亡掉落物免受伤害并设置消失时间；不限制他人拾取。";
            case "minutes" -> "累计游玩分钟上限：0 关闭，负数永久，正数到期结束。";
            case "cancel" -> "提前取消本功能的条件；达成历史会保存，取消后需管理员重置。";
            case "requireAll" -> "true 需要达成全部条件（可先后达成）；false 任意一项即可；空条件不会取消。";
            case "advancements" -> "完成这些进度后取消，填写完整 ID，如 minecraft:story/mine_stone。";
            case "items" -> "物品 ID 列表，如 minecraft:diamond；在 cancel 中表示获得过，在 keep 中表示保留。";
            case "itemTags" -> "获得这些标签的物品后取消，如 minecraft:logs（不加 #）。";
            case "experienceLevel" -> "达到此经验等级后取消，非负整数；0 不使用。";
            case "playMinutes" -> "累计游玩达到此分钟数后取消，非负数；0 不使用。";
            case "keep" -> "物品保留选择器；各列表按并集匹配。";
            case "all" -> "true 保留全部；false 仅保留下面列表选中的物品。";
            case "categories" -> "类别：hotbar, main, armor, offhand, body, saddle, weapons, tools, wearable, food, blocks。";
            case "tags" -> "保留的物品标签 ID 列表，如 minecraft:logs（不加 #）。";
            case "slots" -> "保留的槽位编号列表，整数 0～42。";
            case "ratio" -> "经验保留比例 0～1；1 全部保留，0.5 保留一半。";
            case "uses" -> "免死总次数，整数；-1 不限，0 不允许免死。";
            case "health" -> "免死后恢复生命值，正数；2 点 = 1 颗心。";
            case "effects", "list" -> "药水效果数组：id 为原版持续效果 ID；level 为等级 1～256；durationSeconds 为持续秒数 1～86400；showParticles/showIcon 控制粒子/图标。";
            case "removeOnAttack" -> "主动攻击玩家时是否解除自己的 PvP 保护。";
            case "reduction" -> "伤害减免比例 0～1；0.25 表示减伤 25%。";
            case "multiplier" -> "怪物感知范围倍数 0～1；0.5 表示原范围的一半。";
            case "durationSeconds" -> "每次重生或免死后的无敌秒数，整数 0～86400。";
            case "lifetimeSeconds" -> "受保护死亡掉落物存在秒数，整数 1～86400，默认 600；只影响之后产生的掉落物。";
            default -> "该玩家的覆盖规则，未填写的字段继承默认配置。";
        };
    }
}
