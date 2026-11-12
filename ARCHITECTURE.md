# ITDroid Arquitectura

Cómo está armado el código: qué hace cada pieza, cómo fluyen los datos y dónde tocar para extenderlo.

En una frase: ITDroid toma un APK en un idioma, comprueba que se puede procesar, inventaría todos sus textos (recursos y hardcodeados) con su origen, explora la app original en el dispositivo grabando el recorrido, y por cada idioma destino traduce el inventario con un LLM local, reconstruye un APK, lo instala, repite el recorrido grabado, lo desinstala y compara los layouts contra el origen para reportar los IPFs (defectos visuales que solo aparecen al traducir).

# Pipeline end-to-end

El orquestador es `ITDroid.runITDroid`. El flujo:

1. Preflight (`Preflight`, `AdbExplorer.checkLaunch`): dispositivo con API 33+ y sus ABIs, código nativo del APK compatible, servidor LLM accesible (`LLMServerManager`), decodificación con apktool a `temp/`, manifiesto con actividad lanzable y que no sea una Trusted Web Activity, minSdk compatible, `strings.xml` presente, y prueba de arranque de la app ORIGINAL en el dispositivo. Cualquier fallo aborta con el motivo concreto.
2. Inventario (`StringInventory`): todos los `<string>`, ítems de `<plurals>` y `<string-array>` de `res/values/*.xml` y los literales UI del smali (`HardcodedStringFinder`), cada uno con su origen y con la razón por la que no se traduce si aplica. Se escribe a `strings_inventory.json`; si ya existía uno en la carpeta de salida, sus traducciones se reutilizan.
3. Construcción y exploración del origen: `APKToolWrapper.buildAPK` reconstruye la app intacta; `ExplorationHelper.exploreSource` la explora con el DFS de `AdbExplorer.explore`, que graba cada acción en `exploration_script.json`, y la desinstala al terminar.
4. Por cada idioma destino (`ITDroid.processLanguage`), con la app fuera del dispositivo: traduce los recursos desde el inventario (`Translator` + `LLMTranslator.translate`) si la app no los traía, traduce los literales hardcodeados (`LLMTranslator.translateUiLabels`), parchea el smali (`SmaliPatcher`), reconstruye y firma `<pkg>-<lang>-aligned-debugSigned.apk`, lo instala y repite el recorrido grabado (`AdbExplorer.replay`), lo desinstala, y compara contra el grafo origen (`LayoutGraphComparision`). `report.json` se reescribe tras cada idioma.
5. Cierre: restaura el APK con nombre por defecto desde la construcción origen, deja el smali intacto y reinstala el APK original en el dispositivo (`AdbExplorer.installApk`).

El LLM se habla por HTTP OpenAI-compatible (Ollama o llama.cpp). El dispositivo se maneja por `adb` (install, locale, dump, tap). apktool y uber-apk-signer viven en `extra/`.

# Mapa de paquetes y clases

Paquete raíz: `uniandes.tsdl.itdroid`.

- `ITDroid`: punto de entrada y orquestador de todo el pipeline; escribe `report.json` tras cada hito.

Paquete `translator/` (traducción, patrón Strategy):

- `TranslationInterface`: interfaz `translate(xmlPath, in, out)`.
- `Translator`: contexto Strategy que envuelve la estrategia.
- `LLMTranslator`: estrategia real; traduce los textos del inventario por HTTP OpenAI-compatible, en lotes globales de 25 en JSON (modelos de chat) o un texto por petición con el prompt propio del modelo (`translation.style` = `translategemma` o `template`, con los placeholders enmascarados como `<phN>`), usa el inventario como memoria, escribe `values-<lang>/strings.xml`, traduce los labels hardcodeados y sugiere valores para formularios.

Paquete `explorer/` (exploración del app en el dispositivo, sin root):

- `AdbExplorer`: install, locale por-app, permisos runtime, DFS con grabación (`explore`), reproducción del guion en otra versión (`replay`), prueba de arranque (`checkLaunch`), desinstalación; emite `result.json`.

