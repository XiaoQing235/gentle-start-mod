package dev.gentlestart;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.LinkedHashSet;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.level.ServerPlayer;

/** Vanilla-client forms. Every action returns through permission-checked admin commands. */
final class AdminDialogs {
    private static final String COMMAND = "/gentlestart admin ";

    private AdminDialogs() {
    }

    static Holder<Dialog> decode(JsonObject document) {
        return Holder.direct(Dialog.DIRECT_CODEC.parse(JsonOps.INSTANCE, document).getOrThrow());
    }

    static JsonObject home(ProtectionConfig config) {
        JsonObject dialog = base("multi_action", "Gentle Start 管理", "修改后点击保存。功能规则可按玩家单独覆盖。", "");
        JsonArray inputs = dialog.getAsJsonArray("inputs");
        inputs.add(input("enabled", "启用模组", config.document().get("enabled")));
        inputs.add(input("playersPerTick", "每 tick 周期检查人数（正整数）", config.document().get("playersPerTick")));
        JsonArray actions = dialog.getAsJsonArray("actions");
        actions.add(button("保存全局设置", "settings $(enabled) $(playersPerTick)"));
        actions.add(button("默认保护规则", "ui defaults"));
        actions.add(button("选择玩家", "ui players"));
        actions.add(button("重载配置文件", "reload"));
        return dialog;
    }

    static JsonObject features(ProtectionConfig config, UUID target) {
        JsonObject dialog = base("multi_action", target == null ? "默认保护规则" : "玩家 " + target,
                target == null ? "选择功能调整数值。" : "显示合并后的生效配置；保存仅覆盖本表单字段，不重置取消记录或次数。", "ui");
        var profile = target == null ? config.defaultProfile() : config.profile(target);
        for (Feature feature : Feature.values()) {
            double minutes = profile.window(feature).minutes();
            String state = minutes == 0 ? "关闭" : minutes < 0 ? "长期" : minutes + " 分钟";
            dialog.getAsJsonArray("actions").add(button(featureName(feature) + " · " + state,
                    scope(target) + " " + feature.id()));
        }
        return dialog;
    }

    static JsonObject rule(ProtectionConfig config, UUID target, Feature feature) {
        String title = (target == null ? "默认 · " : "玩家覆盖 · ") + featureName(feature);
        JsonObject dialog = base("confirmation", title,
                "期限为累计游玩分钟：-1 长期，0 关闭。比例为 0～1。保存不会重置取消状态；列表仍通过 rule 命令编辑。",
                scope(target));
        JsonObject current = ProtectionConfig.encode(target == null ? config.defaultProfile() : config.profile(target))
                .getAsJsonObject(feature.id());
        JsonObject patch = scalarInputs(current, "", dialog.getAsJsonArray("inputs"));
        String template = patch.toString();
        for (JsonElement element : dialog.getAsJsonArray("inputs")) {
            String variable = "$(" + element.getAsJsonObject().get("key").getAsString() + ")";
            template = template.replace("\"" + variable + "\"", variable);
        }
        dialog.add("yes", button("保存", "ui apply " + (target == null ? "defaults" : target)
                + " " + feature.id() + " " + template));
        return dialog;
    }

    static JsonObject players(ProtectionEngine engine, ServerPlayer viewer, int page) {
        var ids = new LinkedHashSet<UUID>();
        var online = viewer.level().getServer().getPlayerList();
        online.getPlayers().forEach(player -> ids.add(player.getUUID()));
        engine.config().configuredPlayers().stream().sorted().forEach(ids::add);
        int pages = Math.max(1, (ids.size() + 9) / 10);
        int current = Math.min(page, pages);
        JsonObject dialog = base("multi_action", "选择玩家 · " + current + "/" + pages,
                "列出在线玩家和已有覆盖。也可输入玩家名或 UUID；从未登录的 UUID 可用 /gentlestart admin ui player <UUID>。", "ui");
        JsonObject name = new JsonObject();
        name.addProperty("key", "player");
        name.addProperty("type", "minecraft:text");
        name.addProperty("label", "玩家名或 UUID");
        name.addProperty("max_length", 36);
        dialog.getAsJsonArray("inputs").add(name);
        JsonArray actions = dialog.getAsJsonArray("actions");
        actions.add(button("查找玩家", "ui select $(player)"));
        ids.stream().skip((current - 1L) * 10).limit(10).forEach(id -> {
            ServerPlayer player = online.getPlayer(id);
            actions.add(button(player == null ? id.toString() : player.getGameProfile().name(), "ui player " + id));
        });
        if (current > 1) {
            actions.add(button("上一页", "ui players " + (current - 1)));
        }
        if (current < pages) {
            actions.add(button("下一页", "ui players " + (current + 1)));
        }
        return dialog;
    }

