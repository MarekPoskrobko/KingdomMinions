# Adding a language / Dodawanie języka

KingdomMinions chooses each player's language from their Minecraft locale. Polish (`pl_PL`) uses Polish; English is the fallback for unsupported languages and console output. Players can use different languages on the same server.

## Add a translation

1. Copy `src/main/resources/lang/en.properties` to a language file such as `de.properties`, `fr.properties`, or `pt-BR.properties`.
2. Translate the values after `=`. Preserve every key and every numbered placeholder (`{0}`, `{1}`, etc.). Values may reorder placeholders to match the language's grammar.
3. Save the file as UTF-8. Keep `/minions` commands and permission names unchanged. Do not translate player names, custom helper names, or world names.
4. Add the filename without `.properties` (for example `de` or `pt-BR`) to `src/main/resources/lang/languages.list`. No Java changes are needed.
5. Run the test suite and check menus, wand descriptions, errors, selection hints, delivery messages, and command feedback in-game. Submit the translation as a pull request.

For server-only translations, place the file in `plugins/KingdomMinions/lang/` and restart the server. Additional files there load automatically. Missing entries fall back to the base language, then English; entries with incorrect placeholders are ignored with a log warning.

Existing active wands are relabeled when their owner joins or changes Minecraft's language. Open wand menus refresh on a locale change. Custom helper names are preserved. Default helper names are neutral (`Minion 1`, etc.). Existing custom names are not rewritten.