Paquete `helper/` (infraestructura):

- `RunConfig`: lee el archivo de config (parámetros, motor de traducción, opciones de exploración y preflight).
- `Preflight`: comprobaciones previas de dispositivo, APK y app decodificada.
- `StringInventory`: inventario de strings con origen, reglas de "no traducir" y memoria de traducciones (`strings_inventory.json`).
- `LLMServerManager`: arranca o verifica el servidor LLM.
- `APKToolWrapper`: decodifica (`apktool d`) y reconstruye+firma (`apktool b` + signer).
- `HardcodedStringFinder`: detecta textos UI hardcodeados en el smali, con archivo y línea.
- `SmaliPatcher`: reescribe los `const-string` del smali con las traducciones.
- `ExplorationHelper`: lanza `AdbExplorer` por versión de idioma (exploración o replay).
- `LanguageBundle`: lee `settings.properties` (idiomas seleccionados).
- `NotTranslatableStringsDictionary`: strings que no se deben traducir.
- `Helper`: singleton con el directorio de trabajo y el nombre de paquete.
- `XMLComparator`: decide qué idiomas ya trae traducidos la app.
- `ExplorationException`, `ITDroidException`: excepciones.

Paquete `model/` (grafos y comparación, detección de IPFs):

- `LayoutGraph`: parsea `result.json` a `List<State>` + `List<Transition>`, por id de estado.
- `State`: una pantalla; convierte su `rawXML` en nodos y un grafo de relaciones espaciales; conserva el id del estado origen que reproduce.
- `AndroidNode`: un elemento UI (clase, bounds, texto, enabled).
- `AndroidNodeProperty`: enum de propiedades de nodo.
- `GraphEdgeType`: enum de relaciones espaciales (LEFT/RIGHT/ABOVE/BELOW/alineación).
- `Transition`, `TransitionType`: aristas del grafo (navegaciones).
- `LayoutGraphComparision`: empareja estados (por id reproducido, o posicionalmente) y compara grafos, produce IPFs.
- `IPF`: un Internationalization Presentation Failure.
- `IPFComparator`: ordena IPFs por severidad.

Dependencias externas (no van en el repo, ver README): apktool.jar y uber-apk-signer.jar en `extra/`, adb (Android SDK) y un servidor LLM local. Librerías Maven: `json-simple`, `commons-io`, `jdom2`.

# Las fases en detalle

# Fase 0, preflight

Lee los parámetros de entrada (archivo de config, recomendado, o los 7/8 argumentos posicionales). Luego, antes de gastar minutos en traducir o explorar:

- Dispositivo: `adb devices` con un único device, API 33 o superior (locale por-app sin root), lista de ABIs.
- APK: si trae código nativo (`lib/<abi>/`), alguna ABI debe coincidir con el dispositivo (una app solo ARM no se instala en un emulador x86_64).
- LLM: `LLMServerManager.ensureRunning` garantiza que el endpoint responde o aborta con mensaje claro.
- App decodificada: minSdk no mayor que el del dispositivo, una actividad con MAIN+LAUNCHER (también `activity-alias`), que no sea una Trusted Web Activity (sus pantallas son del navegador) y que exista `res/values/strings.xml`.
- Prueba de arranque: instala el APK ORIGINAL con sus permisos concedidos, lo lanza, comprueba que queda en primer plano y que no crashea, y lo desinstala. Separa "la app no corre en este dispositivo" de "la reconstrucción de ITDroid la rompió".

Cada rechazo es una `ITDroidException` con prefijo `PREFLIGHT:` y el motivo, queda en `report.json` (`error`) y el proceso termina con código 1.

# Fase 1, inventario

`StringInventory.collect` recorre `res/values/*.xml` (salvo `donottranslate.xml`) y el smali del paquete de la app. Por cada entrada guarda tipo, nombre, texto (XML interno, así se conservan `<b>` o `<xliff:g>`), origen y, si no se traduce, la razón: `translatable="false"`, diccionario de no traducibles, referencia (`@string/x`), sin letras, solo números y símbolos, texto no humano (path data, clases, fuentes) o nombre de contenedor de datos (`*_values`, `*_keys`, `*_ids`, `*_urls`...). Los literales hardcodeados pasan las mismas reglas, así que constantes como `org.x.app.STOP_ALARM` nunca llegan al LLM.

