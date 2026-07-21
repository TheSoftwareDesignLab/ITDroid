# ITDroid

ITDroid is a testing framework for Android apps oriented to internationalization (i18n).
Given an APK in one language, ITDroid:

1. Decodes it with apktool and reports hardcoded strings.
2. Translates the missing `strings.xml` resources into the selected languages using a local LLM.
3. Rebuilds and signs a multi-language APK.
4. Drives an emulator or a physical device via `adb` to explore the app in every language and detects Internationalization Presentation Failures (IPF): text that overflows, overlaps or shifts the layout when translated.
5. Produces `report.json` and a web report.

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

# Recommended host resources

16 GB RAM or more (emulator ~2-4 GB plus the LLM: a 3B model ~3-4 GB, a 7B model ~6-8 GB on CPU).

# Compile

```
mvn clean package
```

The runnable jar is `target/ITDroid-1.0.0.jar`.

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

The output directory contains the results and intermediate steps, including `report.json`. Open the dashboard in `webreport/` (it reads `report/report.json`) to browse the IPF findings visually.

See ARCHITECTURE.md for how the code is organized and REFACTOR.md for the modernization change log.
