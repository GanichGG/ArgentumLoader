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

package net.fabricmc.installer.anchor;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.swing.JOptionPane;

/**
 * Одноразовый установщик ArgentumLoader ("якорь").
 *
 * <p>Не запускает игру и ничего сам не скачивает. Запускается вручную из папки клиента
 * (той же, куда указывает рабочая директория самого Minecraft — .minecraft или папка
 * инстанса в MultiMC/Prism), кладёт туда собственный jar лоадера и пишет один
 * version-профиль. Все сторонние библиотеки (Mixin, ASM) после этого штатно скачивает
 * сам лаунчер Minecraft при первом запуске этого профиля — так же, как он скачивает
 * библиотеки для ванильной игры.
 */
public final class AnchorMain {
	private static final String LIBRARIES_RESOURCE = "/anchor-libraries.properties";
	private static final String LOADER_JAR_RESOURCE = "/argentumloader.jar";

	// Обычный запуск (двойной клик) — окно ввода версии и окно с результатом.
	// Если передан аргумент командной строки или окна недоступны (headless), используется он
	// вместо диалога, а результат печатается в консоль — этим путём проходят автотесты.
	private static final boolean HEADLESS = GraphicsEnvironment.isHeadless();

	public static void main(String[] args) {
		String presetVersion = args.length > 0 ? args[0] : null;

		try {
			Path profilePath = install(presetVersion);
			String message = "ArgentumLoader установлен!\n\n"
					+ "Профиль записан в:\n" + profilePath + "\n\n"
					+ "Теперь выберите профиль ArgentumLoader в лаунчере Minecraft и нажмите «Играть» —\n"
					+ "недостающие библиотеки лаунчер скачает сам.";

			if (HEADLESS) {
				System.out.println(message);
			} else {
				JOptionPane.showMessageDialog(null, message, "ArgentumLoader", JOptionPane.INFORMATION_MESSAGE);
			}
		} catch (Exception e) {
			e.printStackTrace();
			String message = "Не удалось установить ArgentumLoader:\n" + e;

			if (!HEADLESS) {
				JOptionPane.showMessageDialog(null, message, "ArgentumLoader", JOptionPane.ERROR_MESSAGE);
			}

			System.exit(1);
		}
	}

	private static Path install(String presetVersion) throws IOException {
		Path gameDir = Paths.get("").toAbsolutePath();

		String mcVersion = presetVersion;

		if (mcVersion == null) {
			if (HEADLESS) {
				throw new IOException("нет окна для ввода версии — укажите версию Minecraft первым аргументом командной строки");
			}

			mcVersion = JOptionPane.showInputDialog(null,
					"В какую папку кладём:\n" + gameDir + "\n\n"
							+ "Версия Minecraft, для которой ставим ArgentumLoader (например 1.21.1):",
					"ArgentumLoader — установка", JOptionPane.QUESTION_MESSAGE);
		}

		if (mcVersion == null || mcVersion.trim().isEmpty()) {
			throw new IOException("версия Minecraft не указана, установка отменена");
		}

		mcVersion = mcVersion.trim();

		Properties props = loadLibraryList();

		String loaderGroup = require(props, "loader.group");
		String loaderArtifact = require(props, "loader.artifact");
		String loaderVersion = require(props, "loader.version");
		String mainClass = require(props, "mainClass");

		String ownCoordinate = loaderGroup + ":" + loaderArtifact + ":" + loaderVersion;
		installOwnJar(gameDir, loaderGroup, loaderArtifact, loaderVersion);

		List<String> thirdPartyLibraries = readThirdPartyLibraries(props);

		String profileId = "argentumloader-" + mcVersion;
		Path versionDir = gameDir.resolve("versions").resolve(profileId);
		Files.createDirectories(versionDir);

		Path profileJson = versionDir.resolve(profileId + ".json");
		String json = buildProfileJson(profileId, mcVersion, mainClass, ownCoordinate, thirdPartyLibraries);
		Files.write(profileJson, json.getBytes(StandardCharsets.UTF_8));

		return profileJson;
	}

	private static Properties loadLibraryList() throws IOException {
		try (InputStream is = AnchorMain.class.getResourceAsStream(LIBRARIES_RESOURCE)) {
			if (is == null) {
				throw new IOException("не найден " + LIBRARIES_RESOURCE + " внутри якоря — сборка повреждена");
			}

			Properties props = new Properties();
			props.load(is);
			return props;
		}
	}

	private static String require(Properties props, String key) throws IOException {
		String value = props.getProperty(key);

		if (value == null || value.isEmpty()) {
			throw new IOException("отсутствует обязательный ключ '" + key + "' в " + LIBRARIES_RESOURCE);
		}

		return value;
	}

	private static List<String> readThirdPartyLibraries(Properties props) throws IOException {
		int count = Integer.parseInt(require(props, "count"));
		List<String> ret = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			String name = require(props, "lib." + i + ".name");
			String url = require(props, "lib." + i + ".url");
			ret.add(libraryJson(name, url));
		}

		return ret;
	}

	private static void installOwnJar(Path gameDir, String group, String artifact, String version) throws IOException {
		Path targetJar = gameDir.resolve("libraries").resolve(mavenPath(group, artifact, version));
		Files.createDirectories(targetJar.getParent());

		try (InputStream is = AnchorMain.class.getResourceAsStream(LOADER_JAR_RESOURCE)) {
			if (is == null) {
				throw new IOException("не найден " + LOADER_JAR_RESOURCE + " внутри якоря — сборка повреждена");
			}

			try (OutputStream os = Files.newOutputStream(targetJar)) {
				byte[] buf = new byte[8192];
				int read;

				while ((read = is.read(buf)) >= 0) {
					os.write(buf, 0, read);
				}
			}
		}
	}

	private static String mavenPath(String group, String artifact, String version) {
		return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
	}

	private static String libraryJson(String coordinate, String url) {
		return "{\"name\":\"" + escape(coordinate) + "\",\"url\":\"" + escape(url) + "\"}";
	}

	private static String libraryJsonNoUrl(String coordinate) {
		return "{\"name\":\"" + escape(coordinate) + "\"}";
	}

	private static String buildProfileJson(String profileId, String mcVersion, String mainClass, String ownCoordinate, List<String> thirdPartyLibraries) {
		StringBuilder libs = new StringBuilder();
		libs.append(libraryJsonNoUrl(ownCoordinate)); // собственный jar уже на диске, url не нужен

		for (String lib : thirdPartyLibraries) {
			libs.append(',').append(lib);
		}

		String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

		return "{\n"
				+ "  \"id\": \"" + escape(profileId) + "\",\n"
				+ "  \"inheritsFrom\": \"" + escape(mcVersion) + "\",\n"
				+ "  \"type\": \"release\",\n"
				+ "  \"time\": \"" + now + "\",\n"
				+ "  \"releaseTime\": \"" + now + "\",\n"
				+ "  \"mainClass\": \"" + escape(mainClass) + "\",\n"
				+ "  \"libraries\": [" + libs + "]\n"
				+ "}\n";
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private AnchorMain() {
	}
}
