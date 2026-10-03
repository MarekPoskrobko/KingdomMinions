# Administrator quick start

Requires a Paper/Purpur server and Java 25. Version 1.0.27 was tested on Purpur 26.3 build 2641.

## Install

Back up your world and plugin data, stop the server, and put `KingdomMinions-Paper-1.0.27.jar` in `plugins/`. Keep only one version of the plugin JAR. Preserve `plugins/KingdomMinions/` on updates and restart the server; do not use `/reload` to replace the plugin.

Players do not install this JAR in CurseForge or their `mods/` folder.

## Grant a wand to every ruler

The target must be online and have an empty inventory slot. Run once per player:

```text
/minions grant PlayerName
```

Use the exact player name. In the server console, omit the leading slash. The player can then hold the wand, use `/minions menu`, and summon helpers.

```text
/minions replace PlayerName
```

Replaces a lost wand and invalidates the previous token. The player must be online and already have an empty slot.

```text
/minions revoke PlayerName
```

Revokes the grant, dismisses the player's helpers and cancels the job. This changes more than the held item; use it deliberately.

## Permissions

- `kingdomminions.admin`: grant, replace and revoke; OP by default.
- `kingdomminions.use`: use the assigned wand; enabled for players by default.

Do not give players OP or administrator permission merely to use helpers. A plain `/give` item does not substitute for the plugin's owner-bound wand.

## Player commands

`/minions menu`, `status`, `follow`, `stay`, `deliver`, `cancel`, `depth <number>`, `size <1-9>`, and `repair` operate on the calling player's own team. `zostan` and `zostancie` are aliases for `stay`. Menu, follow, stay and repair require the active wand in hand. Repair removes missing worker records; it is a recovery action, not a routine task.

Right-click a chest with the wand to save the default delivery destination. Use the menu for summoning, jobs, ground destinations, settings and naming.

## Languages

UI language follows each player's Minecraft locale. Polish and English are bundled; unsupported locales use English. Console messages use English. Custom worker names are preserved. Translation overrides live in `plugins/KingdomMinions/lang/`; restart after changing them. See [LANGUAGES.md](../LANGUAGES.md).

## Before production use

Try the plugin on a copy of your world with the exact plugins used by your server. Check digging, tree chopping, deliveries and protection behavior. Test large destructive orders on a small area first and keep backups. KingdomMinions is not a replacement for land protection.
