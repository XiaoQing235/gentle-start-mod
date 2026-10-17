# Gentle Start

> Designed and written for the servers I manage. Generated entirely by gpt-6-astra. Use at your own risk.

English | [简体中文](README.zh-CN.md)

**Give new players time to find their footing before facing the challenges of survival.**

Gentle Start is a Minecraft Fabric server mod that provides configurable protection for new players: keeping items and experience on death, preventing lethal damage from causing death, respawn invulnerability, PvP protection, and other survival assistance.

Protection is based on a player's cumulative playtime on your server. It can also end automatically when they obtain an item, complete an advancement, or reach a specified experience level. Set rules for the entire server or override them for individual players.

**Install on the server only · No client mod required · Vanilla clients supported**

[Features](#features) · [Installation](#installation) · [Configuration](#configuration) · [Commands](#commands) · [FAQ](#faq) · [Building](#building)

## Features

By default, players with less than **120 minutes** of cumulative playtime keep their items, all experience, and death-screen score on death, and receive Resistance I and Night Vision I. Other protections are disabled by default and can be enabled as needed.

| Feature | Behavior | Configuration name |
| --- | --- | --- |
| Keep inventory | Keep all items on death, or select by category, item, tag, or slot | `keepInventory` |
| Keep experience | Keep all or a configurable fraction of spendable experience | `keepExperience` |
| Keep score | Keep the vanilla score shown on the death screen | `keepScore` |
| Prevent death | Survive lethal damage and restore health, with configurable uses and additional effects | `preventDeath` |
| Respawn invulnerability | Temporarily block ordinary damage after an actual death and respawn, independently of death-prevention invulnerability | `respawnInvulnerability` |
| Protect death drops | Extend the lifetime of death drops, protect them from fire and ordinary damage, and prevent merging | `protectDeathDrops` |
| Status effects | Continuously apply configurable effects during protection | `protectionEffects` |
| PvP protection | Block damage from players, optionally ending protection when attacking another player | `pvpProtection` |
| Damage reduction | Reduce ordinary incoming damage by a configurable fraction | `damageReduction` |
| Mob detection range | Reduce the range at which mobs detect and track protected players | `mobDetectionRange` |

Each feature has its own duration and cancellation conditions. Protection state, consumed death-prevention uses, and cancellation records are saved with the world.

## Installation

| Component | Requirement |
| --- | --- |
| Minecraft Java Edition | 26.2 |
| Mod loader | Fabric Loader 0.19.5 or later |
| Dependency | Fabric API for the matching Minecraft version; this project builds against 0.161.0+26.2 |
| Java | 25 or later; building from source requires JDK 25 |
| Installation side | Server |

1. Set up a Fabric server with the versions listed above.
2. Put the Gentle Start mod Jar and Fabric API in the server's `mods/` directory. Do not install the source archive ending in `-sources`.
3. Start the server to generate `config/gentle-start-mod.json` automatically.
4. Edit the configuration as needed, then run `/gentlestart admin reload` to apply it.

If a prebuilt package is not available, follow the [build instructions](#building) below to produce a Jar.

## Configuration

The configuration file is `config/gentle-start-mod.json`. On server startup, if it does not exist, the mod generates the full default configuration with Chinese `//` comments. The filename remains unchanged, but its contents support comments (JSON with comments); strict external JSON tools require the comments to be removed first.

The generated comments explain each feature, numeric unit, range, and special value. Legacy feature names such as `inventory`, and the old `playersPerTick` option, are still accepted. Saving through administrator commands writes the new names and standard comments. Reloading does not rewrite an existing file; saving does not preserve custom comments. Do not specify both the old and new names for the same feature. Fields within each feature and saved player state remain compatible.

- `enabled`: global switch.
- `timeConditionChecksPerTick`: maximum number of online players checked per server tick, in round-robin order, for **playtime cancellation conditions and sustained effects**. A positive integer, default `1`. With a stable population of P players, a full pass takes approximately `ceil(P / timeConditionChecksPerTick)` ticks. A player is never checked twice in the same tick. Features without time conditions skip condition evaluation; items, levels, and advancements are not periodically scanned. Effects are replenished only when missing, weaker than required, or needing an extension.
- `defaults`: default rules for all players.
- `players`: per-player overrides indexed by UUID.

Omitted fields inherit their defaults; arrays are replaced as a whole. Manual file changes require a reload. Administrator commands save and apply changes immediately. Invalid configurations produce an error, and a failed reload leaves the current valid configuration active.

### How long does protection last?

Each feature's `minutes` field is a **cumulative playtime threshold**:

| Value | Meaning |
| --- | --- |
| Positive, such as `120` | Active until the player reaches 120 minutes of cumulative playtime |
| `0` | Disable this feature |
| Negative, such as `-1` | No playtime expiry; the feature can still be cancelled |

Playtime includes AFK time and statistics accumulated on the server before installing the mod. Leaving or restarting does not reset it. Time is measured in server ticks, so it differs from wall-clock time at low TPS.

### Configuration example

This example adds PvP protection for the first two hours and five seconds of respawn invulnerability to the default protection. It is not the factory default configuration.

```json
{
  "enabled": true,
  "timeConditionChecksPerTick": 1,
  "defaults": {
    "keepInventory": { "minutes": 120 },
    "keepExperience": { "minutes": 120, "ratio": 1.0 },
    "keepScore": { "minutes": 120 },
    "protectionEffects": { "minutes": 120 },
    "pvpProtection": { "minutes": 120, "removeOnAttack": true },
    "respawnInvulnerability": { "minutes": 120, "durationSeconds": 5 }
  },
  "players": {}
}
```

<details>
<summary>Enable additional protections</summary>

Place the desired rules under `defaults` or an individual player's configuration:

```json
{
  "preventDeath": { "minutes": 60, "uses": 3, "health": 10, "invulnerabilitySeconds": 5 },
  "protectDeathDrops": { "minutes": 120, "lifetimeSeconds": 600 },
  "damageReduction": { "minutes": 60, "reduction": 0.25 },
  "mobDetectionRange": { "minutes": 60, "multiplier": 0.5 }
}
```

- `preventDeath`: prevent death up to three times during the first 60 minutes, restoring health to 10 points (five hearts) each time. `uses: -1` allows unlimited uses. `invulnerabilitySeconds` controls the independent invulnerability period after each prevented death: default `5`, integer range `0–86400`, with `0` granting no additional invulnerability. Its timer is separate from `respawnInvulnerability.durationSeconds`; neither grants nor resets the other. Cancelling or resetting a feature clears only its own invulnerability.
- `protectDeathDrops`: `lifetimeSeconds` sets death-drop lifetime, default `600` seconds, integer range `1–86400`. It counts entity ticks and pauses when the chunk stops ticking entities. Lifetime is captured at death; changing the configuration does not alter existing drops, and saving/loading does not refresh their remaining lifetime. Legacy drops retain their existing countdown. Ordinary manually dropped items, mining drops, and experience orbs are unaffected. Other players can still pick up protected drops.
- `damageReduction`: reduce incoming damage by 25%, before armor and potion damage reduction.
- `mobDetectionRange`: reduce mob detection range to 50% of its original value. Mobs may still retaliate when attacked.

</details>

<details>
<summary>Keep only selected items</summary>

Place the following rule under `defaults.keepInventory`:

```json
{
  "minutes": 120,
  "keep": {
    "all": false,
    "categories": ["weapons", "tools", "armor", "offhand"],
    "items": ["minecraft:diamond"],
    "tags": ["minecraft:logs"],
    "slots": []
  }
}
```

An item is kept if it matches any category, item ID, tag, or slot. `all: true` keeps every item. Tags do not include a leading `#`.

Supported categories: `weapons`, `tools`, `wearable`, `food`, `blocks`, `hotbar`, `main`, `armor`, `offhand`, `body`, and `saddle`. `armor` matches equipped armor slots only; `wearable` also matches equippable items stored in the backpack.

Slot numbers: hotbar 0–8, main inventory 9–35, armor 36–39, offhand 40, body equipment 41, and saddle 42.

</details>

<details>
<summary>End protection automatically as players progress</summary>

Add `cancel` to any feature. The following rule permanently cancels that feature when the player enters the Nether or reaches level 30:

```json
{
  "minutes": 120,
  "cancel": {
    "requireAll": false,
    "advancements": ["minecraft:story/enter_the_nether"],
    "experienceLevel": 30
  }
}
```

Other supported conditions are `items` (item IDs), `itemTags` (item tags), and `playMinutes` (cumulative minutes). With `requireAll: true`, every specified condition must be met; previously met conditions are remembered.

Once protection is cancelled, dropping items, losing levels, or revoking advancements does not restore it. Administrators can use `reset`; if the player still meets the cancellation conditions, protection is cancelled again.

Item/tag, equipment, experience-level, and advancement conditions are evaluated through their corresponding change events. Ordinary damage handling, effect refreshes, and status queries no longer scan these states. Playtime conditions are still checked in batches according to `timeConditionChecksPerTick`, and time is also checked when protection is needed. Login, respawn, configuration changes/reloads, successful data-pack reloads, and administrator resets recheck the current state, including items already held and advancements already completed. Mods that directly modify internal fields instead of using vanilla methods are not guaranteed to trigger immediate checks.

</details>

<details>
<summary>Configure individual players</summary>

Use `/gentlestart admin lookup PlayerName` to find a player's UUID, then add it under `players`. Replace the example UUID below:

```json
{
  "players": {
    "00000000-0000-0000-0000-000000000001": {
      "keepInventory": { "minutes": -1 },
      "pvpProtection": { "minutes": 0 }
    }
  }
}
```

This player's inventory retention has no time limit, PvP protection is disabled, and all other features inherit the server defaults.

</details>

## Commands

### Interactive administrator configuration

Run `/gentlestart admin` or `/gentlestart admin ui` in-game as an administrator to open a clickable menu **in chat**. No client mod is required:

- **Global settings:** click to toggle the global switch. Editing `timeConditionChecksPerTick` prefills a command; change the final player count and press Enter to save.
- **Default protection rules:** choose a feature and edit its duration, ratios, uses, health, invulnerability seconds, drop lifetime, and cancellation thresholds.
- **Player selection:** browse online players and existing UUID overrides, or search by name or UUID. The configuration page shows the player's current merged values.
- **Editing:** numeric and list `[编辑]` (Edit) buttons insert the full command and current value into the chat input. Change the final value and press Enter. Boolean `[切换]` (Toggle) buttons save immediately. Hover for descriptions and full values; long lists are abbreviated and fields are paginated.
- Only the selected field is saved; other fields continue inheriting defaults. Item filters, cancellation conditions, and effect lists can also be edited through prefilled commands. Lists use JSON arrays and are replaced as a whole. Invalid edits are not saved; successful edits refresh the relevant page.
- Player pages provide protection status, restoration of a feature's defaults, and cancellation-state reset. Restore/reset actions prefill commands and require Enter to execute. Ordinary configuration changes do not clear cancellation history or consumed death-prevention uses.

Open `/gentlestart admin ui defaults protectDeathDrops` or `/gentlestart admin ui player <UUID> protectDeathDrops` directly, optionally followed by a page number. Text administration commands remain available from the console. All chat buttons execute commands that check administrator permissions. In-game messages and menu labels are currently in Chinese.

Players can use `/gentlestart status` to view their own protection and `/gentlestart help` for help. Status uses consistent feature labels and configuration names, distinguishing active, expired, cancelled, disabled, spectator-inapplicable, and exhausted death-prevention states. Offline queries do not guess remaining playtime.

Administrator commands require operator permission level 2 and can also be run from the server console. Commands support Tab completion.

| Common command | Purpose |
| --- | --- |
| `/gentlestart admin reload` | Reload the configuration |
| `/gentlestart admin defaults set pvpProtection 120` | Enable PvP protection for players with less than two hours of cumulative playtime |
| `/gentlestart admin lookup PlayerName` | Look up a player's UUID |
| `/gentlestart admin player <UUID> grant keepInventory 30` | Grant an online player another 30 minutes of inventory retention from their current playtime |
| `/gentlestart admin player <UUID> cancel all` | Cancel all protection for that player |
| `/gentlestart admin player <UUID> reset all` | Reset cancellation records, condition history, consumed uses, and invulnerability windows |

`set` specifies a cumulative playtime threshold. `grant` adds protection from the player's current playtime and resets the corresponding state. `reset` does not extend the playtime threshold.

<details>
<summary>All administrator commands</summary>

| Command (prefixed with `/gentlestart admin`) | Description |
| --- | --- |
| `help` | List administrator entry points; commands and feature names support Tab completion, and UUID suggestions include online/configured players |
| `lookup <name-or-selector>` | Look up player UUIDs; unknown names in offline mode follow vanilla resolution rules, so prefer known players or exact UUIDs |
| `list [page]` | List UUIDs with overrides, 20 per page |
| `enabled <true-or-false>` | Save the global switch |
| `reload` | Read and validate the configuration again; retain the active configuration on failure |
| `defaults` | Show default rules |
| `ui` | Show the interactive chat menu |
| `ui set <defaults-or-UUID> <feature> <field-path> <JSON-value>` | Edit one field, such as `protectDeathDrops lifetimeSeconds 600` or `keepInventory cancel.experienceLevel 30` |
| `settings <true-or-false> <players-per-tick>` | Save the global switch and periodic-check player budget together |
| `checksPerTick <players-per-tick>` | Change only the polling budget; the two chat controls each update their own field |
| `defaults set <feature> <minutes>` | Change a default cumulative playtime threshold |
| `defaults rule <feature> <JSON-object>` | Merge supported fields into a default rule |
| `player <UUID>` | Show thresholds, cancellation reasons, and consumed uses; remaining time is not invented for offline players |
| `player <UUID> show` | Show the UUID's full effective configuration |
| `player <UUID> set <feature> <minutes>` | Set a cumulative playtime threshold without clearing existing cancellation state |
| `player <UUID> grant <feature> <minutes>` | Grant an online player the specified additional minutes and reset that feature's state; negative values for permanent protection and zero for disabling also work for offline UUIDs |
| `player <UUID> rule <feature> <JSON-object>` | Merge changes into the UUID's rule |
| `player <UUID> clear <feature-or-all>` | Remove overrides and restore inheritance without resetting uses or cancellation state |
| `player <UUID> cancel <feature-or-all>` | Persistently cancel protection |
| `player <UUID> reset <feature-or-all>` | Reset cancellation state and condition history; `preventDeath` also clears consumed uses and its invulnerability, while `respawnInvulnerability` clears only respawn invulnerability |
| `player <UUID> revives <used>` | Set consumed death-prevention uses without changing independent invulnerability windows |
| `copy <source-UUID> <target-UUID>` | Copy effective rules without copying cancellation history or consumed uses |

</details>

## FAQ

**Do clients need the mod?**

No. Install Gentle Start only on the Fabric server; vanilla clients can join. It is not a Paper or Spigot plugin.

**Do existing players receive another two hours of protection?**

No. The mod reads their existing cumulative playtime on this server. Use `grant` to give additional protection.

**Does leaving, reconnecting, or restarting reset uses?**

Normal disconnects and restarts do not reset them. Cancellation records, consumed uses, and invulnerability deadlines are saved per player under the world's `data/gentle-start-mod-state.json.d/<UUID prefix>/<UUID>.json`. Every 100 ticks (about five seconds at normal TPS), only changed players are handed to a single background writer; unchanged history is neither copied nor rewritten. Resetting all state deletes that player's file. Records load on demand, with at most 4,096 clean records cached; unsaved changes remain in memory until persisted.

Normal shutdown and `/save-all flush` wait for pending writes. Failed records remain pending and are retried without rewriting successfully saved players; newer changes take precedence over failed older writes. Write failures are logged rather than thrown through damage callbacks. Unreadable or corrupt player files fail explicitly when accessed, rather than resetting protection state. Power loss or forced termination can lose recent changes; persistent storage failures extend that window. Use administrator commands to change state; do not edit these files while the server is running.

On the first startup after upgrading from the single-file format, the mod migrates `data/gentle-start-mod-state.json` into a temporary directory and publishes the new directory only after every record succeeds. This one-time migration can take time on large worlds. The original file is retained unchanged as a pre-upgrade backup; once the new directory exists, it is authoritative and the old file is never merged back. Back up the new directory with the world. Downgrading to a version that reads only the old file would restore stale state.

**Can death prevention save players from the void?**

No. The void, `/kill`, and other damage that bypasses invulnerability remain effective. The mod does not teleport players out of the void. Vanilla totems take priority over mod death-prevention uses. The mod checks lethality after damage, armor, and absorption are resolved, then restores health before entering the death process. It therefore does not close the current container, capture a death snapshot, or replace the player. Damage-related side effects such as equipment durability loss still follow vanilla behavior. Other mods that directly call death methods instead of going through damage handling are not guaranteed to be intercepted. An actual death clears previous death-prevention invulnerability.

**Can protected players still attack others?**

With `removeOnAttack: true`, attacking another player permanently ends the attacker's PvP protection. With `false`, protected players cannot damage others either. Attribution checks the causing entity, the direct entity, and vanilla traceable/tamed entity ownership, covering arrows, pet attacks, and attributed explosions. Such attacks also end the attacker's current death-prevention and respawn invulnerability, even if the victim blocks the damage. Future death-prevention and respawn eligibility is retained, and the victim's own protection is unaffected. Lava, fire, and traps without player attribution are still not guaranteed to count as PvP.

**Do effects disappear immediately when protection ends?**

Sustained effects receive the remaining duration of `protectionEffects.minutes`; permanent protection uses vanilla infinite duration. Polling replenishes effects only when needed, preserving the natural countdown and vanilla periodic behavior such as regeneration. Login, respawn, prevented death, and configuration reloads check for missing effects. Milk-cleared effects return at the player's next scheduled check. The legacy `durationSeconds` field in the sustained effect list is still accepted but no longer controls duration; it still applies to `preventDeath.effects`.

Finite effects expire naturally at their granted deadline. Early cancellation, shortening the window, or disabling a feature only stops replenishment; already granted effects and equivalent potion/beacon effects are not removed. An already granted infinite effect remains until milk, death, or another vanilla mechanism clears it.

**Does it work with other mods?**

The vanilla global `keep_inventory` rule remains effective. Selective retention temporarily holds selected slots, lets vanilla process other items, vanishing curses, and equipment drops, and restores retained slots in `try/finally`. It neither reimplements the vanilla drop loop nor temporarily changes global rules. Slot restoration is tested with another Mixin cancelling or throwing during drops. Mods with custom AI, death, or damage handling still need testing in the intended combination; compatibility with every combination is not guaranteed.

## Building

With JDK 25 installed, run from the project root:

```powershell
.\gradlew.bat build
```

On Linux / macOS:

```sh
./gradlew build
```

Outputs are written to `build/libs/`. Install the Jar without the `-sources` suffix. `build` also runs the server GameTests; use the `runGameTest` task to run them separately.

GitHub Actions runs the build, server GameTests, and a redacted BetterLeaks scan on pushes to `main` and pull requests. The workflow can also be started manually. Successful runs provide the mod Jar as an artifact; build and GameTest logs are retained for diagnosis. See [CI runs](https://github.com/XiaoQing235/gentle-start-mod/actions/workflows/ci.yml).

## Feedback

For bugs or feature requests, open an [Issue](https://github.com/XiaoQing235/gentle-start-mod/issues) with your Minecraft, Fabric Loader, Fabric API, and mod versions, along with reproduction steps. Remove personal information before sharing logs or configuration files.

## License

[MIT License](LICENSE) · Copyright (c) 2026 XiaoQing235