El inventario es también la memoria de traducción: un mismo texto se traduce una sola vez, y un `strings_inventory.json` previo en la carpeta de salida siembra la memoria de la siguiente corrida.

# Fase 2, construcción y exploración del origen

Se reconstruye la app intacta (si apktool no puede, la app no se puede procesar y se aborta con ese motivo). `AdbExplorer.explore`:

1. `install` (con permisos concedidos) y `cleanReset` (`pm clear`, sin root; vuelve a fijar el locale por-app y a conceder los permisos runtime).
2. `launch` (monkey), espera a primer plano aceptando el diálogo de permisos si aparece, y ventana de asentamiento: `exploration.settleSeconds` más esperar a que la estructura no cambie durante 1,5 s.
3. DFS in-place: captura el estado con `stableDump`, extrae los clickables (los campos de texto no se tocan), los toca, recursa en estados nuevos y vuelve con el botón atrás o, si no vuelve, con reinicio limpio y repetición del camino. Cada tap, cada atrás y cada reinicio queda grabado como acción.
4. Firma de estado: `resource-id` + clase + `content-desc` (dígitos plegados) + si el nodo muestra texto, ignorando el teclado. Además se guarda la estructura (`resource-id` + clase) comparable entre idiomas.
5. Opcional (`exploration.fillForms`): al llegar a una pantalla con campos de texto pide al LLM un valor por campo, lo teclea y lo graba; el replay teclea exactamente lo mismo.
6. Emite `result.json` y `exploration_script.json` (estados con su camino desde la raíz y sus entradas, y la lista de acciones), y desinstala la app.

# Fase 3, idiomas destino

Por cada idioma (`ITDroid.processLanguage`), con la app fuera del dispositivo:

1. Recursos: si la app no trae ese idioma (`XMLComparator`), `LLMTranslator.translate` agrupa las entradas del inventario, traduce los textos únicos en lotes de 25 (todos los `<string>`, ítems de `<plurals>` y de `<string-array>` juntos, no una llamada por contenedor) y escribe `temp/res/values-<lang>/strings.xml`. Los ítems que son referencias o datos se copian tal cual; los recursos que ya definía el idioma no se tocan.
2. Hardcodeados: `translateUiLabels` traduce los literales del inventario; `SmaliPatcher.patch` reescribe los `const-string` (no ASCII como `\uXXXX`) y se escribe `hardcoded_<lang>.csv`.
3. `APKToolWrapper.buildAPK` reconstruye y firma `<pkg>-<lang>-aligned-debugSigned.apk`; el smali vuelve a quedar intacto.
4. `AdbExplorer.replay`: instala, reinicio limpio, lanza, asienta, y ejecuta las acciones grabadas. Antes de cada acción comprueba estar en el estado esperado (por la firma aprendida en esta versión) y, si no, reinicia y rehace el camino. Cada pantalla capturada se guarda con el id del estado origen que reproduce. Las divergencias (elemento no encontrado, pantalla distinta, salida de la app) se anotan en `replay_report.json`, nunca abortan. Al terminar desinstala la app.
5. `LayoutGraphComparision` empareja cada estado por id reproducido (misma actividad y número de nodos similar) y compara las relaciones espaciales nodo a nodo; cada relación perdida o nueva es un IPF.

# Fase 4, cierre

Restaura el APK con nombre por defecto desde la construcción origen, reinstala el APK original en el dispositivo y escribe el `report.json` final. Si algo abortó, el código de salida es 1 y `report.json` conserva lo terminado.

# El servidor LLM

ITDroid es agnóstico al motor: solo habla un endpoint HTTP OpenAI-compatible. El `translation.autoStart` (o el 8º argumento posicional) decide cómo se provee:

