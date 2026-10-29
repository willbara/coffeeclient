# tools

## validate_mixins.py

Static pre-flight check for `src/main/java/dev/coffeeclient/mixin/*.java`.

Mixin failures are class-load hard crashes that happen *before* the client
initialises, so they cannot be caught by running the game normally. Existing
checks only verified that a target member exists; that misses the common
failure where the member exists but the `@At` instruction is not present in the
method selected by `method = ...`, which makes Mixin abort the whole transform.

This script, for every injection annotation:

* resolves the `@Mixin` target class (including nested `A.B` targets),
* resolves each `method = ...` selector to real methods (descriptor-exact),
* parses each `@At` target (`Lowner;name(desc)` for methods, `Lowner;name:Type`
  for fields),
* verifies the target instruction actually occurs in the selected method body,
* honours `opcode = Opcodes.X` and counts matches against `ordinal = N`.

Run it before testing a jar:

```
python3 tools/validate_mixins.py
```

It needs `javap` (JDK 25) and a Minecraft 26.2 client jar. Configure it with environment
variables:

```
MC_JAR=/path/to/26.2.jar \
LIB_DIRS=/path/to/libraries:/path/to/mods \
python3 tools/validate_mixins.py
```

`MC_JAR` is required, `LIB_DIRS` (jar folders searched recursively, `:`-separated) adds loader and
mod classes, and `JAVAP` overrides the `javap` binary if it is not on `PATH`.

## build_chatfilter_lists.py

Rebuilds the ChatFilter built-in word lists in
`src/main/resources/assets/coffeeclient/chatfilter/` (`casual.txt`: slurs, hate
speech and abuse; `profanity.txt`: extra swearing used by Family Friendly) from
four public word lists. The generated files are committed, so this only needs
rerunning to rebuild them:

```
python3 tools/build_chatfilter_lists.py <dir with words.json, profanity_en.csv, google-word-list.txt, en.txt>
```
