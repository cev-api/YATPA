# YATPA

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
- OP commands:
  - `/ytp <player>`
  - `/ytp <x> <y> <z> [realm]` with safeguards against teleporting into blocks and suffocating if not in creative/spectator.
  - `/tpoffline <player>`
  - `realm` accepts `overworld`, `nether`, `end`, or a loaded dimension id/path (for example `minecraft:the_end`).
- In-game admin config (OP):
  - `/yatpa settings`
  - `/yatpa set <path> <value>`
  - `/yatpa reload`
- Player help page:
  - `/tpahelp`
  - `/tphelp`
  - `/yatpa help`
  - When teleport costs are enabled, a `Costs` section is shown at the bottom listing only teleports with non-zero costs.

## Notable behavior

- Request timeout and request cooldown.
- Teleport delay with countdown.
- Optional cancel on move/damage.
- Clickable accept/deny messages.
- XML-based messages.
- Configurable sounds and particle effects.
- Configurable teleport costs for each teleport type (XP or Items).
- Cost failures show exact requirement (for example, required XP levels or item amount/type).
- Feature toggles for `tpa`, `tpahere`, `homes`, and `rtp`.
- RTP cooldown (`settings.rtp_cooldown_seconds`, default `300`).

## Paper build output

The Paper module is configured to build:
- `paper/build/libs/YATPA-v1.0.0-Paper.jar`

## Fabric build output

The Fabric module is configured to build:
- `fabric/build/libs/YATPA-v1.0.0-Fabric.jar`

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
    mode: NONE # NONE, XP_LEVELS, ITEM
    xp_levels:
      tpa: 4
      tpahere: 4
      home: 16
      rtp: 30
      spawn: 8
    item:
      material: DIAMOND
      tpa: 2
      tpahere: 2
      home: 20
      rtp: 50
      spawn: 10
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

## Permissions (Paper)

- `yatpa.op.reload` (default: op)
- `yatpa.op.tp` (default: op)
- `yatpa.op.tpoffline` (default: op)

## License

Licensed under `GNU General Public License v3.0` (GPL-3.0-only). See [LICENSE](LICENSE).

