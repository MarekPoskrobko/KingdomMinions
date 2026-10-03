# KingdomMinions

Server-side helpers controlled by a golden Ruler's Wand. For Minecraft servers running Paper/Purpur; players do not install a client mod.

## Features

- Up to four named helpers per ruler by default.
- Digging, tunnels, connected ore veins, tree chopping, terrain and wall leveling.
- Saved chest or ground delivery destination; one-shot delivery preserves the default target.
- Optional lighting, lining and hole filling.
- Persistent jobs, inventories, helper names and delivery state, plus an audit log.
- Per-player Polish and English UI selected from Minecraft's language settings. Unsupported languages fall back to English.

## Compatibility and validation

Version **1.0.27** requires **Java 25**. It compiles against Paper API 26.2 and is integration-tested on **Purpur 26.3 build 2641**. Compatibility with other builds and third-party plugins must be checked on a test copy of your server.

This is a standalone server-side implementation inspired by Minions gameplay. It does not bundle upstream mod code or assets. It is not affiliated with Minecraft, Mojang or the original Minions mod.

## Installation

1. Back up your world and plugin data.
2. Stop the server and put the plugin JAR in `plugins/`. Keep only one KingdomMinions JAR there.
3. Start the server. Players do not put this JAR in their CurseForge `mods/` folder.
4. Grant a wand to each ruler: `/minions grant PlayerName`. The player must be online and have an empty inventory slot.

Updates preserve `plugins/KingdomMinions/`. Do not delete `data.yml` or use `/reload` to replace the JAR.

## Commands

| Command | Purpose |
| --- | --- |
| `/minions grant <player>` | Grant the player's sole active wand |
| `/minions replace <player>` | Replace a lost wand; invalidate the previous one |
| `/minions revoke <player>` | Revoke the wand and dismiss the player's helpers |
| `/minions menu` | Open the menu while holding your active wand |
| `/minions status` | Show your helpers and job status |
| `/minions follow` | All helpers follow you |
| `/minions stay` | All helpers stay here (`zostan` / `zostancie` aliases) |
| `/minions deliver` | Request one-time delivery to yourself |
| `/minions cancel` | Cancel your job |
| `/minions depth <number>` | Set depth for subsequent jobs; minimum 1 |
| `/minions size <1-9>` | Set point-mode size |
| `/minions repair` | Remove missing helper records when recovery is needed |

`grant`, `replace` and `revoke` require `kingdomminions.admin` (OP by default). These also work in the server console without the slash. Players normally have `kingdomminions.use`; they do not need OP. Other commands operate on the calling player's helpers.

## Controls

Right-click with your wand to select two corners. Sneak + right-click opens the menu; right-clicking a chest saves the default delivery destination. Triple right-click on an ore block or tree starts the respective job. Menu settings apply to subsequent jobs rather than changing active jobs.

When leveling, the first point determines floor height. Hole filling leaves open water alone; an enclosed water area needs a continuous boundary at floor height inside the selection. Always try destructive jobs on a small area first. The plugin is not a replacement for land protection.

## Languages

See [LANGUAGES.md](LANGUAGES.md). Translation files are UTF-8, with numbered placeholders for dynamic values. Add a translation file and register it in `lang/languages.list`; no Java changes are required. Server owners can also add files to `plugins/KingdomMinions/lang/` and restart.

## Build

With Java 25:

```sh
./gradlew test jar
```

On Windows use `gradlew.bat test jar`. Output: `build/libs/KingdomMinions-Paper-1.0.27.jar`.

## Data and recovery

`plugins/KingdomMinions/data.yml` stores grants, worker identities and locations, preferences, active jobs, delivery targets and backpacks. `events.jsonl` records important actions. Back up these files together with the world. Runtime server files, player data, worlds and logs are not part of the source repository.

## License

MIT — see [LICENSE](LICENSE).
