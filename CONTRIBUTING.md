# Contributing

Submit changes through a pull request and link the relevant issue. Describe the problem, resulting behavior, validation and limitations. Keep each PR focused. MarekPoskrobko reviews all changes and makes the final merge decision; AI review is an additional check, not permission to merge. New commits invalidate previous approvals. Resolve review conversations before merging.

Bug reports and improvements are welcome. For a reproducible bug, include the plugin version, Minecraft/server build, relevant plugins, job type, selection dimensions, expected result, and what actually happened. Remove private player information and credentials from logs before sharing them.

For code changes, use Java 25, run `./gradlew test jar` (or `gradlew.bat test jar` on Windows), and describe the behavior your change protects. Keep saved job data compatible; do not reset user data to simplify a fix.

For translations, see [LANGUAGES.md](LANGUAGES.md). Add the UTF-8 catalog and its entry in `lang/languages.list`, preserve placeholders, and verify the actual menu wording in Minecraft.

The project is inspired by Minions gameplay but does not include upstream mod code or assets. Please submit only code or assets you are entitled to contribute under the project's MIT license.
