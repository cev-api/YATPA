# YATPA
Current version: `1.0.5`

Yet Another TPA.

YATPA is a teleport plugin/mod project for modern Minecraft servers:
- `paper/`: Paper plugin (1.21.x API target)
- `fabric/`: Fabric dedicated-server mod (1.21.x Mojmap/Fabric API scaffold)

![Help](https://i.imgur.com/wPznadt.png)

## Features

- Player teleport requests:
  - `/tpa <player>`
  - `/tpahere <player>`
  - `/tpaccept`
  - `/tpdeny`
  - `/tpatoggle`
  - `/tpablock <player>`
  - `/tpaunblock <player>`
- Homes:
  - `/tphome set [name]`
  - `/tphome set default <name>`
  - `/tphome delete <name>`
  - `/tphome list`
  - `/tphome [name]`
- Utility teleports:
  - `/rtp`
  - `/spawn`
  - `/tpaback` (last death location)
- OP commands:
  - `/ytp <player>`
  - `/ytp <player> <targetPlayer>`
  - `/ytp <player> <x> <y> <z> [realm]`
  - `/ytp <x> <y> <z> [realm]` 
  - `/tpoffline <player>`
- In-game admin config (OP):
  - `/yatpa settings`
  - `/yatpa gui` (Paper, paginated inventory editor for all settings; click to toggle/edit)
  - `/yatpa set <path> <value>`
  - `/yatpa reload`
  - `/setspawn`
- Player help page:
  - `/tpahelp`
  - `/tphelp`
  - `/yatpa help`
  - When teleport costs are enabled, a `Costs` section is shown at the bottom listing only teleports with non-zero costs.

## Notable Behavior

- Requests:
  - Timeout + cooldown are enforced.
  - Accept/deny messages are clickable.
  - If an accepted delayed teleport is cancelled (move/damage), the other player is notified.
- Teleports:
  - Delayed teleports show a countdown.
  - Costs are validated before countdown and charged on execution.
  - Players are told exactly what they paid (XP, items, or currency).
  - `ytp`/`rtp` use safe landing checks to avoid unsafe blocks and lava.
- Costs and settings:
  - Cost modes: `NONE`, `XP_LEVELS`, `ITEM`, `CURRENCY`.
  - Realm-specific RTP costs and min/max RTP distance overrides are supported.
  - Paper `/yatpa gui` and `/yatpa set` expose currency paths directly.
- Restrictions and routing:
  - Per-dimension restrictions can disable `/rtp` only or all YATPA teleports.
  - `/rtp` supports blacklist + optional overworld routing.
  - Spawn destination is configurable via `settings.spawn.*` and `/setspawn`.
- Platform note:
  - Vault/EssentialsX currency charging is Paper-only in this release.

## Paper build output

The Paper module is configured to build:
- `paper/build/libs/YATPA-v1.x.x-Paper.jar`

## Fabric build output

The Fabric module is configured to build:
- `fabric/build/libs/YATPA-v1.x.x-Fabric.jar`

## Build

From repo root:

```bash
# Both Paper + Fabric
./gradlew clean build

# Paper only
./gradlew :paper:jar

# Fabric only
./gradlew :fabric:build
```

## Configuration

Paper config files:
- `paper/src/main/resources/config.yml`
- `paper/src/main/resources/messages.xml`
- `paper/src/main/resources/plugin.yml`

Fabric default config/resource files:
- `fabric/src/main/resources/yatpa-fabric.properties`
- `fabric/src/main/resources/yatpa-fabric.yml`
- `fabric/src/main/resources/messages-fabric.xml`

Runtime data (Paper) is stored under plugin data folder:
- `data/players.yml`
- `data/homes.yml`
- `data/offline.yml`

Runtime data (Fabric) is stored under:
- `config/yatpa/store.json`

### Example Config YAML

```
settings:
  max_homes_default: 3
  request_timeout_seconds: 60
  request_cooldown_seconds: 30
  teleport_delay_seconds: 5
  cancel_on_move: true
  cancel_on_damage: true
  spawn_radius: 50
  rtp_cooldown_seconds: 300
  rtp:
    default_min_distance: 64
    default_max_distance: 2500
    rtp_to_overworld: false
    overworld_name: "world"
    blacklisted_worlds: []
    # Optional per-realm overrides
    realm_min_distance:
      overworld: 96
      nether: 48
      end: 128
    realm_max_distance:
      overworld: 3000
      nether: 1500
      end: 4000
  dimension_restrictions:
    # Use world names, realm aliases (overworld/nether/end), or namespaced ids.
    # For namespaced ids in YAML, quote the key, for example "minecraft:the_nether".
    disable_rtp:
      nether: true
    disable_teleport:
      # "minecraft:the_end": true
  landing:
    mode: EXACT # EXACT or RANDOM_OFFSET
    random_offset_max: 4
  features:
    enabled: true
    tpa: true
    tpahere: true
    homes: true
    rtp: true
  costs:
    enabled: false
    mode: NONE # NONE, XP_LEVELS, ITEM, CURRENCY
    xp_levels:
      tpa: 4
      tpahere: 4
      home: 16
      back: 0
      # Per-realm RTP overrides (optional; overrides global if set)
      rtp:
        overworld: 30
        nether: 0
        end: 0
      spawn: 8
    item:
      material: DIAMOND
      tpa: 2
      tpahere: 2
      home: 20
      back: 0
      # Per-realm RTP overrides (optional; overrides global if set)
      rtp:
        overworld: 30
        nether: 0
        end: 0
      spawn: 10
    currency:
      tpa: 0.0
      tpahere: 0.0
      home: 0.0
      back: 0.0
      # Per-realm RTP overrides (optional; overrides global if set)
      rtp:
        overworld: 0.0
        nether: 0.0
        end: 0.0
      spawn: 0.0
  spawn:
    enabled: true
    x: 0
    y: 100
    z: 0
    yaw: 0
    pitch: 0
    world: world
sounds:
  request_sent: ENTITY_EXPERIENCE_ORB_PICKUP
  request_received: BLOCK_NOTE_BLOCK_PLING
  countdown: BLOCK_BELL_USE
  success: ENTITY_ENDERMAN_TELEPORT
  cancelled: BLOCK_GLASS_BREAK
effects:
  request_sent: PORTAL
  request_received: ENCHANT
  countdown: WAX_OFF
  success: END_ROD
  cancelled: SMOKE
```

Fabric `config.properties` supports the same restrictions with either:
- `settings.dimension_restrictions.disable_rtp=overworld,minecraft:the_nether`
- `settings.dimension_restrictions.disable_teleport=minecraft:the_end,my_custom_dimension`
- Dynamic keys like `settings.dimension_restrictions.disable_teleport.minecraft:the_nether=true`

## Permissions (Paper)

- `yatpa.op.reload` (default: op)
- `yatpa.op.tp` (default: op)
- `yatpa.op.tpoffline` (default: op)
- `yatpa.op.setspawn` (default: op)

## License

Licensed under `GNU General Public License v3.0` (GPL-3.0-only). See [LICENSE](LICENSE).

