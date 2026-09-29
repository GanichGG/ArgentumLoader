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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

public final class MetadataVerifier {
	private static final Pattern MOD_ID_PATTERN = Pattern.compile("[a-z][a-z0-9-_]{1,63}");

	public static ModCandidateImpl verifyIndev(ModCandidateImpl mod, boolean isDevelopment) {
		if (isDevelopment) {
			try {
				MetadataVerifier.verify(mod.getMetadata(), isDevelopment);
			} catch (ParseMetadataException e) {
				e.setModPaths(mod.getLocalPath(), Collections.emptyList());
				throw new RuntimeException("Недопустимые метаданные мода", e);
			}
		}

		return mod;
	}

	static void verify(LoaderModMetadata metadata, boolean isDevelopment) throws ParseMetadataException {
		checkModId(metadata.getId(), "id мода");

		for (String providesDecl : metadata.getProvides()) {
			checkModId(providesDecl, "запись \"provides\"");
		}

		// TODO: verify mod id and version decls in deps

		if (isDevelopment) {
			if (metadata.getSchemaVersion() < ModMetadataParser.LATEST_VERSION) {
				Log.warn(LogCategory.METADATA, "Mod %s uses an outdated schema version: %d < %d", metadata.getId(), metadata.getSchemaVersion(), ModMetadataParser.LATEST_VERSION);
			}
		}

		if (!(metadata.getVersion() instanceof SemanticVersion)) {
			String version = metadata.getVersion().getFriendlyString();
			VersionParsingException exc;

			try {
				SemanticVersion.parse(version);
				exc = null;
			} catch (VersionParsingException e) {
				exc = e;
			}

			if (exc != null) {
				Log.warn(LogCategory.METADATA, "Mod %s uses the version %s which isn't compatible with Loader's extended semantic version format (%s), SemVer is recommended for reliably evaluating dependencies and prioritizing newer version",
						metadata.getId(), version, exc.getMessage());
			}

			metadata.emitFormatWarnings();
		}
	}

	private static void checkModId(String id, String name) throws ParseMetadataException {
		if (MOD_ID_PATTERN.matcher(id).matches()) return;

		List<String> errorList = new ArrayList<>();

		// A more useful error list for MOD_ID_PATTERN
		if (id.isEmpty()) {
			errorList.add("пуст!");
		} else {
			if (id.length() == 1) {
				errorList.add("состоит из одного символа! (должно быть не менее 2 символов)!");
			} else if (id.length() > 64) {
				errorList.add("содержит более 64 символов!");
			}

			char first = id.charAt(0);

			if (first < 'a' || first > 'z') {
				errorList.add("начинается с недопустимого символа '" + first + "' (должна быть строчная латинская буква a-z — заглавные буквы в id не допускаются)");
			}

			Set<Character> invalidChars = null;

			for (int i = 1; i < id.length(); i++) {
				char c = id.charAt(i);

				if (c == '-' || c == '_' || ('0' <= c && c <= '9') || ('a' <= c && c <= 'z')) {
					continue;
				}

				if (invalidChars == null) {
					invalidChars = new HashSet<>();
				}

				invalidChars.add(c);
			}

			if (invalidChars != null) {
				StringBuilder error = new StringBuilder("содержит недопустимые символы: ");
				error.append(invalidChars.stream().map(value -> "'" + value + "'").collect(Collectors.joining(", ")));
				errorList.add(error.append("!").toString());
			}
		}

		assert !errorList.isEmpty();

		StringWriter sw = new StringWriter();

		try (PrintWriter pw = new PrintWriter(sw)) {
			pw.printf("Недопустимый %s \"%s\":", name, id);

			if (errorList.size() == 1) {
				pw.printf(" Он %s", errorList.get(0));
			} else {
				for (String error : errorList) {
					pw.printf("\n\t- Он %s", error);
				}
			}
		}

		throw new ParseMetadataException(sw.toString());
	}
}
