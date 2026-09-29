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

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/**
 * Одноразовый установщик ArgentumLoader ("якорь").
 *
 * <p>Не запускает игру и ничего сам не скачивает. Игрок кладёт этот jar прямо в папку
 * своего клиента (.minecraft или папку инстанса в MultiMC/Prism) и запускает его оттуда
 * один раз. Якорь сам определяет, в какой папке он лежит (а не полагается на текущую
 * рабочую директорию процесса — при запуске двойным кликом на Windows она иногда
 * оказывается System32, а не папкой самого файла), кладёт туда собственный jar лоадера
 * и пишет один version-профиль. Все сторонние библиотеки (Mixin, ASM) после этого
 * штатно скачивает сам лаунчер Minecraft при первом запуске этого профиля — так же, как
 * он скачивает библиотеки для ванильной игры.
 */
public final class AnchorMain {
	private static final String LIBRARIES_RESOURCE = "/anchor-libraries.properties";
	private static final String LOADER_JAR_RESOURCE = "/argentumloader.jar";

	// Пока целимся только на одну версию; список расширим по мере тестирования на других.
	private static final String[] SUPPORTED_VERSIONS = {"1.21.1"};

	// Обычный запуск (двойной клик) — окно выбора версии и окно с результатом.
	// Если передан аргумент командной строки или окна недоступны (headless), используется он
	// вместо диалога, а результат печатается в консоль — этим путём проходят автотесты.
	private static final boolean HEADLESS = GraphicsEnvironment.isHeadless();

	public static void main(String[] args) {
		String presetVersion = args.length > 0 ? args[0] : null;

		try {
			Path profilePath = install(presetVersion);
			String message = "<html><b>ArgentumLoader установлен!</b><br><br>"
					+ "Профиль записан в:<br>" + escapeHtml(profilePath.toString()) + "<br><br>"
					+ "Теперь выберите профиль <b>ArgentumLoader</b> в лаунчере Minecraft и нажмите «Играть» —<br>"
					+ "недостающие библиотеки лаунчер скачает сам.</html>";

			if (HEADLESS) {
				System.out.println(profilePath);
			} else {
				JOptionPane.showMessageDialog(null, message, "ArgentumLoader", JOptionPane.INFORMATION_MESSAGE);
			}
		} catch (Exception e) {
			e.printStackTrace();
			String message = "<html><b>Не удалось установить ArgentumLoader</b><br><br>" + escapeHtml(String.valueOf(e)) + "</html>";

			if (!HEADLESS) {
				JOptionPane.showMessageDialog(null, message, "ArgentumLoader", JOptionPane.ERROR_MESSAGE);
			}

			System.exit(1);
		}
	}

	private static Path install(String presetVersion) throws IOException {
		Path gameDir = findGameDir();

		String mcVersion = presetVersion;

		if (mcVersion == null) {
			if (HEADLESS) {
				throw new IOException("нет окна для выбора версии — укажите версию Minecraft первым аргументом командной строки");
			}

			mcVersion = askVersion(gameDir);

			if (mcVersion == null) {
				throw new IOException("установка отменена");
			}
		}

		mcVersion = mcVersion.trim();

		if (!isSupported(mcVersion)) {
			throw new IOException("версия " + mcVersion + " пока не поддерживается ArgentumLoader (сейчас доступна: "
					+ String.join(", ", SUPPORTED_VERSIONS) + ")");
		}

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

	/**
	 * Папка, в которой физически лежит сам anchor-jar — а не рабочая директория процесса
	 * (на Windows при запуске двойным кликом она может оказаться System32).
	 */
	private static Path findGameDir() {
		try {
			CodeSource codeSource = AnchorMain.class.getProtectionDomain().getCodeSource();

			if (codeSource != null) {
				Path jarPath = Paths.get(codeSource.getLocation().toURI());

				if (Files.isRegularFile(jarPath)) {
					Path jarDir = jarPath.getParent().toAbsolutePath();

					// В .exe-сборке (jpackage --type app-image) jar лежит не рядом с .exe,
					// а во вложенной папке "app": <корень>/app/<jar>, <корень>/runtime/,
					// <корень>/ArgentumAnchor.exe — в этом случае нужен именно <корень>.
					Path appImageRoot = jarDir.getParent();

					if (appImageRoot != null
							&& "app".equals(jarDir.getFileName().toString())
							&& Files.isDirectory(appImageRoot.resolve("runtime"))) {
						return appImageRoot;
					}

					return jarDir;
				}
			}
		} catch (URISyntaxException e) {
			// падаем на fallback ниже
		}

		// Например, при запуске не из jar (dev-окружение) — берём рабочую директорию.
		return Paths.get("").toAbsolutePath();
	}

	private static String askVersion(Path gameDir) {
		JComboBox<String> versionBox = new JComboBox<>(SUPPORTED_VERSIONS);
		versionBox.setSelectedIndex(0);

		JLabel info = new JLabel("<html>Папка установки:<br><b>" + escapeHtml(gameDir.toString()) + "</b><br><br>"
				+ "Версия Minecraft:</html>");
		info.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

		JPanel panel = new JPanel(new BorderLayout());
		panel.add(info, BorderLayout.NORTH);
		panel.add(versionBox, BorderLayout.CENTER);

		int result = JOptionPane.showConfirmDialog(null, panel, "ArgentumLoader — установка",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);

		if (result != JOptionPane.OK_OPTION) {
			return null;
		}

		return (String) versionBox.getSelectedItem();
	}

	private static boolean isSupported(String mcVersion) {
		return Arrays.asList(SUPPORTED_VERSIONS).contains(mcVersion);
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

	private static String escapeHtml(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private AnchorMain() {
	}
}
