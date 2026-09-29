# Аудит: места выброса mod-related исключений

Составлено для п.4 плана (см. техзадание). Задача: перед переписыванием в
человекочитаемый вид (п.5) — полный список мест, где loader бросает
исключения из-за проблем с модами. Всего **120** точек выброса в пакетах
`impl.discovery` и `impl.metadata` (grep по `throw new
(ModResolutionException|ParseMetadataException|FormattedException|
RuntimeException|IllegalStateException|IOException|...)`).

Полный построчный дамп (grep-вывод, файл:строка:текст) —
см. `docs/readable-errors-raw.txt` рядом с этим файлом (генерируется той же
командой, что и ниже, чтобы не расходиться).

## 1. Парсинг `fabric.mod.json` (самая многочисленная категория — 78 мест)

**`src/main/java/net/fabricmc/loader/impl/metadata/V1ModMetadataParser.java` — 45 мест**
Схема V1. Валидация буквально каждого поля манифеста: `schemaVersion`,
`id` (длина 3-64), `version` (SemVer-парсинг), `environment`, `entrypoints`
(структура/тип/обязательный `value`), `jars` (nested jar entries), `mixins`,
`depends`/`recommends`/`suggests`/`breaks`/`conflicts` (тип
контейнера + формат диапазона версий), `authors`/`contributors` (объект
person), `contact`, `license`, `icon` (размеры), `languageAdapters`,
`custom`. Все — `ParseMetadataException` (часть — подкласс `MissingField`).
Сообщения на английском, технические ("must be a string", "Duplicate
... field"), без указания какого мода/файла — путь мода добавляется выше по
стеку в `ModDiscoverer.ModScanTask`.

**`src/main/java/net/fabricmc/loader/impl/metadata/V0ModMetadataParser.java` — 29 мест**
То же самое для устаревшей схемы V0 (обратная совместимость со старыми
модами). Дублирует категории V1 с поправкой на другую структуру полей.

**`src/main/java/net/fabricmc/loader/impl/metadata/ModMetadataParser.java` — 4 места**
Точка входа парсинга: не-объект в корне JSON, `schemaVersion` не число,
неизвестная/неподдерживаемая версия схемы — отсюда парсинг делегируется в V0
или V1.

**`src/main/java/net/fabricmc/loader/impl/metadata/DependencyOverrides.java` — 10 мест**
Парсинг `fabric_loader_dependencies.json` (файл переопределения зависимостей,
кладётся пользователем/лаунчером поверх манифеста мода) — та же природа
ошибок (не тот тип, не тот ключ, неподдерживаемая версия формата).

**`VersionOverrides.java` — 2, `MetadataVerifier.java` — 2, `CustomValueImpl.java` — 1,
`BuiltinModMetadata.java` — 1** — по мелочи, той же природы (override-файлы,
пост-валидация уже распарсенных метаданных).

## 2. Discovery — поиск и чтение jar-файлов модов (10 мест)

**`ModDiscoverer.java`**
- :123 `FormattedException("Invalid game version", ...)` — builtin-мод
  (`minecraft`) в dev-окружении без корректной SemVer-версии.
- :174 `FormattedException("Mod discovery took too long!", ...)` — таймаут
  параллельного сканирования папки `mods`.
- :178 `FormattedException("Mod discovery interrupted!", e)`.
- :280 `RuntimeException("Error analyzing nested jar %s from %s: %s", ...)` —
  ошибка чтения/парсинга **вложенного** (jar-in-jar) мода.
- :300 `RuntimeException("Error analyzing %s: %s", ...)` — ошибка чтения
  верхнеуровневого jar-файла (битый zip, IO-ошибка, некорректный манифест).

**`DirectoryModCandidateFinder.java` (3), `ClasspathModCandidateFinder.java` (1),
`ArgumentModCandidateFinder.java` (1)** — ошибки поиска кандидатов: не
существует/не является директорией папка `mods`, IO при обходе classpath или
аргументов `--mod`.

## 3. Resolution — конфликты зависимостей и версий (5 мест, все в `ModResolver.java`)

- :104 `ModResolutionException("Mods share ID with builtin mod %s: %s", ...)`
  — мод пытается использовать зарезервированный id (`java`/`minecraft`/
  `fabricloader`/...).
- :125 `ModResolutionException("Solving failed", e)` — SAT-солвер (sat4j)
  упал по таймауту/противоречию на уровне самого алгоритма (не должно
  происходить в норме).
- :149 — **главная точка**: `ModResolutionException("Some of your mods are
  incompatible with the game or each other!%s",
  ResultAnalyzer.gatherErrors(...))`. Сюда стекаются все отсутствующие
  зависимости, конфликты версий, breaks/conflicts между модами. Текст уже
  собирается `ResultAnalyzer` (человекочитаемее прочих, но всё ещё
  технический граф "Fix: add X, remove Y").
- :224, :228 `ModResolutionException("duplicate mod %s", ...)` — внутренняя
  ошибка солвера (два мода с одинаковым id выбраны одновременно).

## 4. Идентификация "битого jar" (6 мест, `ModCandidateImpl.java`)

Ошибки уровня nested-jar навигации: не найден вложенный мод в родительском
jar (`IOException "can't find nested mod..."`), некорректное состояние путей
(`IllegalStateException`/`UnsupportedOperationException` — по сути баги
дискавери, а не пользовательские ошибки, но всё равно долетают до GUI при
крахе).

## 5. Runtime-ремаппинг dev-модов (5 мест, `RuntimeModRemapper.java`)

Относится только к dev-окружению (запуск мода в IDE): ошибки чтения
class-tweaker конфигурации, построения classpath для ремаппинга, открытия
jar через NIO, финальная обёртка `FormattedException("Failed to remap
mods!", t)`.

## Вывод для п.5

Приоритет переписывания (по частоте реального столкновения игрока/моддера):

1. **`ResultAnalyzer` (ModResolver.java:149)** — самое частое, что видит
   конечный пользователь при несовместимости модов. Наибольший ROI.
2. **V1/V0 парсеры `fabric.mod.json`** — 74 места, но однообразные (поле →
   тип/формат) — можно закрыть одним универсальным форматтером
   "`<mod-id>`: поле `<field>` в `fabric.mod.json` должно быть `<type>`,
   получено `<actual>`" вместо переписывания каждого сообщения вручную.
3. **`ModDiscoverer`** (битые/зависшие jar) — требует контекста "какой файл",
   сейчас есть только путь без объяснения, что с ним не так пользователю.
4. Остальное (`ModCandidateImpl`, `RuntimeModRemapper`) — low-priority,
   в основном dev-time/internal-consistency ошибки, редко видны конечному
   пользователю.
