# ITDroid

ITDroid is a testing framework for Android apps oriented to internationalization (i18n).
Given an APK in one language, ITDroid:

1. Checks that the app can be processed at all (preflight): device on Android 13+, compatible ABIs, the package given is the one of the APK, launchable activity, not a Trusted Web Activity, and the original app actually starts on the device.
2. Decodes it with apktool and analyses it statically: the hardcoded strings of the code and of the XML, with what the code does with each one (`hcs.txt`); an inventory of every string (resources, plurals, arrays and hardcoded literals) with the file and line it came from (`strings_inventory.json`); and the strings each language lacks. The hardcoded strings the app shows are then moved to string resources, so they are translated like the rest. This step needs no device and can be run alone with `--static`.
3. Rebuilds the untouched app and explores it on the device in its source language, recording every tap as a script.
4. For each target language, with the app removed from the device: translates the inventory with a local LLM (reusing earlier translations), rebuilds and signs a per-language APK. A hardcoded string that could not be moved to a resource is patched in the code of that language's APK, in the places where the app shows it.
5. Installs each translated APK, replays the recorded script so it visits the same screens, uninstalls it, and compares the layouts state by state to detect Internationalization Presentation Failures (IPF): elements that the translated text pushes out of place, makes overlap or makes disappear, and, in a right-to-left language, elements that do not mirror.
6. Builds one internationalized APK that holds every language, hardcoded strings included, and writes `report.json` after every language (a run cut short keeps the finished ones) plus a web report.

The repository contains only source code. The external tools and the LLM model are not committed; install them as described below.

# Prerequisites

# Build tools

- JDK 21+ (the project targets Java 21; newer JDKs also work).
- Maven 3.9+.

# Android SDK (provides adb and the emulator)

Install via Android Studio or the standalone command-line tools, then make sure `adb` and `emulator` are on your `PATH`.

A device or emulator running Android 13+ (API 33 or higher) is required for the analysis. Any system image works (Google APIs or Google Play); root is not needed.

# External binaries (place them in extra/)

These are not committed. Download them and save them in the `extra/` folder with the exact names below (ITDroid invokes them by that path):

- apktool 2.12.x from https://github.com/iBotPeaches/Apktool/releases, saved as `extra/apktool.jar`
- uber-apk-signer 1.3.x from https://github.com/patrickfav/uber-apk-signer/releases, saved as `extra/uber-apk-signer.jar`

# Local LLM (for the translation step)

Translation uses a local LLM behind an OpenAI-compatible HTTP endpoint. It runs on the host (CPU is enough, no GPU, no cloud key). Any OpenAI-compatible runtime works; pick one.

Option A, Ollama (recommended, easiest):

```
# Install: https://ollama.com/download
ollama pull qwen2.5:3b          # small multilingual model (~2 GB); use qwen2.5:7b for better quality
# Ollama serves the endpoint automatically at http://localhost:11434
```

Option B, llama.cpp (llama-server):

```
# Get a build from https://github.com/ggml-org/llama.cpp/releases and a GGUF model, then:
llama-server -m <model>.gguf --port 8080
# Endpoint: http://localhost:8080/v1/chat/completions
```

LM Studio or any other OpenAI-compatible server also works. Ollama runs llama.cpp under the hood and adds model management; ITDroid talks to either through the same endpoint, so it is engine-agnostic. If the user has no Ollama, llama.cpp alone is enough.

# Configure the LLM

Copy `.env.example` to `.env` (in the folder you pass as `settingsDir`, or the working directory) and set the endpoint/model for the runtime you chose. Each value can also be a JVM system property (`-Dkey=value`) or an environment variable; precedence is system property, then env var, then `.env`.

- `ITDROID_LLM_ENDPOINT`: chat-completions endpoint. Default `http://localhost:11434/v1/chat/completions` (use `:8080` for llama.cpp).
- `ITDROID_LLM_MODEL`: model name as known by the runtime. Default `qwen2.5:3b` (e.g. `mistral:7b`).
- `ITDROID_LLM_API_KEY`: optional bearer token. Empty by default; Ollama ignores it.
- `ITDROID_LLM_TIMEOUT`: per-request timeout in seconds. Default `180`.
- `ITDROID_LLM_TEMPERATURE`: sampling temperature. Default `0` (most faithful).

