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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;
import net.fabricmc.loader.impl.util.version.VersionParser;

final class V0ModMetadataParser {
	private static final Pattern WEBSITE_PATTERN = Pattern.compile("\\((.+)\\)");
	private static final Pattern EMAIL_PATTERN = Pattern.compile("<(.+)>");

	public static LoaderModMetadata parse(JsonReader reader) throws IOException, ParseMetadataException {
		List<ParseWarning> warnings = new ArrayList<>();

		// All the values the `fabric.mod.json` may contain:
		// Required
		String id = null;
		Version version = null;

		// Optional (mod loading)
		List<ModDependency> dependencies = new ArrayList<>();
		V0ModMetadata.Mixins mixins = null;
		ModEnvironment environment = ModEnvironment.UNIVERSAL; // Default is always universal
		String initializer = null;
		List<String> initializers = new ArrayList<>();

		String name = null;
		String description = null;
		List<Person> authors = new ArrayList<>();
		List<Person> contributors = new ArrayList<>();
		ContactInformation links = null;
		String license = null;

		while (reader.hasNext()) {
			final String key = reader.nextName();

			switch (key) {
			case "schemaVersion":
				// Duplicate field, make sure it matches our current schema version
				if (reader.peek() != JsonToken.NUMBER) {
					throw new ParseMetadataException("Повторное поле \"schemaVersion\" не является числом", reader);
				}

				final int read = reader.nextInt();

				if (read != 0) {
					throw new ParseMetadataException(String.format("Повторное поле \"schemaVersion\" не совпадает с ожидаемой версией схемы 0. Указанное значение: %s", read), reader);
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

				final String rawVersion = reader.nextString();

				try {
					version = VersionParser.parse(rawVersion, false);
				} catch (VersionParsingException e) {
					throw new ParseMetadataException(String.format("Не удалось разобрать версию: %s", rawVersion), e);
				}

				break;
			case "requires":
				readDependenciesContainer(reader, ModDependency.Kind.DEPENDS, dependencies, "requires");
				break;
			case "conflicts":
				readDependenciesContainer(reader, ModDependency.Kind.BREAKS, dependencies, "conflicts");
				break;
			case "mixins":
				mixins = readMixins(warnings, reader);
				break;
			case "side":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Значение \"side\" должно быть строкой", reader);
				}

				final String rawEnvironment = reader.nextString();

				switch (rawEnvironment) {
				case "universal":
					environment = ModEnvironment.UNIVERSAL;
					break;
				case "client":
					environment = ModEnvironment.CLIENT;
					break;
				case "server":
					environment = ModEnvironment.SERVER;
					break;
				default:
					warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), rawEnvironment, "Недопустимое значение \"side\""));
				}

				break;
			case "initializer":
				// `initializer` and `initializers` cannot be used at the same time
				if (!initializers.isEmpty()) {
					throw new ParseMetadataException("initializer и initializers не должны быть заданы одновременно! (id мода '" + id + "')");
				}

				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Значение \"initializer\" должно быть непустой строкой", reader);
				}

				initializer = reader.nextString();
				break;
			case "initializers":
				// `initializer` and `initializers` cannot be used at the same time
				if (initializer != null) {
					throw new ParseMetadataException("initializer и initializers не должны быть заданы одновременно! (id мода '" + id + "')");
				}

				if (reader.peek() != JsonToken.BEGIN_ARRAY) {
					throw new ParseMetadataException("Значение \"initializers\" должно быть списком", reader);
				}

				reader.beginArray();

				while (reader.hasNext()) {
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("Элемент списка \"initializers\" должен быть строкой", reader);
					}

					initializers.add(reader.nextString());
				}

				reader.endArray();

				break;
			case "name":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Значение \"name\" должно быть строкой", reader);
				}

				name = reader.nextString();
				break;
			case "description":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Описание мода должно быть строкой", reader);
				}

				description = reader.nextString();
				break;
			case "recommends":
				readDependenciesContainer(reader, ModDependency.Kind.SUGGESTS, dependencies, "recommends");
				break;
			case "authors":
				readPeople(warnings, reader, authors);
				break;
			case "contributors":
				readPeople(warnings, reader, contributors);
				break;
			case "links":
				links = readLinks(warnings, reader);
				break;
			case "license":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Название лицензии должно быть строкой", reader);
				}

				license = reader.nextString();
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

		// Optional stuff
		if (links == null) {
			links = ContactInformation.EMPTY;
		}

		return new V0ModMetadata(id, version, dependencies, mixins, environment, initializer, initializers, name, description, authors, contributors, links, license);
	}

	private static ContactInformation readLinks(List<ParseWarning> warnings, JsonReader reader) throws IOException, ParseMetadataException {
		final Map<String, String> contactInfo = new HashMap<>();

		switch (reader.peek()) {
		case STRING:
			contactInfo.put("homepage", reader.nextString());
			break;
		case BEGIN_OBJECT:
			reader.beginObject();

			while (reader.hasNext()) {
				final String key = reader.nextName();

				switch (key) {
				case "homepage":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("Значение \"homepage\" должно быть строкой", reader);
					}

					contactInfo.put("homepage", reader.nextString());
					break;
				case "issues":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("Значение \"issues\" должно быть строкой", reader);
					}

					contactInfo.put("issues", reader.nextString());
					break;
				case "sources":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("Значение \"sources\" должно быть строкой", reader);
					}

					contactInfo.put("sources", reader.nextString());
					break;
				default:
					warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), key, "Неподдерживаемое поле в \"links\""));
					reader.skipValue();
				}
			}

			reader.endObject();
			break;
		default:
			throw new ParseMetadataException("Значение \"links\" должно быть объектом или строкой", reader);
		}

		return new ContactInformationImpl(contactInfo);
	}

	private static V0ModMetadata.Mixins readMixins(List<ParseWarning> warnings, JsonReader reader) throws IOException, ParseMetadataException {
		final List<String> client = new ArrayList<>();
		final List<String> common = new ArrayList<>();
		final List<String> server = new ArrayList<>();

		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Значение \"mixins\" должно быть объектом.", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			final String environment = reader.nextName();

			switch (environment) {
			case "client":
				client.addAll(readStringArray(reader, "client"));
				break;
			case "common":
				common.addAll(readStringArray(reader, "common"));
				break;
			case "server":
				server.addAll(readStringArray(reader, "server"));
				break;
			default:
				warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), environment, "Недопустимый тип окружения"));
				reader.skipValue();
			}
		}

		reader.endObject();
		return new V0ModMetadata.Mixins(client, common, server);
	}

	private static List<String> readStringArray(JsonReader reader, String key) throws IOException, ParseMetadataException {
		switch (reader.peek()) {
		case NULL:
			reader.nextNull();
			return Collections.emptyList();
		case STRING:
			return Collections.singletonList(reader.nextString());
		case BEGIN_ARRAY:
			reader.beginArray();
			final List<String> list = new ArrayList<>();

			while (reader.hasNext()) {
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException(String.format("Элементы \"%s\" должны быть массивом строк", key), reader);
				}

				list.add(reader.nextString());
			}

			reader.endArray();
			return list;
		default:
			throw new ParseMetadataException(String.format("Значение \"%s\" должно быть строкой или массивом строк", key), reader);
		}
	}

	private static void readDependenciesContainer(JsonReader reader, ModDependency.Kind kind, List<ModDependency> dependencies, String name) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException(String.format("Значение \"%s\" должно быть объектом с зависимостями.", name), reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			final String modId = reader.nextName();
			final List<String> versionMatchers = new ArrayList<>();

			switch (reader.peek()) {
			case STRING:
				versionMatchers.add(reader.nextString());
				break;
			case BEGIN_ARRAY:
				reader.beginArray();

				while (reader.hasNext()) {
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("Список требований к версии должен состоять из строк", reader);
					}

					versionMatchers.add(reader.nextString());
				}

				reader.endArray();
				break;
			default:
				throw new ParseMetadataException("Значение версии должно быть строкой или массивом", reader);
			}

			try {
				dependencies.add(new ModDependencyImpl(kind, modId, versionMatchers));
			} catch (VersionParsingException e) {
				throw new ParseMetadataException(e);
			}
		}

		reader.endObject();
	}

	private static void readPeople(List<ParseWarning> warnings, JsonReader reader, List<Person> people) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseMetadataException("Список людей должен быть массивом", reader);
		}

		reader.beginArray();

		while (reader.hasNext()) {
			people.add(readPerson(warnings, reader));
		}

		reader.endArray();
	}

	private static Person readPerson(List<ParseWarning> warnings, JsonReader reader) throws IOException, ParseMetadataException {
		final HashMap<String, String> contactMap = new HashMap<>();
		String name = "";

		switch (reader.peek()) {
		case STRING:
			final String person = reader.nextString();
			String[] parts = person.split(" ");

			Matcher websiteMatcher = V0ModMetadataParser.WEBSITE_PATTERN.matcher(parts[parts.length - 1]);

			if (websiteMatcher.matches()) {
				contactMap.put("website", websiteMatcher.group(1));
				parts = Arrays.copyOf(parts, parts.length - 1);
			}

			Matcher emailMatcher = V0ModMetadataParser.EMAIL_PATTERN.matcher(parts[parts.length - 1]);

			if (emailMatcher.matches()) {
				contactMap.put("email", emailMatcher.group(1));
				parts = Arrays.copyOf(parts, parts.length - 1);
			}

			name = String.join(" ", parts);

			return new ContactInfoBackedPerson(name, new ContactInformationImpl(contactMap));
		case BEGIN_OBJECT:
			reader.beginObject();

			while (reader.hasNext()) {
				final String key = reader.nextName();

				switch (key) {
				case "name":
					if (reader.peek() != JsonToken.STRING) {
						break;
					}

					name = reader.nextString();
					break;
				case "email":
					if (reader.peek() != JsonToken.STRING) {
						break;
					}

					contactMap.put("email", reader.nextString());
					break;
				case "website":
					if (reader.peek() != JsonToken.STRING) {
						break;
					}

					contactMap.put("website", reader.nextString());
					break;
				default:
					warnings.add(new ParseWarning(reader.getLineNumber(), reader.getColumn(), key, "Неподдерживаемое поле контактной информации"));
					reader.skipValue();
				}
			}

			reader.endObject();
			return new ContactInfoBackedPerson(name, new ContactInformationImpl(contactMap));
		default:
			throw new ParseMetadataException("Запись человека должна быть строкой или объектом", reader);
		}
	}
}