    private static JsonObject scalarInputs(JsonObject object, String prefix, JsonArray inputs) {
        JsonObject patch = new JsonObject();
        object.entrySet().forEach(entry -> {
            String path = prefix + entry.getKey();
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                JsonObject child = scalarInputs(value.getAsJsonObject(), path + ".", inputs);
                if (!child.isEmpty()) {
                    patch.add(entry.getKey(), child);
                }
            } else if (value.isJsonPrimitive() && (value.getAsJsonPrimitive().isNumber() || value.getAsJsonPrimitive().isBoolean())) {
                String variable = path.replace('.', '_');
                inputs.add(input(variable, fieldName(path), value));
                patch.addProperty(entry.getKey(), "$(" + variable + ")");
            }
        });
        return patch;
    }

    private static JsonObject input(String variable, String label, JsonElement value) {
        JsonObject input = new JsonObject();
        input.addProperty("key", variable);
        boolean bool = value.getAsJsonPrimitive().isBoolean();
        input.addProperty("type", bool ? "minecraft:boolean" : "minecraft:text");
        input.addProperty("label", label);
        if (bool) {
            input.add("initial", value.deepCopy());
        } else {
            input.addProperty("initial", value.getAsString());
            input.addProperty("max_length", 32);
        }
        return input;
    }

    private static JsonObject base(String type, String title, String description, String back) {
        JsonObject dialog = new JsonObject();
        dialog.addProperty("type", "minecraft:" + type);
        dialog.addProperty("title", title);
        dialog.addProperty("pause", false);
        JsonArray body = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "minecraft:plain_message");
        text.addProperty("contents", description);
        body.add(text);
        dialog.add("body", body);
        dialog.add("inputs", new JsonArray());
        if (type.equals("multi_action")) {
            dialog.add("actions", new JsonArray());
            dialog.add("exit_action", button(back.isEmpty() ? "关闭" : "返回", back));
        } else {
            dialog.add("no", button("取消并返回", back));
        }
        return dialog;
    }

    private static JsonObject button(String label, String command) {
        JsonObject button = new JsonObject();
        button.addProperty("label", label);
        if (!command.isEmpty()) {
            JsonObject action = new JsonObject();
            boolean dynamic = command.contains("$(");
            action.addProperty("type", dynamic ? "minecraft:dynamic/run_command" : "minecraft:run_command");
            action.addProperty(dynamic ? "template" : "command", COMMAND + command);
            button.add("action", action);
        }
        return button;
    }

    private static String scope(UUID target) {
        return target == null ? "ui defaults" : "ui player " + target;
    }

    private static String fieldName(String field) {
        return switch (field) {
            case "minutes" -> "保护期限（累计分钟，-1 长期，0 关闭）";
            case "ratio" -> "经验保留比例（0～1）";
            case "uses" -> "免死总次数（整数，-1 不限）";
            case "health" -> "免死恢复生命（正数，2 点 = 1 颗心）";
            case "durationSeconds" -> "每次无敌秒数（整数，0～86400）";
            case "lifetimeSeconds" -> "掉落物寿命（整数秒，1～86400）";
            case "reduction" -> "伤害减免比例（0～1）";
            case "multiplier" -> "怪物感知范围倍数（0～1）";
            case "removeOnAttack" -> "主动攻击时解除 PvP 保护";
            case "keep.all" -> "保留全部物品";
            case "cancel.requireAll" -> "需要达成全部取消条件";
            case "cancel.experienceLevel" -> "取消等级阈值（非负整数，0 不使用）";
            case "cancel.playMinutes" -> "取消游玩分钟阈值（0 不使用）";
            default -> field;
        };
    }

    private static String featureName(Feature feature) {
        return switch (feature) {
            case INVENTORY -> "物品保留";
            case EXPERIENCE -> "经验保留";
            case SCORE -> "分数保留";
            case REVIVE -> "原地免死";
            case EFFECTS -> "持续效果";
            case PVP -> "PvP 保护";
            case DAMAGE_REDUCTION -> "伤害减免";
            case MOB_RANGE -> "怪物感知范围";
            case RESPAWN_PROTECTION -> "重生无敌";
            case DEATH_DROPS -> "死亡掉落物保护";
        };
    }
}