# Not-translatable strings dictionary (strings.xml)

The folder you pass as `settingsDir` must also contain a `strings.xml` file listing the resource strings that must not be translated: framework/AppCompat entries such as `abc_action_bar_home_description`, plus any app string you want kept verbatim. ITDroid loads it as a dictionary; every `name` found there is skipped by the resource translator and by the translated-vs-not-translated language check. A ready-to-use `strings.xml` with the standard AppCompat entries ships in the repo root, so pointing `settingsDir` at the repo root works out of the box. If this file is missing the run fails.

# Protected terms (protected-terms.txt)

Names of people, places, products and brands must come out of a translation as they went in. List them, one per line, in a `protected-terms.txt` file in the `settingsDir` folder (the repository has a commented template). Each term is replaced by a token before the text reaches the model and put back afterwards, so no model can translate, transliterate or inflect it; a translation that comes back without one is discarded and the source text is kept. Matching is case-sensitive and by whole words, so do not list ordinary words.

Protected without being listed: e-mail addresses and URLs inside a text, and the name of the app when it is recognisably a name (several words, or a capital, digit or symbol inside, such as `ShiftSwift`). The name of the app on its own, as a resource or as a literal, is never translated. A name that is in neither place is translated like any other word: a translation model does not know it is a name.

# Recommended host resources

16 GB RAM or more (emulator ~2-4 GB plus the LLM: a 3B model ~3-4 GB, a 7B model ~6-8 GB on CPU).

# Compile

```
mvn clean package
```

The runnable jar is `target/ITDroid-1.0.0.jar`. The build runs the unit tests first (JUnit 5, in `test/`, same packages as `src/`); `mvn test` runs only the tests. They need no device and no LLM.

# Usage

There are two ways to run ITDroid: a single config file (recommended) or the classic positional arguments.

# Option A, config file (recommended)

Copy the template, edit it, and pass it as the only argument:

```
cp itdroid.config.example.properties itdroid.config.properties   # then edit it
java -jar target/ITDroid-1.0.0.jar itdroid.config.properties
```

The file holds every run parameter (APK, package, folders, emulator) and the translation-engine choice, so nobody has to remember the argument order. In its `translation.*` block you pick one engine:

- `ollama`: local Ollama. Set `model`, and `autoStart=<model>` to auto `serve`+`pull`.
- `llamacpp`: local llama.cpp server. Set `endpoint` (its port) plus optional `autoStart` launch command.
- `api`: remote OpenAI-compatible endpoint. Set `endpoint`, `model`, `apiKey` (your token).

Values set here take precedence over `.env` and environment variables. The real `itdroid.config.properties` is git-ignored (it may contain a token), so only the `.example` is committed. See itdroid.config.example.properties for the documented template.

Optional keys, all with sensible defaults:

- `preflight.launchTest` (true): install and launch the original app once before processing it; set false to skip.
- `hardcoded.translate` (ui): which hardcoded literals of the code are translated, by what the code does with them. `ui` translates only the places where the text is handed to the interface (a framework call that shows it, or a composable function). `ui+unknown` adds the places the scan cannot decide (the text goes to a method of the app, a field, an array, a returned value): it reaches text shown indirectly, such as the labels of a list built from data objects, and also translates some data. `all` translates every use, logs and data included, as ITDroid did before; it is there to compare. The name of the app is never translated.
- `hardcoded.extractCode` (true): move the translated literals of the code to string resources. The literal stays in the code and is handed to a small class ITDroid adds to the app (`itdroid.hc.Strings`), which returns the resource for that text in the language the app runs in, or the text itself when there is none. With false, they are only patched into the per-language APKs.
- `hardcoded.codePackages` (empty): extra package roots of the app's own code, comma separated. ITDroid finds the code from the manifest and the `BuildConfig` class, so this is only needed for an app split in modules that neither mentions.
- `translation.reuseInventory` (true): reuse the translations of a previous `strings_inventory.json` found in the output folder, so a re-run only asks the LLM for new texts.
- `translation.style` (auto): how texts are sent to the model. `json` batches 25 texts in a JSON object (general chat models); `translategemma`, `template` and `plamo` send one text per request in the model's own prompt (Google's TranslateGemma prompt; `translation.promptTemplate` with `{source}`, `{target}` and `{text}`, by default Tencent Hy-MT2's prompt; or, for PFN's PLaMo Translate, the language codes in the system message and the bare text as the user message). `auto` picks by model name. PLaMo Translate needs a recent Ollama (0.40 or later; 0.34 cannot load its tokenizer): `translation.model=hf.co/pfnet/plamo-3-translate-0.8b-GGUF:Q4_K_M`.
- `translation.maskPlaceholders` (true for the one-text styles): replace `%1$s`, `{name}`, tags and entities with `<phN>` tokens around each request and restore them afterwards, so translation-only models cannot alter them.
- `translation.context` (a sentence saying the text is a label of an app): what PLaMo Translate is told about a text of up to three words, which on its own is ambiguous (`Open` gives `Abrir` with it and `Abierto` without). `none` turns it off. Other styles ignore it.
- `exploration.maxStates` (5) and `exploration.maxClickables` (8): size of the source-language exploration; the translated versions replay it.
- `exploration.settleSeconds` (3): extra wait after each launch before the first capture, on top of the automatic "UI stopped changing" check.
- `exploration.fillForms` (false): ask the LLM for a value for each text field and type it before tapping, so screens behind forms become reachable; the same values are typed in every language.
- `comparison.minSimilarity` (0.5): share of the elements of a replayed screen, from 0 to 1, that must match those of the source screen for the two to be compared. Below it the app showed something else at that step (a dialog, an error) and the screen is listed as not compared, with the reason. See "What is reported as an IPF".
- `debug.graphDumps` (false): also write `graph.txt` and `graph.json` next to every `result.json`, with every relation between every two elements of every screen. Large; only to debug the comparison by hand.
- `alpha` (0): untranslatable strings tolerated before a language is considered not translated.

# Option B, positional arguments

```
java -jar target/ITDroid-1.0.0.jar <APKPath> <AppPackage> <ExtraCompFolder> <settingsDir> <alpha> <Output> <EmulatorName> [LLM]
```