- Sin espacios (ej. `qwen2.5:3b`): modo modelo Ollama. Arranca `ollama serve` si está caído, hace `ollama pull` y fija endpoint y modelo vía system properties.
- Con espacios (ej. `llama-server -m m.gguf --port 8080`): modo comando. Ejecuta el comando vía shell y espera al endpoint de `.env`.
- Vacío: pre-flight; el server debe estar arriba o aborta con mensaje claro.

El estilo de petición lo fija `translation.style`: `json` (lotes de 25 textos en un objeto JSON, para modelos de chat), `translategemma` (un texto por petición con el prompt fijo de TranslateGemma) o `template` (un texto por petición con `translation.promptTemplate`, por defecto el prompt de Hy-MT2); `auto` lo deduce del nombre del modelo. En los estilos de un texto por petición los placeholders viajan enmascarados como `<phN>` (`translation.maskPlaceholders`) y cada respuesta pasa por la comprobación de placeholders; si los pierde dos veces, el texto se queda en el idioma origen. Un GGUF del Hub servido por Ollama puede traer la plantilla de chat corrupta: comprobar `ollama show <modelo> --template` y, si hace falta, envolverlo en un Modelfile con la plantilla oficial (ver `eval/Modelfile.hy-mt2`).

Un server que ITDroid arrancó se apaga al final (shutdown hook, mata el árbol de procesos). Uno que ya estaba corriendo se reutiliza y no se toca. La salida del server va a `<output>/llm-server.log`.

# Informe web

`java -jar ITDroid.jar --report [carpeta] [--port N] [--no-browser]` arranca `ReportServer` (el `HttpServer` del JDK, solo en 127.0.0.1): sirve `webreport/` en `/`, la carpeta de resultados en `/results/` y un índice en `/api/results` que recorre las subcarpetas con `report.json` y resume cada evaluación (estado, idiomas, IPFs, fecha, dispositivo, modelo, y la telemetría del harness si hay `meta.json`). La página (`webreport/js/main.js`, sin dependencias) lista las apps y muestra la seleccionada en pestañas: resumen, idiomas e IPFs (agrupa los IPFs por pantalla, lee `result.json` de la exploración origen y de la traducida, empareja la pantalla por `sourceStateId`, dibuja sobre las capturas los nodos afectados a partir de los `bounds` del volcado de uiautomator, en el mismo orden de documento con el que ITDroid numera los nodos), hardcodeados (desde `strings_inventory.json`), rendimiento, entorno y artefactos.

# Re-análisis sin dispositivo

`java -jar ITDroid.jar --reanalyze <carpeta> [--out <carpeta>]` (`helper/Reanalysis`) vuelve a calcular la comparación de evaluaciones ya exploradas: lee el `report.json` de cada una para saber el idioma origen y los idiomas evaluados, carga los `result.json` guardados con `LayoutGraph` y ejecuta `LayoutGraphComparision` tal como lo hace el pipeline. No usa dispositivo ni LLM y no escribe nada en la carpeta de entrada; la salida (por defecto `reanalysis/<nombre de la carpeta>`) lleva un `summary.csv` con los IPFs reportados y los recalculados por evaluación e idioma, y por evaluación un `reanalysis.json` y un `ipfs.csv`. Sirve para medir sobre un lote entero el efecto de cualquier cambio en el comparador sin volver a explorar las apps.

# Pruebas

`mvn test` corre las pruebas unitarias (JUnit 5), que viven en `test/` con los mismos paquetes que `src/`. No necesitan dispositivo ni LLM.

- `model/`: relaciones entre los nodos de una pantalla (`StateRelationsTest`), reglas de la comparación sobre pantallas hechas a mano (`ComparisonRulesTest`), carga de `result.json` (`LayoutGraphTest`) y la comparación de cuatro evaluaciones reales guardadas en `test/fixtures/evaluations` (`StoredEvaluationsComparisonTest`), cuyos conteos de IPFs quedan fijados: un cambio en el comparador que los mueva tiene que ser intencionado.
- `helper/`: detección de hardcoded e inventario sobre una app escrita a mano (`test/fixtures/decoded/shop`: smali en dos dex, código fuera de la carpeta del `applicationId`, recursos y un layout), parcheo del smali y el modo de re-análisis.
- `translator/`: el traductor contra `FakeLlmServer`, un servidor local que imita el endpoint OpenAI-compatible, para probar lotes, reintentos, enmascarado de placeholders y la escritura de `values-<lang>/strings.xml`.

