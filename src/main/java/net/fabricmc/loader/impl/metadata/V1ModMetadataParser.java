/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.metadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;
import net.fabricmc.loader.impl.util.version.VersionParser;

final class V1ModMetadataParser {
	/**
	 * Reads a {@code fabric.mod.json} file of schema version {@code 1}.
	 *
	 * @param logger the logger to print warnings to
	 * @param reader the json reader to read the file with
	 * @return the metadata of this file, null if the file could not be parsed
	 * @throws IOException         if there was any issue reading the file
	 */
	static LoaderModMetadata parse(JsonReader reader) throws IOException, ParseMetadataException {
		List<ParseWarning> warnings = new ArrayList<>();

		// All the values the `fabric.mod.json` may contain:
		// Required
		String id = null;
		Version version = null;

		// Optional (id provides)
		List<String> provides = new ArrayList<>();

		// Optional (mod loading)
		ModEnvironment environment = ModEnvironment.UNIVERSAL; // Default is always universal
		Map<String, List<EntrypointMetadata>> entrypoints = new HashMap<>();
		List<NestedJarEntry> jars = new ArrayList<>();
		List<V1ModMetadata.MixinEntry> mixins = new ArrayList<>();
		String classTweaker = null;

		// Optional (dependency resolution)
		List<ModDependency> dependencies = new ArrayList<>();
		// Happy little accidents
		boolean hasRequires = false;

		// Optional (metadata)
		String name = null;
		String description = null;
		List<Person> authors = new ArrayList<>();
		List<Person> contributors = new ArrayList<>();
		ContactInformation contact = null;
		List<String> license = new ArrayList<>();
		V1ModMetadata.IconEntry icon = null;

		// Optional (language adapter providers)
		Map<String, String> languageAdapters = new HashMap<>();

		// Optional (custom values)
		Map<String, CustomValue> customValues = new HashMap<>();

		while (reader.hasNext()) {
			final String key = reader.nextName();

			// Work our way from required to entirely optional
			switch (key) {
			case "schemaVersion":
				// Duplicate field, make sure it matches our current schema version
				if (reader.peek() != JsonToken.NUMBER) {
					throw new ParseMetadataException("Повторное поле \"schemaVersion\" не является числом", reader);
				}

				final int read = reader.nextInt();

				if (read != 1) {
					throw new ParseMetadataException(String.format("Повторное поле \"schemaVersion\" не совпадает с ожидаемой версией схемы 1. Указанное значение: %s", read), reader);
				}

				break;
			case "id":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Id мода должен быть непустой строкой длиной 3-64 символа.", reader);
				}

				id = reader.nextString();
				break;
			case "version":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Версия должна быть непустой строкой", reader);
				}

				try {
					version = VersionParser.parse(reader.nextString(), false);
				} catch (VersionParsingException e) {
					throw new ParseMetadataException("Не удалось разобрать версию", e);
				}

				break;
			case "provides":
				readProvides(reader, provides);
				break;
			case "environment":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Значение \"environment\" должно быть строкой", reader);
				}

				environment = readEnvironment(reader);
				break;
			case "entrypoints":
				readEntrypoints(warnings, reader, entrypoints);
				break;
			case "jars":
				readNestedJarEntries(warnings, reader, jars);
				break;
			case "mixins":
				readMixinConfigs(warnings, reader, mixins);
				break;
			case "accessWidener":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Путь к файлу Access Widener должен быть строкой", reader);
				}

				classTweaker = reader.nextString();
				break;
			case "depends":
				readDependenciesContainer(reader, ModDependency.Kind.DEPENDS, dependencies);
				break;
			case "recommends":
				readDependenciesContainer(reader, ModDependency.Kind.RECOMMENDS, dependencies);
				break;
			case "suggests":
				readDependenciesContainer(reader, ModDependency.Kind.SUGGESTS, dependencies);
				break;
			case "conflicts":
				readDependenciesContainer(reader, ModDependency.Kind.CONFLICTS, dependencies);
				break;
			case "breaks":
				readDependenciesContainer(reader, ModDependency.Kind.BREAKS, dependencies);
				break;
			case "requires":
				hasRequires = true;
				reader.skipValue();
				break;
			case "name":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Название мода должно быть строкой", reader);
				}

				name = reader.nextString();
				break;
			case "description":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Описание мода должно быть строкой", reader);
				}

				description = reader.nextString();
				break;
			case "authors":
				readPeople(warnings, reader, authors);
				break;
			case "contributors":
				readPeople(warnings, reader, contributors);
				break;
			case "contact":
				contact = readContactInfo(reader);
				break;
			case "license":
				readLicense(reader, license);
				break;
			case "icon":
				icon = readIcon(reader);
				break;
			case "languageAdapters":
				readLanguageAdapters(reader, languageAdapters);
				break;
			case "custom":
				readCustomValues(reader, customValues);
				break;
			default:
				if (!ModMetadataParser.IGNORED_KEYS.contains(key)) {
					warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), key, "Неподдерживаемое поле верхнего уровня"));
				}

				reader.skipValue();
				break;
			}
		}

		// Validate all required fields are resolved
		if (id == null) {
			throw new ParseMetadataException.MissingField("id");
		}

		if (version == null) {
			throw new ParseMetadataException.MissingField("version");
		}

		ModMetadataParser.logWarningMessages(id, warnings);

		return new V1ModMetadata(id, version, provides,
				environment, entrypoints, jars, mixins, classTweaker,
				dependencies, hasRequires,
				name, description, authors, contributors, contact, license, icon, languageAdapters, customValues);
	}

	private static void readProvides(JsonReader reader, List<String> provides) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseMetadataException("Значение \"provides\" должно быть массивом");
		}

		reader.beginArray();

		while (reader.hasNext()) {
			if (reader.peek() != JsonToken.STRING) {
				throw new ParseMetadataException("Id в \"provides\" должен быть строкой", reader);
			}

			provides.add(reader.nextString());
		}

		reader.endArray();
	}

	private static ModEnvironment readEnvironment(JsonReader reader) throws ParseMetadataException, IOException {
		final String environment = reader.nextString().toLowerCase(Locale.ROOT);

		if (environment.isEmpty() || environment.equals("*")) {
			return ModEnvironment.UNIVERSAL;
		} else if (environment.equals("client")) {
			return ModEnvironment.CLIENT;
		} else if (environment.equals("server")) {
			return ModEnvironment.SERVER;
		} else {
			throw new ParseMetadataException("Недопустимое значение \"environment\": " + environment + "!", reader);
		}
	}

	private static void readEntrypoints(List<ParseWarning> warnings, JsonReader reader, Map<String, List<EntrypointMetadata>> entrypoints) throws IOException, ParseMetadataException {
		// Entrypoints must be an object
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Значение \"entrypoints\" должно быть объектом", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			final String key = reader.nextName();

			List<EntrypointMetadata> metadata = new ArrayList<>();

			if (reader.peek() != JsonToken.BEGIN_ARRAY) {
				throw new ParseMetadataException("Список entrypoint'ов должен быть массивом!", reader);
			}

			reader.beginArray();

			while (reader.hasNext()) {
				String adapter = "default";
				String value = null;

				// Entrypoints may be specified directly as a string or as an object to allow specification of the language adapter to use.
				switch (reader.peek()) {
				case STRING:
					value = reader.nextString();
					break;
				case BEGIN_OBJECT:
					reader.beginObject();

					while (reader.hasNext()) {
						final String entryKey = reader.nextName();
						switch (entryKey) {
						case "adapter":
							adapter = reader.nextString();
							break;
						case "value":
							value = reader.nextString();
							break;
						default:
							warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), entryKey, "Недопустимое поле в метаданных entrypoint"));
							reader.skipValue();
							break;
						}
					}

					reader.endObject();
					break;
				default:
					throw new ParseMetadataException("Entrypoint должен быть строкой либо объектом с полем \"value\"", reader);
				}

				if (value == null) {
					throw new ParseMetadataException.MissingField("value у entrypoint'а");
				}

				metadata.add(new V1ModMetadata.EntrypointMetadataImpl(adapter, value));
			}

			reader.endArray();

			// Empty arrays are acceptable, do not check if the List of metadata is empty
			entrypoints.put(key, metadata);
		}

		reader.endObject();
	}

	private static void readNestedJarEntries(List<ParseWarning> warnings, JsonReader reader, List<NestedJarEntry> jars) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseMetadataException("Значение \"jars\" должно быть массивом", reader);
		}

		reader.beginArray();

		while (reader.hasNext()) {
			if (reader.peek() != JsonToken.BEGIN_OBJECT) {
				throw new ParseMetadataException("Недопустимый тип элемента в \"jars\"!", reader);
			}

			reader.beginObject();
			String file = null;

			while (reader.hasNext()) {
				final String key = reader.nextName();

				if (key.equals("file")) {
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("Поле \"file\" в элементе \"jars\" должно быть строкой", reader);
					}

					file = reader.nextString();
				} else {
					warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), key, "Неподдерживаемое поле в элементе \"jars\""));
					reader.skipValue();
				}
			}

			reader.endObject();

			if (file == null) {
				throw new ParseMetadataException("Отсутствует обязательное поле 'file' в элементе \"jars\"!", reader);
			}

			jars.add(new V1ModMetadata.JarEntry(file));
		}

		reader.endArray();
	}

	private static void readMixinConfigs(List<ParseWarning> warnings, JsonReader reader, List<V1ModMetadata.MixinEntry> mixins) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseMetadataException("Значение \"mixins\" должно быть массивом", reader);
		}

		reader.beginArray();

		while (reader.hasNext()) {
			switch (reader.peek()) {
			case STRING:
				// All mixin configs specified via string are assumed to be universal
				mixins.add(new V1ModMetadata.MixinEntry(reader.nextString(), ModEnvironment.UNIVERSAL));
				break;
			case BEGIN_OBJECT:
				reader.beginObject();

				String config = null;
				ModEnvironment environment = null;

				while (reader.hasNext()) {
					final String key = reader.nextName();

					switch (key) {
					// Environment is optional
					case "environment":
						environment = V1ModMetadataParser.readEnvironment(reader);
						break;
					case "config":
						if (reader.peek() != JsonToken.STRING) {
							throw new ParseMetadataException("Значение \"config\" должно быть строкой", reader);
						}

						config = reader.nextString();
						break;
					default:
						warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), key, "Неподдерживаемое поле в элементе \"mixins\""));
						reader.skipValue();
					}
				}

				reader.endObject();

				if (environment == null) {
					environment = ModEnvironment.UNIVERSAL; // Default to universal
				}

				if (config == null) {
					throw new ParseMetadataException.MissingField("'config' в элементе \"mixins\"");
				}

				mixins.add(new V1ModMetadata.MixinEntry(config, environment));
				break;
			default:
				warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), "Недопустимый тип элемента \"mixins\""));
				reader.skipValue();
				break;
			}
		}

		reader.endArray();
	}

	private static void readDependenciesContainer(JsonReader reader, ModDependency.Kind kind, List<ModDependency> out) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Контейнер зависимостей должен быть объектом!", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			final String modId = reader.nextName();
			final List<String> matcherStringList = new ArrayList<>();

			switch (reader.peek()) {
			case STRING:
				matcherStringList.add(reader.nextString());
				break;
			case BEGIN_ARRAY:
				reader.beginArray();

				while (reader.hasNext()) {
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("Массив диапазонов версий зависимости должен содержать только строки", reader);
					}

					matcherStringList.add(reader.nextString());
				}

				reader.endArray();
				break;
			default:
				throw new ParseMetadataException("Диапазон версий зависимости должен быть строкой или массивом строк!", reader);
			}

			try {
				out.add(new ModDependencyImpl(kind, modId, matcherStringList));
			} catch (VersionParsingException e) {
				throw new ParseMetadataException(e);
			}
		}

		reader.endObject();
	}

	private static void readPeople(List<ParseWarning> warnings, JsonReader reader, List<Person> people) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseMetadataException("Список людей (authors/contributors) должен быть массивом", reader);
		}

		reader.beginArray();

		while (reader.hasNext()) {
			switch (reader.peek()) {
			case STRING:
				// Just a name
				people.add(new SimplePerson(reader.nextString()));
				break;
			case BEGIN_OBJECT:
				// Map-backed impl
				reader.beginObject();
				// Name is required
				String personName = null;
				ContactInformation contactInformation = null;

				while (reader.hasNext()) {
					final String key = reader.nextName();

					switch (key) {
					case "name":
						if (reader.peek() != JsonToken.STRING) {
							throw new ParseMetadataException("Имя человека в списке authors/contributors должно быть строкой", reader);
						}

						personName = reader.nextString();
						break;
						// Effectively optional
					case "contact":
						contactInformation = V1ModMetadataParser.readContactInfo(reader);
						break;
					default:
						// Ignore unsupported keys
						warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), key, "Неподдерживаемое поле в записи человека"));
						reader.skipValue();
					}
				}

				reader.endObject();

				if (personName == null) {
					throw new ParseMetadataException.MissingField("'name' в записи человека (authors/contributors)");
				}

				if (contactInformation == null) {
					contactInformation = ContactInformation.EMPTY; // Empty if not specified
				}

				people.add(new ContactInfoBackedPerson(personName, contactInformation));
				break;
			default:
				throw new ParseMetadataException("Запись человека должна быть объектом или строкой!", reader);
			}
		}

		reader.endArray();
	}

	private static ContactInformation readContactInfo(JsonReader reader) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Значение \"contact\" должно быть объектом", reader);
		}

		reader.beginObject();

		final Map<String, String> map = new HashMap<>();

		while (reader.hasNext()) {
			final String key = reader.nextName();

			if (reader.peek() != JsonToken.STRING) {
				throw new ParseMetadataException("Значения в \"contact\" должны быть строками", reader);
			}

			map.put(key, reader.nextString());
		}

		reader.endObject();

		// Map is wrapped as unmodifiable in the contact info impl
		return new ContactInformationImpl(map);
	}

	private static void readLicense(JsonReader reader, List<String> license) throws IOException, ParseMetadataException {
		switch (reader.peek()) {
		case STRING:
			license.add(reader.nextString());
			break;
		case BEGIN_ARRAY:
			reader.beginArray();

			while (reader.hasNext()) {
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Список лицензий должен содержать только строки", reader);
				}

				license.add(reader.nextString());
			}

			reader.endArray();
			break;
		default:
			throw new ParseMetadataException("Значение \"license\" должно быть строкой или массивом строк!", reader);
		}
	}

	private static V1ModMetadata.IconEntry readIcon(JsonReader reader) throws IOException, ParseMetadataException {
		switch (reader.peek()) {
		case STRING:
			return new V1ModMetadata.Single(reader.nextString());
		case BEGIN_OBJECT:
			reader.beginObject();

			final SortedMap<Integer, String> iconMap = new TreeMap<>(Comparator.naturalOrder());

			while (reader.hasNext()) {
				String key = reader.nextName();

				int size;

				try {
					size = Integer.parseInt(key);
				} catch (NumberFormatException e) {
					throw new ParseMetadataException("Не удалось разобрать размер иконки '" + key + "'!", e);
				}

				if (size < 1) {
					throw new ParseMetadataException("Размер иконки должен быть положительным!", reader);
				}

				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Путь к иконке должен быть строкой", reader);
				}

				iconMap.put(size, reader.nextString());
			}

			reader.endObject();

			if (iconMap.isEmpty()) {
				throw new ParseMetadataException("Объект \"icon\" не должен быть пустым!", reader);
			}

			return new V1ModMetadata.MapEntry(iconMap);
		default:
			throw new ParseMetadataException("Значение \"icon\" должно быть объектом или строкой!", reader);
		}
	}

	private static void readLanguageAdapters(JsonReader reader, Map<String, String> languageAdapters) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Значение \"languageAdapters\" должно быть объектом", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			final String adapter = reader.nextName();

			if (reader.peek() != JsonToken.STRING) {
				throw new ParseMetadataException("Значение записи language adapter должно быть строкой", reader);
			}

			languageAdapters.put(adapter, reader.nextString());
		}

		reader.endObject();
	}

	private static void readCustomValues(JsonReader reader, Map<String, CustomValue> customValues) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Значение \"custom\" должно быть объектом!", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			customValues.put(reader.nextName(), CustomValueImpl.readCustomValue(reader));
		}

		reader.endObject();
	}

	private V1ModMetadataParser() {
	}
}