1. `APKPath`: path of the APK to internationalize. This exact file is reinstalled on the device at the end, so the device is always left on the original app, never on a translation.
2. `AppPackage`: app main package name (must match the APK's real package; see `aapt dump badging`).
3. `ExtraCompFolder`: path of the `extra/` folder.
4. `settingsDir`: folder containing `settings.properties`, the not-translatable `strings.xml` dictionary, and, if used, `.env`. The repo root satisfies all three.
5. `alpha`: untranslatable strings tolerated when deciding if a language is translated.
6. `Output`: folder where results and intermediate steps are stored.
7. `EmulatorName`: name of the AVD (physical devices connected via `adb` also work).
8. `LLM` (optional): how to provide the local LLM. Accepts either form:
   - An Ollama model name, a bare token like `qwen2.5:3b` or `mistral:7b`. ITDroid points the translator at Ollama, starts `ollama serve` if it isn't running, and `ollama pull`s the model (downloading it if you don't have it yet), then uses it.
   - A launch command, anything with spaces, e.g. `"ollama serve"` or `"llama-server -m C:\models\qwen2.5-3b-instruct.gguf --port 8080"`. ITDroid runs it and waits until the endpoint from `.env` answers.

   If omitted, the server must already be running (ITDroid checks first and aborts with a clear message if it isn't). A server ITDroid started is stopped at the end; a server you already had running is reused and left alone.

Languages are selected/deselected in `settings.properties` (comment a line with `#` to skip it). The LLM endpoint/model are configured in `.env`; the `LLM` argument's model form overrides them to point at Ollama. Make sure `adb devices` shows a single device before running.

# Example

```
java -jar target/ITDroid-1.0.0.jar foo.apk org.foo.app ./extra/ ./ 2 ./results/ Pixel_API_34
```

Concrete example (Windows / PowerShell), the actual command used to validate the pipeline (MiniWallet app, package `com.example.dictoapp`, AVD `Test4`, Ollama with `mistral:7b`):

```powershell
java -jar target\ITDroid-1.0.0.jar "..\APKs\Original\app-debug.apk" "com.example.dictoapp" "extra" "." "0" "..\APKs\Traducciones" "Test4" "mistral:7b"
```

Quote each argument with spaces. The 8th argument `"mistral:7b"` makes ITDroid start Ollama if needed and download the model if you don't have it. Swap it for `"qwen2.5:3b"` for better multilingual quality.

Letting ITDroid manage the LLM (8th argument, quoted as one argument):

```
# Ollama model, pulls qwen2.5:3b if you don't have it, then uses it:
java -jar target/ITDroid-1.0.0.jar foo.apk org.foo.app ./extra/ ./ 2 ./results/ Pixel_API_34 "qwen2.5:3b"

# Explicit "ollama serve" command (uses the model from .env, already downloaded):
java -jar target/ITDroid-1.0.0.jar foo.apk org.foo.app ./extra/ ./ 2 ./results/ Pixel_API_34 "ollama serve"

# llama.cpp (no Ollama needed), endpoint http://localhost:8080 must be set in .env:
java -jar target/ITDroid-1.0.0.jar foo.apk org.foo.app ./extra/ ./ 2 ./results/ Pixel_API_34 "llama-server -m C:\models\qwen2.5-3b-instruct-q4_k_m.gguf --port 8080 --ctx-size 4096"
```

For the command form the `--port` (llama.cpp) must match `ITDROID_LLM_ENDPOINT`. The server's console output is written to `<Output>/llm-server.log`.

# Devices: emulator and physical

The device is driven entirely through `adb`, so both emulators and physical devices work. The app language is switched per-app (`cmd locale set-app-locales`), which requires Android 13+ (API 33) but needs no root, so standard Play Store emulators and retail phones are fine.

The input app may have any `minSdk`; only the test device must be Android 13+ for the IPF analysis. Older Android would need a rooted device and a system-wide locale change, not currently implemented.

# Output

The output directory contains the results and intermediate steps:

- `report.json`: preflight results, inventory counts, and per language the states, the pairing with the source graph, the replay summary and the IPFs. Rewritten after each language. `hardcoded` is the number of distinct hardcoded strings and `inventory` breaks it down (in code, in XML, places, by use, translatable); `nonInternationalized` says, per language, whether the app ships it and which default strings it lacks; `codePackages` lists the packages taken as the app's own code.
- `strings_inventory.json`: every string of the app with its origin (`res/values/strings.xml`, `smali_classes2/.../Foo.smali:123`, `res/layout/main.xml:12`), whether it is translated and why not, and its translations. A hardcoded string also has its use (`consumer`: `ui`, `log`, `exception`, `data`...) and every place it appears in, with class, method and the call that receives it. Review or correct it by hand; the next run reuses it.
- `trnsResults/<lang>/` and `noTrnsResults/<lang>/`: `result.json`, screenshots (plus the large `graph.txt` and `graph.json` debug dumps when `debug.graphDumps=true`); the source language also has `exploration_script.json` (the recorded path) and each translated language `replay_report.json` (what could be reproduced and where it diverged).
- `<package>-<lang>-aligned-debugSigned.apk`: the APK built for each language, with its translated resources and its hardcoded strings patched.
- `<package>-aligned-debugSigned.apk`: the internationalized APK, a single build with the resources of every language processed, including the hardcoded strings moved to resources (`itdroid_code_<hash>` for the code, `itdroid_hc_N` for the XML). It follows the language of the device like any app. A hardcoded string of the code that could not be moved is translated only in the per-language APKs. A string the code reads once into a static field keeps the language the app started in until the app restarts.
- `hardcoded_<lang>.csv`: what was done with every hardcoded string for that language, one row per place (a line of smali or an attribute of the XML): `extracted` to a resource, `patched`, `reverted`, or `not translated` with the reason, plus class, method, use and the call that receives the text.
- `ipfs.csv`: the IPFs as a flat list: `language;state;nodePos;ipfScore;langNodePos;kind`. `nodePos` is the position of the element in the dump of the source screen and `langNodePos` in the translated one (-1 when that screen does not have it).
- `hcs.txt`: the hardcoded strings grouped as in the original tool, a line with the class, a tab and the method, two tabs and each literal of that method, followed by its use in brackets. The literals of the XML come after the code: file, element, literal with its attribute.

If the app does not build or start once its literals are read from resources, they go back to the code and are patched per language instead (`codeExtraction.reverted` in `report.json`). If the build of a language does not start, or crashes during the replay where the original app did not, ITDroid rebuilds that language with its hardcoded strings in the source language and evaluates that; `report.json` records it per language (`hardcodedFallback`, `revertedHardcoded`, `crashes`) and the CSV marks the rows as reverted.

An answer of the model that is not a translation is discarded: empty, without a placeholder or a protected term of the source, far longer than the text, a comment about the task, or left in Latin letters when the language uses another alphabet. The text is asked for again and, if the answer is no better, kept in the source language; `performance.llm.answersRejected` in `report.json` counts them. A language the model does not handle (PLaMo Translate has a closed list of 53; Portuguese is not in it) is not evaluated, and its entry in `report.json` says why.

Some apps cannot be rebuilt by apktool exactly as decoded. Two known cases are adjusted and the build tried again, and `buildAdjustments` in `report.json` lists what was done: an `android:` attribute newer than the Android apktool knows is removed, and an attribute of the app whose declaration was lost in the decoding (Material 3's `state_with_icon`) is declared again.

ITDroid exits with code 1 when it rejects or aborts an app; the reason is in the `error` field of `report.json`. Besides the findings, `report.json` records the run itself: `itdroid` (version, start/end, status), `environment` (host, device model and API, translation model and style, configuration) and `performance` (seconds per phase, translation requests, ITDroid's CPU time and heap, the host's CPU load and memory in use).

# Web report

Every evaluation goes to `results/<package>/` unless the config sets `output=`. To browse them, start the local report server from the ITDroid folder; it prints the address and opens your browser:

```
java -jar target/ITDroid-1.0.0.jar --report                        # serves results/
java -jar target/ITDroid-1.0.0.jar --report path/to/folder         # any folder whose sub-folders hold a report.json
java -jar target/ITDroid-1.0.0.jar --report --port 9000 --no-browser
```

The page (`webreport/`) lists the evaluated apps and shows, for the selected one: the IPFs of each language with the source and translated screens side by side and the affected nodes outlined, the hardcoded strings and how they were translated, the time of every phase and the machine usage, the static facts of the run (APK, version, device, Android API, translation model, configuration) and links to every artifact. A local server is needed because browsers do not let a page opened from disk read other files; the server only listens on 127.0.0.1 and only serves `webreport/` and the results folder.

# Static analysis without a device

`--static` decodes the APK and stops after the static analysis. It needs no device and no LLM, and takes the same arguments as a run (the emulator name is not needed):

```
java -jar target/ITDroid-1.0.0.jar --static itdroid.config.properties
java -jar target/ITDroid-1.0.0.jar --static app.apk com.example.app extra settings results/com.example.app
```

It writes `hcs.txt`, `strings_inventory.json` and a `report.json` with the hardcoded strings, the inventory counts and the strings each language lacks. A normal run does the same analysis before it needs the device: if the device or the LLM is not available, the run says so at once, finishes the static analysis and then stops. An app without a default `strings.xml` is analysed and evaluated like any other; only its hardcoded strings can be translated.

A string counts as hardcoded when it is a literal of the app's own code that reads like text, or text written directly in an attribute of a layout, a menu, a preference screen, a navigation graph or the manifest (`android:text="Save"` instead of `@string/save`). For each literal of the code ITDroid follows its register to the instruction that consumes it and classifies the consumer: shown to the user (`ui`), `log`, `exception`, `data` (keys, SQL, patterns, comparisons), `compiler`, or one of the cases it cannot decide (`app-call`, `library-call`, `field`, `return`, `array`, `concatenation`, `unused`). A text appended to a `StringBuilder` takes the use of the whole sentence. Only a literal that is shown, or whose use is not known, is translated; logs, exception messages and data are listed but kept verbatim.

The table that says what each API does with a string is `hardcoded-consumers.txt` (packaged in the jar, one regular expression per line). To teach ITDroid an API of your app without rebuilding, put a file with that name in the settings folder; its rules are tried first:

```
# the charting library of this app draws the labels it is given
ui   ^Lorg/thirdparty/Chart;->axis\(
log  ^Lcom/example/app/util/Tracer;->
```

# What is reported as an IPF

Each translated screen is compared with the source screen it reproduces (the same step of the recorded path, on the same activity).

1. The elements of the two screens are paired. The two trees are walked together from the top, and the children of two paired elements are matched in order when they are the same kind of element: same class and same resource id, then what they are like (clickable, with text, how much hangs from them) and, to break ties, where they are. An element that only one screen has costs that element alone; the ones after it are still compared with their true counterparts. A Compose test tag that shows up as `resource-id` is not taken for an id, because apps use the visible label as the tag and it changes with the language.
2. If fewer than `comparison.minSimilarity` (half, by default) of the elements of the larger screen could be paired, the app showed something else there, and the screen is not compared: it is listed in `statesNotCompared` with the reason.
3. For every two paired elements, the relations they had in the source (one is `LEFT`, `RIGHT`, `ABOVE` or `BELOW` the other, contains it, shares an edge with it, overlaps it) are set against the ones they have now. An element with a relation lost or gained is a finding. So is an element only one of the screens has, reported once at the top of what is missing or new.

Two rules keep out what is not a failure. A change of alignment is ignored when either element is a text, because a translated text gets wider or narrower and that moves its edges. And between a left-to-right and a right-to-left language the layout is expected to be mirrored: who is left of whom and which side is aligned are expected to swap, so that is not reported; what is reported is two elements of the same container that did not swap (`missingMirroring`).

In `report.json`, each language has:

- `amIPFs`: the number of findings.
- `ipfSummary`: `nodesCompared`; `nodesChanged` (the element itself moved or changed size) and `nodesAffected` (it stayed as it was, or was mirrored as expected, and it is its neighbours that changed); `componentsLost` and `componentsAdded`; and `byType`, how many findings carry each kind of change: `positionLost`, `positionAdded`, `alignmentLost`, `alignmentAdded`, `overlapAdded`, `overlapRemoved`, `missingMirroring`, `componentLost`, `componentAdded`. A finding can carry several. For a right-to-left language, also `siblingPairsChecked` and `siblingPairsNotMirrored`. `amIPFs` is the sum of changed, affected, lost and added.
- `ipfs`: each finding with `stateID`, `nodeID` and `langNodeID` (its position in each screen, -1 when that screen does not have it), `kind` (`relations`, `componentLost`, `componentAdded`), `types`, `geometry` (`same`, `moved`, `resized`, `moved+resized`, `mirrored`), `score` (how many other elements it changed with; for a lost or added component, how many elements it is made of) and `relations`: against which element (`relNode`, `langRelNode`) which relations were `added`, `removed` or, when mirroring was expected, are `missing`.
- `pairedStates`, `statesNotCompared`, `missingDfltStates`, `missingLangStates`: which screens were compared.

These figures are not comparable with those of the 2020 paper, which paired elements by their position in the dump and counted mirroring as a failure. REFACTOR.md, section 12, has the before and after on a batch of 40 apps.

# Re-analysis without a device

The layout comparison can be recomputed from the `result.json` files of evaluations that were already explored. It needs no device and no LLM, and never modifies the folder it reads. A batch of 40 apps in three languages takes a couple of seconds, against the day it takes to explore them again:

```
java -jar target/ITDroid-1.0.0.jar --reanalyze results/                     # every evaluation in the folder
java -jar target/ITDroid-1.0.0.jar --reanalyze results/org.foo.app          # a single evaluation
java -jar target/ITDroid-1.0.0.jar --reanalyze results/ --out some/folder   # default: reanalysis/<input folder name>
java -jar target/ITDroid-1.0.0.jar --reanalyze results/ --min-similarity 0.4   # try another comparison.minSimilarity
```

The output folder gets a `summary.csv` (one row per evaluation and language: the IPFs the stored report says, the IPFs the current code computes, and the breakdown of `ipfSummary` in columns) and, per evaluation, a `reanalysis.json` with the recomputed comparison and an `ipfs.csv`. Two runs over the same data write the same files. Use it to check that a change to the comparison does what was intended before spending hours exploring again.

See ARCHITECTURE.md for how the code is organized and REFACTOR.md for the modernization change log.