Las pruebas llamadas `knownGap_...` fijan un comportamiento que la auditoría de noviembre de 2026 encontró incorrecto: afirman lo que el código hace hoy y nombran la corrección pendiente; cuando se corrige, la aserción se invierte en el mismo cambio.

# Artefactos que se generan

- `temp/`: APK decodificado (`smali*/`, `res/values*/`, `AndroidManifest.xml`); se regenera en cada run.
- `<output>/report.json`: preflight, inventario (conteos), y por idioma estados, emparejamiento, replay e IPFs; además `itdroid` (versión, inicio, fin, estado), `environment` (host, dispositivo, motor de traducción, configuración) y `performance` (segundos por fase, peticiones al LLM, CPU y heap de ITDroid, carga de CPU y memoria del host muestreadas cada 2 s por `RunMetrics`).
- `<output>` es `results/<paquete>` salvo que la config fije `output=`; `results/` es la carpeta que lista el informe web.
- `<output>/strings_inventory.json`: inventario con orígenes, razones de exclusión y traducciones.
- `<output>/<pkg>-aligned-debugSigned.apk`: APK por defecto (idioma origen, intacto).
- `<output>/<pkg>-<lang>-aligned-debugSigned.apk`: APK por idioma (recursos y hardcoded traducidos).
- `<output>/{trnsResults,noTrnsResults}/<lang>/result.json`: grafo explorado o reproducido.
- `<output>/trnsResults/<origen>/exploration_script.json`: el recorrido grabado.
- `<output>/.../<lang>/replay_report.json`: acciones reproducidas, estados capturados y divergencias.
- `<output>/.../<lang>/screenshots/`: capturas. Con `debug.graphDumps=true`, además `graph.txt` y `graph.json`: volcados legibles de cada estado con todas las relaciones entre pares de nodos (varios MB por idioma; solo para depurar la comparación a mano).
- `<output>/ipfs.csv`: IPFs en CSV.
- `<output>/hardcoded_<lang>.csv`: auditoría de los textos hardcodeados traducidos.
- `<output>/hcs.txt`: textos hardcodeados detectados.
- `<output>/llm-server.log`: salida del servidor LLM (si ITDroid lo arrancó).

# Decisiones de diseño clave

- Preflight antes que nada: lo que no se puede procesar se rechaza en segundos con un motivo clasificable (fuera de alcance, entorno, límite de apktool) en vez de fallar a mitad de una exploración.
- Inventario único con origen: una sola fuente para traducir, revisar a mano, deduplicar y reutilizar; permite rastrear cada texto hasta su archivo y línea.
- Grabar y reproducir: las versiones traducidas no exploran por su cuenta, repiten el recorrido grabado, así los grafos se emparejan por construcción y el no determinismo del explorador deja de contaminar la comparación. Cada versión traducida se reconstruye, instala y evalúa de verdad.
- La app fuera del dispositivo mientras se traduce: tras cada exploración se desinstala (procesos y datos incluidos); solo vive en el dispositivo el tiempo de su exploración.
- Strategy en la traducción (`TranslationInterface`): cambiar de IBM Watson a un LLM local fue cambiar la estrategia, sin tocar el orquestador.
- LLM agnóstico al motor: HTTP OpenAI-compatible, sirve Ollama, llama.cpp o LM Studio sin cambiar código.
- Exploración sin root: locale por-app (`cmd locale set-app-locales`) y `pm clear` no requieren root, funciona en emuladores Play Store y dispositivos físicos.
- Original intacto: el APK original nunca se parchea, y el dispositivo termina con él reinstalado.

Para el historial de cambios ver REFACTOR.md. Para cómo ejecutar ver README.md.
