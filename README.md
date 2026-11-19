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

Option A, Ollama (recommended, easiest): install it from https://ollama.com/download (0.40 or later). Nothing else is needed: with the default configuration ITDroid starts Ollama if it is down and downloads the model the first time. To download it beforehand:

```
ollama pull hf.co/pfnet/plamo-3-translate-0.8b-GGUF:Q4_K_M   # PLaMo Translate (~0.6 GB), the model ITDroid was validated with
```

Option B, llama.cpp (llama-server): get a build from https://github.com/ggml-org/llama.cpp/releases and a GGUF model, set `translation.engine=llamacpp`, and either start `llama-server -m <model>.gguf --port 8080` yourself or write that command in `translation.autoStart`.

LM Studio or any other OpenAI-compatible server also works (`translation.engine=api` with its `translation.endpoint`). Ollama runs llama.cpp under the hood and adds model management; ITDroid talks to either through the same endpoint, so it is engine-agnostic.

Two kinds of model work. A general chat model (`qwen2.5:3b`, `mistral:7b`) receives the texts in batches, with instructions. A model trained only to translate (Google's TranslateGemma, Tencent's Hy-MT2, PFN's PLaMo Translate) receives one text per request in the prompt it was trained with; ITDroid picks the way from the name of the model (`translation.style`, below). A model of Hugging Face is named `hf.co/<user>/<repository>:<file>`, as Ollama 0.40 or later pulls it straight from there; the final validation of this version (REFACTOR.md, section 12) used `hf.co/pfnet/plamo-3-translate-0.8b-GGUF:Q4_K_M`.

# Recommended host resources

16 GB RAM or more (emulator ~2-4 GB plus the LLM: PLaMo Translate under 1 GB, a 3B chat model ~3-4 GB, a 7B one ~6-8 GB on CPU).

# Compile

```
mvn clean package
```

The runnable jar is `target/ITDroid-1.0.0.jar`. The build runs the unit tests first (JUnit 5, in `test/`, same packages as `src/`); `mvn test` runs only the tests. They need no device and no LLM.

# Usage

Everything is set in one file, `itdroid.config.properties`, at the root of the repository. Write the path of the APK in `apkPath`, start the emulator, and run from the ITDroid folder:

```
java -jar target/ITDroid-1.0.0.jar                # evaluate the app of itdroid.config.properties: needs the device and the LLM
java -jar target/ITDroid-1.0.0.jar --static       # static analysis only: no device, no LLM
java -jar target/ITDroid-1.0.0.jar --report       # browse the evaluations in the web report
java -jar target/ITDroid-1.0.0.jar --reanalyze results/   # recompute the comparison of stored evaluations
```

The first two also take another config file after the mode (`java -jar target/ITDroid-1.0.0.jar other.properties`). The last three have their own sections below.

# Configuration (itdroid.config.properties)

The file is the only place a run is configured and every key is commented in it. Only `apkPath` is required; the rest have the default written there. In a path write `/` or `\\` (the format reads a single `\` as an escape and drops it).

The app and the device:

- `apkPath`: the APK to evaluate. This exact file is reinstalled on the device at the end, so the device is always left on the original app.
- `appPackage` (empty): the package of the app. Empty takes it from the manifest of the APK, read without decoding it; if it is written and it is not the package of the APK, ITDroid stops and names the right one.
- `emulator` (empty): the AVD name (`Test4`) or the serial (`emulator-5554`, or that of a phone) of the device to use. Empty uses the only device connected; with several and none named, it takes the first one and warns. A device named here that is not connected stops the run with the list of the connected ones.
- `resultsDir` (results): the folder of the results, one sub-folder per app (`results/<package>/`). It is created if missing, and it is the folder `--report` shows.
- `extraFolder` (extra): the folder with `apktool.jar` and `uber-apk-signer.jar`.

Languages:

- `sourceLanguage` (en): the language the texts of the app are written in. Empty asks the model to detect it.
- `languages` (es,ar,ru): the languages to evaluate, comma separated, with the codes Android uses for its folders (`es`, `ar`, `ru`, `fr`, `hi`, `zh-rCN`...). Each one adds a build and a replay of the app. A language the model does not handle (PLaMo Translate has a closed list of 53; Portuguese is not in it) is skipped with the reason.
- `alpha` (0): default strings a language may lack and still count as shipped by the app.

Translation model:

- `translation.engine` (ollama): `ollama` (local Ollama), `llamacpp` (local llama.cpp server) or `api` (a remote OpenAI-compatible endpoint).
- `translation.model` (PLaMo Translate): the model as the engine knows it.
- `translation.autoStart` (true): `true` starts Ollama if it is down and pulls `translation.model` if it is missing; `false` starts nothing, the server must be running; anything else is a command run to start the server (`llama-server -m model.gguf --port 8080`), whose output goes to `llm-server.log` in the folder of the app. A model name is accepted only when it is the one of `translation.model`, so the two lines cannot name different models. A server ITDroid started is stopped at the end; one that was already running is reused and left alone.
- `translation.endpoint` (empty): the chat-completions endpoint. Empty uses the one of the engine (`http://localhost:11434/v1/chat/completions` for Ollama, `:8080` for llama.cpp); required for `api`.
- `translation.apiKey` (empty): the bearer token for `api`. It can also be given in the environment variable `ITDROID_LLM_API_KEY`, which keeps it out of the file. Every `translation.*` value can be given that way (`ITDROID_LLM_ENDPOINT`, `ITDROID_LLM_MODEL`...); the file wins.
- `translation.timeoutSeconds` (180) and `translation.temperature` (0, the most faithful).
- `translation.style` (auto): how texts are sent to the model. `json` batches 25 texts in a JSON object (general chat models); `translategemma`, `template` and `plamo` send one text per request in the model's own prompt (Google's TranslateGemma prompt; `translation.promptTemplate` with `{source}`, `{target}` and `{text}`, by default Tencent Hy-MT2's prompt; or, for PFN's PLaMo Translate, the language codes in the system message and the bare text as the user message). `auto` picks by model name. PLaMo Translate needs a recent Ollama (0.40 or later; 0.34 cannot load its tokenizer).
- `translation.maskPlaceholders` (true for the one-text styles): replace `%1$s`, `{name}`, tags and entities with `<phN>` tokens around each request and restore them afterwards, so translation-only models cannot alter them.
- `translation.context` (a sentence saying the text is a label of an app): what PLaMo Translate is told about a text of up to three words, which on its own is ambiguous (`Open` gives `Abrir` with it and `Abierto` without). `none` turns it off. Other styles ignore it.
- `translation.protectedTerms` (empty): names that are never translated, see "Protected terms".
- `translation.keepResources` (empty): resource names of the app that are never translated nor counted as missing, comma separated. ITDroid already knows the ones of the Android libraries (`abc_action_bar_home_description`...), packaged in the jar.
- `translation.reuseInventory` (true): reuse the translations of a previous `strings_inventory.json` found in the folder of the app, so a re-run only asks the LLM for new texts.

Hardcoded strings:

- `hardcoded.translate` (ui): which hardcoded literals of the code are translated, by what the code does with them. `ui` translates only the places where the text is handed to the interface (a framework call that shows it, or a composable function). `ui+unknown` adds the places the scan cannot decide (the text goes to a method of the app, a field, an array, a returned value): it reaches text shown indirectly, such as the labels of a list built from data objects, and also translates some data. `all` translates every use, logs and data included, as ITDroid did before; it is there to compare. The name of the app is never translated.
- `hardcoded.extractCode` (true): move the translated literals of the code to string resources. The literal stays in the code and is handed to a small class ITDroid adds to the app (`itdroid.hc.Strings`), which returns the resource for that text in the language the app runs in, or the text itself when there is none. With false, they are only patched into the per-language APKs.
- `hardcoded.codePackages` (empty): extra package roots of the app's own code, comma separated. ITDroid finds the code from the manifest and the `BuildConfig` class, so this is only needed for an app split in modules that neither mentions.
- `hardcoded.extraRules` (empty): a file of extra rules saying what a call does with a text, tried before the built-in ones (`src/uniandes/tsdl/itdroid/helper/hardcoded-consumers.txt` has the format and the rules ITDroid ships).

Exploration, comparison and output:

- `preflight.launchTest` (true): install and launch the original app once before processing it; set false to skip.
- `exploration.maxStates` (5) and `exploration.maxClickables` (8): size of the source-language exploration; the translated versions replay it. 0 means no limit.
- `exploration.maxMinutes` (0, no limit): minutes the source-language exploration may take; what it found until then is kept. Set it when `exploration.maxStates` is 0.
- `exploration.settleSeconds` (3): extra wait after each launch before the first capture, on top of the automatic "UI stopped changing" check.
- `exploration.fillForms` (false): ask the LLM for a value for each text field and type it before tapping, so screens behind forms become reachable; the same values are typed in every language.
- `exploration.setup` (none): a file with one `adb` command per line, run after every clean start of the app, in the source language and in every translated one. It gives the app the content it needs before it has screens to show: a file in shared storage, a row of a content provider, an intent, a setting. Each line is what follows `adb -s <device>` (`push data/sample.mp3 /sdcard/Music/sample.mp3`); a line starting with `#` is a comment and an argument with spaces goes between double quotes. It does not sign in to an account and does not answer a screen of the app or of the system.
- `rtl.repair` (false): for each right-to-left language, also build the app with its layouts repaired to follow the direction of the language, and evaluate it next to the app as it is. The repair declares `android:supportsRtl="true"`, renames the attributes that name a side (`paddingLeft`, `layout_marginRight`, `layout_toLeftOf`...) to the ones that name the start or the end, and adds a copy of every layout that forces the direction (`res/layout-ldrtl`). It adds one build and one replay per right-to-left language, and the internationalized APK is built with the repair. See "Repair of right-to-left layouts".
- `comparison.minSimilarity` (0.5): share of the elements of a replayed screen, from 0 to 1, that must match those of the source screen for the two to be compared. Below it the app showed something else at that step (a dialog, an error) and the screen is listed as not compared, with the reason. See "What is reported as an IPF".
- `comparison.textOverGraphic` (true): also look at the screenshots of every compared screen for translated text drawn over a graphic of the app that is not an element of the dump (the arrow of a drop-down list, an icon, a border). In such a case every box is where it was, so only the pixels show it; it is reported as `textOverGraphic` (see "What is reported as an IPF"). It is a heuristic: false leaves it out.
- `output.keepLanguageApks` (true): keep the APK built for each target language in the results folder. With false each one is removed once its language is evaluated, and the folder keeps the source build and the internationalized APK; APKs are most of the size of a results folder.
- `debug.graphDumps` (false): also write `graph.txt` and `graph.json` next to every `result.json`, with every relation between every two elements of every screen. Large; only to debug the comparison by hand.

# Protected terms

Names of people, places, products and brands must come out of a translation as they went in. List them in `translation.protectedTerms`, comma separated, written as the app writes them (`translation.protectedTerms=Juan Pérez, New York, Beanbook Cloud`). Each term is replaced by a token before the text reaches the model and put back afterwards, so no model can translate, transliterate or inflect it; a translation that comes back without one is discarded and the source text is kept. Matching is case-sensitive and by whole words, so do not list ordinary words.

Protected without being listed: e-mail addresses and URLs inside a text, and the name of the app when it is recognisably a name (several words, or a capital, digit or symbol inside, such as `ShiftSwift`). The name of the app on its own, as a resource or as a literal, is never translated. A name that is in neither place is translated like any other word: a translation model does not know it is a name.

# Devices: emulator and physical

The device is driven entirely through `adb`, so both emulators and physical devices work. The app language is switched per-app (`cmd locale set-app-locales`), which requires Android 13+ (API 33) but needs no root, so standard Play Store emulators and retail phones are fine.

What ITDroid supports: a device with Android 13 or later (API 33+), whose architecture runs the native code of the app: an x86 or x86_64 (AMD64) emulator, or an ARM phone for an app that only ships ARM code. What it does not process is refused before anything is decoded or explored, with the reason in the console and in `report.json`:

- a device older than Android 13 ("ITDroid needs Android 13 (API 33) or newer");
- an app whose native code is only for architectures the device lacks ("the APK only ships native code for [arm64-v8a]");
- an app that needs a newer Android than the device (its `minSdk`);
- an app built for Android 5.1 or older (`targetSdk` below 23, or no `targetSdk` and a `minSdk` below 23): Android 13 opens it behind a review of its permissions and a notice that it was made for an older version of Android, which ITDroid does not answer, and Android 14 and later do not install it.

The same app gives other figures on another version of Android, because the platform draws the same layout differently: on Android 16 Arabic text takes more height than on Android 13 and the rows of a form grow (rereminder, same build and same path: 14 findings in Arabic on Android 13, 73 on Android 16; Spanish and Russian the same on both). Compare runs made on the same device.

The language is set before the app starts and read back once its first screen is up. Some apps take it away the first time they start after being installed (an app that uses AppCompat with `autoStoreLocales` does): ITDroid sets it again and starts the app over, and the second start keeps it. `languageRestarts` in the counts of each exploration and replay says how many starts were repeated; an app that takes the language away every time is evaluated in the language it chooses, and the console and `languageRefused` say so.

If the device stops answering between two languages, ITDroid waits up to two minutes for it and, if it does not come back, ends the run there with what was evaluated until then, instead of failing every language that was left.

# Output

The output directory contains the results and intermediate steps:

- `report.json`: preflight results, inventory counts, and per language the states, the pairing with the source graph, the replay summary and the IPFs. Rewritten after each language. `hardcoded` is the number of distinct hardcoded strings and `inventory` breaks it down (in code, in XML, places, by use, translatable); `nonInternationalized` says, per language, whether the app ships it and which default strings it lacks; `codePackages` lists the packages taken as the app's own code. The source language carries `exploration`: how many screens were found, what ended the exploration (`stop`: `maxStates`, `maxMinutes`, `maxClickables` when every screen was tapped up to the limit per screen, `exhausted` when nothing was left to tap), the activities visited and declared, and the seconds, taps, restarts and dumps it took; each language has the same counts of its replay under `replay`. `preflight.app.frameworks` names the cross-platform frameworks the app is built with (`flutter`, `react-native`, `capacitor`, `cordova`, `compose-multiplatform`, `xamarin`, `unity`; empty for an app whose interface is Android views or Compose), and `sourceLanguageCheck` says whether the default strings read like the language they are translated from. Each language has `screenText` (see "Text that did not change") and, with `rtl.repair=true`, a right-to-left language has `rtlRepair` (see "Repair of right-to-left layouts").
- `strings_inventory.json`: every string of the app with its origin (`res/values/strings.xml`, `smali_classes2/.../Foo.smali:123`, `res/layout/main.xml:12`), whether it is translated and why not, and its translations. A hardcoded string also has its use (`consumer`: `ui`, `log`, `exception`, `data`...) and every place it appears in, with class, method and the call that receives it. Review or correct it by hand; the next run reuses it.
- `trnsResults/<lang>/` and `noTrnsResults/<lang>/`: `result.json`, screenshots (plus the large `graph.txt` and `graph.json` debug dumps when `debug.graphDumps=true`); the source language also has `exploration_script.json` (the recorded path) and each translated language `replay_report.json` (what could be reproduced and where it diverged).
- `<package>-<lang>-aligned-debugSigned.apk`: the APK built for each language, with its translated resources and its hardcoded strings patched. The one of the source language is the build that was explored. With `output.keepLanguageApks=false` the ones of the target languages are removed once each language is evaluated. The unsigned APK apktool writes before signing is not kept.
- With `rtl.repair=true`, for each right-to-left language: `rtlResults/<lang>/` (the `result.json`, screenshots and `replay_report.json` of the repaired app) and `<package>-<lang>-rtl-aligned-debugSigned.apk` (the repaired build).
- `<package>-aligned-debugSigned.apk`: the internationalized APK, a single build with the resources of every language processed, including the hardcoded strings moved to resources (`itdroid_code_<hash>` for the code, `itdroid_hc_N` for the XML). It follows the language of the device like any app. A hardcoded string of the code that could not be moved is translated only in the per-language APKs. A string the code reads once into a static field keeps the language the app started in until the app restarts. If this build fails there is no file under this name: `internationalizedApk.built` is false in `report.json`, which names the source build instead.
- `hardcoded_<lang>.csv`: what was done with every hardcoded string for that language, one row per place (a line of smali or an attribute of the XML): `extracted` to a resource, `patched`, `reverted`, or `not translated` with the reason, plus class, method, use and the call that receives the text.
- `ipfs.csv`: the IPFs as a flat list: `language;state;nodePos;ipfScore;langNodePos;kind`. `nodePos` is the position of the element in the dump of the source screen and `langNodePos` in the translated one (-1 when that screen does not have it).
- `hcs.txt`: the hardcoded strings grouped as in the original tool, a line with the class, a tab and the method, two tabs and each literal of that method, followed by its use in brackets. The literals of the XML come after the code: file, element, literal with its attribute.

If the app does not build or start once its literals are read from resources, they go back to the code and are patched per language instead (`codeExtraction.reverted` in `report.json`). If the build of a language does not start, or crashes during the replay where the original app did not, ITDroid rebuilds that language with its hardcoded strings in the source language and evaluates that; `report.json` records it per language (`hardcodedFallback`, `revertedHardcoded`, `crashes`) and the CSV marks the rows as reverted.

An answer of the model that is not a translation is discarded: empty, without a placeholder or a protected term of the source, far longer than the text, a comment about the task, or left in Latin letters when the language uses another alphabet. The text is asked for again and, if the answer is no better, kept in the source language; `performance.llm.answersRejected` in `report.json` counts them. A language the model does not handle (PLaMo Translate has a closed list of 53; Portuguese is not in it) is not evaluated, and its entry in `report.json` says why.

Some apps cannot be rebuilt by apktool exactly as decoded. Two known cases are adjusted and the build tried again, and `buildAdjustments` in `report.json` lists what was done: an `android:` attribute newer than the Android apktool knows is removed, and an attribute of the app whose declaration was lost in the decoding (Material 3's `state_with_icon`) is declared again.

ITDroid exits with code 1 when it rejects or aborts an app; the reason is in the `error` field of `report.json`. Besides the findings, `report.json` records the run itself: `itdroid` (version, start/end, status), `environment` (host, device model and API, translation model and style, configuration) and `performance` (seconds per phase, translation requests, ITDroid's CPU time and heap, the host's CPU load and memory in use).

`report.json` is written from the first second of a run, with `itdroid.status` set to `running`, and rewritten after every step. A run ends as `finished` or `aborted`; one stopped from outside (Ctrl+C, the terminal closed) ends as `interrupted`. A process killed outright cannot write anything, so its report keeps saying `running`: the web report tells from the process id the report carries (`itdroid.pid`) that the run is gone and shows it as interrupted.

A results folder can be moved to another place or published. Nothing in it names the user or a folder of the machine it was made on: files of the evaluation are named by their path from the results folder (`apkPath` in `report.json`, `screenShot` in each `result.json`), the APK that was evaluated is identified by its SHA-256 (`apkSha256`), and an absolute path that ends up in a text, such as the message of an error, has its start replaced by `<output>` (the results folder), `<itdroid>` (the folder ITDroid ran in) or `~` (the home of the user).

# Web report

Every evaluation goes to `<resultsDir>/<package>/`, `results/<package>/` by default. To browse them, start the local report server from the ITDroid folder; it prints the address and opens your browser:

```
java -jar target/ITDroid-1.0.0.jar --report                        # serves the resultsDir of itdroid.config.properties
java -jar target/ITDroid-1.0.0.jar --report path/to/folder         # any folder whose sub-folders hold a report.json
java -jar target/ITDroid-1.0.0.jar --report --port 9000 --no-browser
```

The page (`webreport/`) lists the evaluated apps, each with the status of its run (a run still going shows as `running`, one that was killed as `interrupted`), and shows the selected one in tabs:

- Overview: the app, the run, and one row per language with its findings and the share of the source texts it still shows. It says what the app is built with, and warns when it is a cross-platform framework, when the default strings do not read like the source language, and when the exploration ran out of things to tap.
- Languages & IPFs: a language whose screens show the text of the source is marked "same text as source", with the count, and a note says how many of the texts that did not change are not in the APK, with examples. With `rtl.repair=true` a right-to-left language shows what the repair changed and the findings before and after it, and each screen has the screenshot of the repaired app next to the others. For each language, how many elements were compared, how many moved or changed size, how many only have a neighbour that changed, and what is missing or extra, with one button per kind of change that narrows the screens to it. Each screen opens with the source and the translated screenshots side by side and the affected elements outlined, outlined by severity. Yellow is a severe finding, one that breaks the screen (a text over a graphic, an element that now steps on another one, an element that reaches past the sides of the screen, such as a menu wider than its window). Translucent red is a finding that rearranges the screen without breaking it (an element moved, grew, or its text takes more or fewer lines). Orange is an element only one of the two screens has, or one cut by the bottom of its scrolling area that the translated screen shows more or less of (outlined in both). Blue, purple and magenta are information (the elements a finding relates to, such as the element a longer text pushed down, the estimated mirrored position, the visual difference). Each language shows its count of severe findings, and so does the table of the overview. Each outline is placed from the position the element has in the dump, as a share of the screen. For a right-to-left language a third image draws where each element that was not mirrored would be in a mirrored screen. The last image is the visual difference between the two screenshots: the source in grey and, in magenta, every pixel that is not the same (for a right-to-left language the translated screenshot is flipped first, so a layout that mirrored well lines up). The list opens on "every screen": all the screens of the source exploration, also the ones compared without findings, the ones reached but not compared, with the reason, and the ones the replay did not reach; "with findings" narrows it to the screens with findings. `#<evaluation>/languages/<language>/<screen>` in the address opens that screen.
- Hardcoded strings: the ones extracted from the code into resources, the ones extracted from the XML, the ones patched in each language build and the ones not translated with the reason, each with its class, method, use and resource; what was applied in each language build; a search box, a filter by use and pages of 50 rows.
- Non-internationalized strings: per language, whether the app shipped it and which default strings it lacks, with their text and ITDroid's translation.
- ITDroid performance and Environment: how long each phase of the run took and what ITDroid used of the machine (it is the cost of the evaluation, not a measure of how the app performs), and the static facts of the run (device, Android API, translation model, configuration).
- Artifacts: a link to every file of the evaluation that exists, each exploration in the folder it is in (`trnsResults/` for a language the app shipped, `noTrnsResults/` for one ITDroid translated).

A local server is needed because browsers do not let a page opened from disk read other files; the server only listens on 127.0.0.1 and only serves `webreport/` and the results folder.

The page can be checked without a browser. With Node 18 or later and the jar built, this starts the report server on a small sample of results (`webreport/test/sample`, one made-up app that shows every case of the report), runs the page against it and checks what every tab shows; `--url` checks the folder a server already running serves instead:

```
node webreport/test/smoke.js
node webreport/test/smoke.js --url http://127.0.0.1:8585
```

The sample is also a quick way to see the report before evaluating anything: `java -jar target/ITDroid-1.0.0.jar --report webreport/test/sample`.

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
2. If fewer than `comparison.minSimilarity` (half, by default) of the elements of the larger screen could be paired, the app showed something else there, and the screen is not compared: it is listed in `statesNotCompared` with the reason. So is a screen for which the replay kept the same capture as for another screen of the source: a replay that lost its way can believe it reached a screen while it is still on another one. The capture is compared with the source screen it matches best (more elements paired, then more of them in the same place), and the other one is left out ("shows the same capture as screen N").
3. For every two paired elements, the relations they had in the source (one is `LEFT`, `RIGHT`, `ABOVE` or `BELOW` the other, contains it, shares an edge with it, overlaps it) are set against the ones they have now. An element with a relation lost or gained is a finding. So is an element only one of the screens has, reported once at the top of what is missing or new.

4. A failure that moves no box is looked for in the screenshots (`comparison.textOverGraphic`): a translated text drawn over a graphic of the app that is not an element of the dump. The arrow of a drop-down list is part of its background image; when the text grows or is aligned to the other side (Arabic text aligns right by itself in a layout that is not mirrored), it runs over the arrow and every box is still where it was. For each element with text that stayed in place, the box of its parent is looked at in both screenshots: what the source draws there that stays the same in the translation and stands alone (a letter has others beside it) is a graphic, and translated text drawn over a good part of it, which the source text did not touch, is a finding of the element. A graphic that is an element, or part of an element that moved, is left to the boxes.

5. An element that reaches past the left or right side of the window that holds it is a finding of its own kind, `outOfFrame`, and so is a window that reaches past the side of the app. A menu or a dialog is dumped alone, so its own box is the window; the app is the widest of the top-level boxes of the source screens. A menu that grows within the app to fit its text is not out of anything, while a list that grows past an app shown as a window, up to the edge of the screen where its text is cut, is. Only the highest such element is reported, so that list is one finding with all its rows inside.
6. A translated text that takes more or less height, so that it wraps into more lines or fits in fewer, pushes what follows down or up without changing any relation, and is a finding of its own, `linesAdded` or `linesRemoved`, with the element it pushed as the related one (`moved`, how many pixels it moved). It counts when the height changes by at least 1 % of the height of the screen and a fifth of the height of the element: a line is two to three hundredths of the screen, while a script with taller letters (Arabic, Devanagari) adds less than one, which is not reported. Only elements with a text or with no children count, since a container grows with what it holds.
7. An element cut by the bottom of the scroll view or list it is in (it touches its bottom edge) that the translated screen shows more or less of is `moreOnScreen` or `lessOnScreen`, outlined in orange: the translated screen shows content the source did not, or hides some it showed. `heightChange` in the finding says by how many pixels, for these four kinds.

A container does not take the finding of what it holds: when a child is pushed to the edge of its container, the child changed and the container did not, so only the child is reported.

Five rules keep out what is not a failure. A change of alignment is ignored when either element is a text, because a translated text gets wider or narrower and that moves its edges. Left and right are compared only between two elements of one row (their boxes share part of their height, in the source or in the translation): between two rows a text that gets wider, a title for instance, stops being left of something three rows below, which says nothing of the layout. Two boxes that cross by a sliver, at most 2 pixels and less than half of the smaller one, are side by side and do not overlap: the layout rounds every edge, and a mirrored layout rounds them again (two colour circles that touch in English share a pixel in Arabic). A change of a few pixels is no change: an element is still inside the one that holds it, or its window, if it passes its side by no more than a hundredth of the width of the screen (at least 6 px, about 4 dp on a phone); it is over another one only if it reaches into it deeper than that plus the 2-pixel sliver; and a relation of position or alignment changes only when an edge moves more than that across the screen, or more than a hundredth of the height of the screen up or down (the same share under which a change of height is not counted). So the icon of a bottom bar item that a wider label makes 16 px shorter, an edge rounded one pixel another way, or a text of taller letters that steps 3 px out of its box are not reported, and the same layout gives the same result in every language. And between a left-to-right and a right-to-left language the layout is expected to be mirrored: who is left of whom and which side is aligned are expected to swap, so that is not reported; what is reported is two elements of the same container that did not swap (`missingMirroring`). Two elements side by side in one row must swap sides and alignments; two stacked ones (in different rows) only the edge they start from, since the other edge moves with the width of each.

In `report.json`, each language has:

- `amIPFs`: the number of findings.
- `ipfSummary`: `nodesCompared`; `nodesChanged` (the element itself moved or changed size) and `nodesAffected` (it stayed as it was, or was mirrored as expected, and it is its neighbours that changed); `componentsLost` and `componentsAdded`; and `byType`, how many findings carry each kind of change: `positionLost`, `positionAdded`, `alignmentLost`, `alignmentAdded`, `overlapAdded`, `overlapRemoved`, `missingMirroring`, `componentLost`, `componentAdded`, `textOverGraphic`, `outOfFrame`, `linesAdded`, `linesRemoved`, `moreOnScreen`, `lessOnScreen`. A finding can carry several. `severe` and `moderate` count the findings by severity. For a right-to-left language, also `siblingPairsChecked` and `siblingPairsNotMirrored`. `textOverGraphic`, the elements whose only finding is a text over a graphic (an element that also moved is counted among the changed and carries the type). `amIPFs` is the sum of changed, affected, lost, added and `textOverGraphic`.
- `ipfs`: each finding with `severity` (`severe` when it carries `textOverGraphic`, `overlapAdded` or `outOfFrame`, otherwise `moderate`), `stateID`, `nodeID` and `langNodeID` (its position in each screen, -1 when that screen does not have it), `kind` (`relations`, `componentLost`, `componentAdded`, `textOverGraphic`), `types`, `geometry` (`same`, `moved`, `resized`, `moved+resized`, `mirrored`), `score` (how many other elements it changed with; for a lost or added component, how many elements it is made of) and `relations`: against which element (`relNode`, `langRelNode`) which relations were `added`, `removed` or, when mirroring was expected, are `missing`, or for a text that changed its lines the element it `moved`; `heightChange`, the pixels of height a text or a cut element gained (negative when it lost them); and `graphics`, for a text drawn over a graphic, the box of each graphic in the translated screen (x1, y1, x2, y2).
- `pairedStates`, `statesNotCompared`, `missingDfltStates`, `missingLangStates`: which screens were compared.

These figures are not comparable with those of the 2020 paper, which paired elements by their position in the dump and counted mirroring as a failure. REFACTOR.md, section 12, has the before and after on a batch of 40 apps.

# Text that did not change

A translated screen that equals the source has no findings, and that reads as "the translation fits" also when nothing was translated on screen. So each language carries `screenText`: of the distinct texts of the source screens (the text and the content description of every element that has a word; what was typed in a field does not count), how many are on the translated screens letter for letter.

- `sourceTexts`, `unchanged` and `share` (from 0 to 1).
- `sameAsSource`: true when there are at least 5 texts and 90 % or more did not change. The console and the web report warn about it.
- `unchangedNotInApk` and `notInApkExamples` (up to 5): of the texts that did not change, the ones the APK does not carry anywhere: not in its resources, not among the strings of its code (every package, libraries included), not in its files (the assets, where a web interface or a React Native bundle keeps its texts, and the native libraries, where Flutter keeps them). They came from the network or the device, or the app put them together while it ran: an ad, the name of another installed app, a date, a number and its unit, a file name turned into words. ITDroid only translates what the APK has, so they stay as they are; the console and the web report say so, with examples. A text the app builds from pieces it carries can still count here when the pieces are not found as they are shown.

It is a fact about the screens, not a verdict. The usual cause is an app that keeps its texts where ITDroid does not translate them: the web page of a Capacitor or Cordova app, the Dart snapshot of Flutter, the resources of Compose Multiplatform (`preflight.app.frameworks` names what was found). A screen that is mostly data, such as a list of currencies, also keeps most of its texts. Either way, `0` findings in such a language does not say that a translation fits; and when the screens did not change at all, the findings of a right-to-left language are all missing mirroring.

# Repair of right-to-left layouts

With `rtl.repair=true`, a right-to-left language is evaluated twice: the app as it is, and the app with its layouts repaired. `rtlRepair` in the entry of the language has:

- `applied`: `supportsRtlAdded`, `layoutsChanged`, `attributesRenamed` and `forcedLayouts` (the copies in `res/layout-ldrtl` that force the direction).
- The same fields as the language itself for the repaired app (`amIPFs`, `ipfSummary`, `ipfs`, `replay`, `screenText`, `pairedStates`...), `resultsFolder` (`rtlResults`) and `apk`.
- `error` when the repaired app could not be built or evaluated; the evaluation of the app as it is does not depend on it.

The repair acts on the manifest and on the XML layouts. An app without `android:supportsRtl` is the case it fixes whole: every layout stays left to right until the manifest allows otherwise. It does nothing for an interface that is not made of Android layouts (a web page, Flutter), nor for a view that places its children by its own arithmetic, and what the app draws in Compose only needs the manifest. The report shows the findings before and after, so a repair that changed nothing is seen as such.

# Re-analysis without a device

The layout comparison can be recomputed from the `result.json` files of evaluations that were already explored. It needs no device and no LLM, and never modifies the folder it reads. A batch of 40 apps in three languages takes a couple of seconds, against the day it takes to explore them again:

```
java -jar target/ITDroid-1.0.0.jar --reanalyze results/                     # every evaluation in the folder
java -jar target/ITDroid-1.0.0.jar --reanalyze results/org.foo.app          # a single evaluation
java -jar target/ITDroid-1.0.0.jar --reanalyze results/ --out some/folder   # default: reanalysis/<input folder name>
java -jar target/ITDroid-1.0.0.jar --reanalyze results/ --min-similarity 0.4   # try another comparison.minSimilarity
```

The output folder gets a `summary.csv` (one row per evaluation and language: the IPFs the stored report says, the IPFs the current code computes, the breakdown of `ipfSummary` in columns, with `textOverGraphicOnly` and the `textOverGraphic` type, and the text check of `screenText`: `sourceTexts`, `unchangedTexts`, `sameTextAsSource`, `unchangedNotInApk`) and, per evaluation, a `reanalysis.json` with the recomputed comparison and an `ipfs.csv`. The texts of the APK are read from its `strings_inventory.json` and from an APK the evaluation kept in its folder; without one, only the inventory is known and more texts count as not in the APK. Two runs over the same data write the same files. Use it to check that a change to the comparison does what was intended before spending hours exploring again.

# Known limits

What an evaluation does not cover today. REFACTOR.md, section 12, has the validation these come from and the work that is pending.

- ITDroid translates the Android string resources of the app and the literals of its Dalvik code. An app written with a cross-platform framework keeps its texts somewhere else (the web assets of Capacitor or Cordova, the Dart snapshot of Flutter, the resources of Compose Multiplatform), and they are not translated: the app shows whatever it does by itself with the language of the device, often the source language. Its screens then come out equal to the source, and `0` findings means nothing was translated on screen, not that the translation fits. The report names the framework and marks every language whose screens show the text of the source (see "Text that did not change"); ITDroid does not translate those formats.
- The exploration only sees what the app shows by itself. An app that needs content or an account before it has screens to show (an audiobook player with an empty library, the client of a server) is evaluated on the few screens it reaches. `exploration.setup` runs `adb` commands before every start, which covers what `adb` reaches: a file in shared storage, a row of a content provider, an intent. It does not sign in to an account and does not answer a screen, so an app that asks for a folder in the system picker, or for a server and a password, stays at its door; `exploration.fillForms=true` only types a value in the text fields it finds. The report says when an exploration ended with nothing left to tap.
- The exploration is small by default: 5 screens and 8 taps per screen (`exploration.maxStates`, `exploration.maxClickables`). Both can be raised, or set to 0 for no limit, with `exploration.maxMinutes` as the stop. The first screens are cheap and each one after them costs more than the one before (in one app, 5 screens took a minute and a half and 9 took ten), once for the source language and once per language; and an app with a calendar or a list never runs out of screens.
- The replay expects the app to behave the same every time it starts. Where it does not (a first-run dialog, content that arrives from the network, a map that loads late), the path diverges: `replay_report.json` lists every divergence, and a screen that could not be reproduced is not compared. For such an app the findings change from one run to the next; read them together with the share of the replay that was reproduced.
- The source language is the `sourceLanguage` of the config (the model is asked to detect it from the strings only when it is empty). An app whose default strings are in another language than the one set there is translated from the wrong language. ITDroid warns when the default strings do not read like the language set (it looks for the most common short words of English, Spanish, French, German, Portuguese or Italian; for another source language it does not check), and does not guess which language they are in.
- A hardcoded string that is shown outside the screens of the app (a notification, a home-screen widget, an error of a sign-in) is translated and built into the APKs, but the exploration does not get to it, so its layout is not compared.
- With `hardcoded.translate=ui` a text that reaches the screen through the app's own data objects is not translated; `ui+unknown` reaches it and translates some data too.
- The translation is as good as the model. A small model takes a short word in the wrong sense. A figure with separators (`6,568.65`, `09:41`, `2026-11-14`) travels masked and comes back as written, so it is not adapted to the way the target language writes numbers either. A proper name that is not in `translation.protectedTerms` is translated like any other word.
- A text that is not in the APK (an ad or other content from the network, data of the device, what the app stored) is not translated, by design: the report counts the ones that stayed the same and gives examples.
- The check of a text over a graphic is a heuristic on the pixels. It only looks at elements whose box stayed in place (the others are already reported by the boxes) and at graphics that are not elements; it was tuned on 16 apps, where it found the arrow and the border of a drop-down list in Arabic and nothing else. A text that overflows into the margin of a screen with nothing drawn there is not a collision for it.
- An app built for Android 5.1 or older (`targetSdk` below 23) is refused (see "Devices").
- An app that only has ARM native code is rejected on an x86_64 emulator; it needs an ARM device. An app apktool cannot rebuild, beyond the two cases ITDroid adjusts, is rejected.
- An app that is a single web view (Capacitor, Cordova) takes longer than the rest: every restart of the replay waits for the page to load (21 and 44 minutes for three languages in the two such apps of the final validation, where the median was 17). What the dump sees of such an app also depends on the WebView of the device: the same app gave more elements, and more findings, after the data of the emulator was recreated. Its figures compare well within a run, where the source and the languages are captured on the same device, and not between two devices.
- A layout that does not mirror in a right-to-left language is reported (`missingMirroring`). With `rtl.repair=true` the app is also built with its manifest and its XML layouts repaired and evaluated again; the repair reaches no further than that (see "Repair of right-to-left layouts").

See ARCHITECTURE.md for how the code is organized and REFACTOR.md for the modernization change log.
