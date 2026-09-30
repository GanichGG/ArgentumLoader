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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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

	// AWT/Swing под GraalVM native-image не встраиваются в сам .exe целиком — эти маленькие
	// нативные библиотеки собраны как встроенные ресурсы (см. installer/build.gradle,
	// copyNativeLibs) и распаковываются рядом с .exe при самом первом запуске, до того как
	// что-либо в программе успевает их запросить (см. static-блок ниже и порядок полей: он
	// должен отработать раньше HEADLESS, иначе Swing попытается загрузить их первым).
	private static final String[] NATIVE_LIBS = {
			"awt.dll", "fontmanager.dll", "freetype.dll", "java.dll",
			"javaaccessbridge.dll", "javajpeg.dll", "jawt.dll", "jvm.dll", "lcms.dll"
	};

	static {
		extractNativeLibsIfNeeded();
	}

	// Пока целимся только на одну версию; список расширим по мере тестирования на других.
	private static final String[] SUPPORTED_VERSIONS = {"1.21.1"};

	// Обычный запуск (двойной клик) — окно выбора версии и окно с результатом.
	// Если передан аргумент командной строки или окна недоступны (headless), используется он
	// вместо диалога, а результат печатается в консоль — этим путём проходят автотесты.
	private static final boolean HEADLESS = GraphicsEnvironment.isHeadless();

	public static void main(String[] args) {
		String presetVersion = args.length > 0 ? args[0] : null;

		try {
			InstallResult result = install(presetVersion);
			String message = "<html><b>ArgentumLoader установлен!</b><br><br>"
					+ "Профиль записан в:<br>" + escapeHtml(result.profilePath.toString()) + "<br><br>"
					+ "Теперь выберите профиль <b>ArgentumLoader</b> в лаунчере Minecraft и нажмите «Играть» —<br>"
					+ "недостающие библиотеки лаунчер скачает сам.</html>";

			if (HEADLESS) {
				System.out.println(result.profilePath);
			} else {
				// Диалог блокирует поток до закрытия — самоочистку планируем уже после этого,
				// чтобы не пытаться удалить ещё выполняющийся .exe/jar (файл будет занят).
				JOptionPane.showMessageDialog(null, message, "ArgentumLoader", JOptionPane.INFORMATION_MESSAGE);
				scheduleCleanup(result.leftovers);
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

	private static InstallResult install(String presetVersion) throws IOException {
		InstallLocation location = findInstallLocation();
		Path gameDir = location.gameDir;

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

		return new InstallResult(profileJson, location.leftovers);
	}

	private static final class InstallResult {
		final Path profilePath;
		final List<Path> leftovers;

		InstallResult(Path profilePath, List<Path> leftovers) {
			this.profilePath = profilePath;
			this.leftovers = leftovers;
		}
	}

	private static final class InstallLocation {
		final Path gameDir;
		/** Собственные файлы якоря (exe/app/runtime или сам jar) — после успешной установки не нужны. */
		final List<Path> leftovers;

		InstallLocation(Path gameDir, List<Path> leftovers) {
			this.gameDir = gameDir;
			this.leftovers = leftovers;
		}
	}

	/**
	 * Папка, в которой физически лежит сам якорь — а не рабочая директория процесса
	 * (на Windows при запуске двойным кликом она может оказаться System32) — плюс список
	 * его собственных файлов, которые после успешной установки становятся не нужны.
	 */
	private static InstallLocation findInstallLocation() {
		try {
			CodeSource codeSource = AnchorMain.class.getProtectionDomain().getCodeSource();

			if (codeSource != null) {
				Path jarPath = Paths.get(codeSource.getLocation().toURI());

				if (Files.isRegularFile(jarPath)) {
					Path jarDir = jarPath.getParent().toAbsolutePath();

					// В .exe-сборке (jpackage --type app-image) jar лежит не рядом с .exe,
					// а во вложенной папке "app": <корень>/app/<jar>, <корень>/runtime/,
					// <корень>/ArgentumAnchor.exe — в этом случае нужен именно <корень>,
					// а мусор для очистки — вся тройка (app/, runtime/, сам .exe).
					Path appImageRoot = jarDir.getParent();

					if (appImageRoot != null
							&& "app".equals(jarDir.getFileName().toString())
							&& Files.isDirectory(appImageRoot.resolve("runtime"))) {
						List<Path> leftovers = new ArrayList<>();
						leftovers.add(jarDir);
						leftovers.add(appImageRoot.resolve("runtime"));

						Path exe = findSiblingExe(appImageRoot);
						if (exe != null) leftovers.add(exe);

						return new InstallLocation(appImageRoot, leftovers);
					}

					return new InstallLocation(jarDir, Collections.singletonList(jarPath));
				}
			}
		} catch (URISyntaxException e) {
			// падаем на fallback ниже
		}

		// Под GraalVM native-image classSource() возвращает null — нет реальной загрузки из
		// jar в рантайме. Определяем расположение по самому процессу ОС (не зависит от cwd).
		Path exePath = currentExecutablePath();

		if (exePath != null) {
			Path dir = exePath.getParent().toAbsolutePath();
			List<Path> leftovers = new ArrayList<>();
			leftovers.add(exePath);

			for (String lib : NATIVE_LIBS) {
				Path libPath = dir.resolve(lib);
				if (Files.exists(libPath)) leftovers.add(libPath);
			}

			return new InstallLocation(dir, leftovers);
		}

		// Например, при запуске не из jar и не из native-exe (dev-окружение) — берём рабочую
		// директорию, чистить нечего.
		return new InstallLocation(Paths.get("").toAbsolutePath(), Collections.emptyList());
	}

	private static boolean isNativeImage() {
		return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
	}

	/**
	 * Кладёт встроенные в .exe AWT/Swing-библиотеки рядом с самим .exe, если их там ещё нет —
	 * до того как что-либо в программе их запросит (см. порядок static-полей класса). Вне
	 * native-image (обычный jar) ничего не делает — там эти библиотеки достаёт сама JVM.
	 */
	private static void extractNativeLibsIfNeeded() {
		if (!isNativeImage()) return;

		Path exePath = currentExecutablePath();
		if (exePath == null) return;

		Path dir = exePath.getParent();
		if (dir == null) return;

		// GraalVM автоматически включает в образ только те ресурсы, чей путь виден статическому
		// анализатору как строковый литерал прямо в вызове getResourceAsStream — путь, собранный
		// динамически (например, в цикле по массиву имён), он не видит и не встраивает. Поэтому
		// здесь принципиально 9 отдельных вызовов с литеральными путями, а не цикл по NATIVE_LIBS.
		extractOne(dir, "awt.dll", AnchorMain.class.getResourceAsStream("/awt.dll.bin"));
		extractOne(dir, "fontmanager.dll", AnchorMain.class.getResourceAsStream("/fontmanager.dll.bin"));
		extractOne(dir, "freetype.dll", AnchorMain.class.getResourceAsStream("/freetype.dll.bin"));
		extractOne(dir, "java.dll", AnchorMain.class.getResourceAsStream("/java.dll.bin"));
		extractOne(dir, "javaaccessbridge.dll", AnchorMain.class.getResourceAsStream("/javaaccessbridge.dll.bin"));
		extractOne(dir, "javajpeg.dll", AnchorMain.class.getResourceAsStream("/javajpeg.dll.bin"));
		extractOne(dir, "jawt.dll", AnchorMain.class.getResourceAsStream("/jawt.dll.bin"));
		extractOne(dir, "jvm.dll", AnchorMain.class.getResourceAsStream("/jvm.dll.bin"));
		extractOne(dir, "lcms.dll", AnchorMain.class.getResourceAsStream("/lcms.dll.bin"));
	}

	private static void extractOne(Path dir, String targetName, InputStream is) {
		try (InputStream stream = is) {
			if (stream == null) return; // не встроено — пропускаем, а не валим установку

			Path target = dir.resolve(targetName);
			if (Files.exists(target)) return;

			byte[] shifted = stream.readAllBytes();

			// первые 4 байта — сдвиг, добавленный при упаковке (см. installer/build.gradle,
			// copyNativeLibs) — историческая подстраховка, оставлена на случай похожих проблем.
			try (OutputStream os = Files.newOutputStream(target)) {
				os.write(shifted, 4, shifted.length - 4);
			}
		} catch (IOException e) {
			// самораспаковка — best effort; если что-то не вышло, Windows всё равно
			// поищет библиотеку по остальным путям (PATH и т.п.)
			e.printStackTrace();
		}
	}

	private static Path currentExecutablePath() {
		try {
			return ProcessHandle.current().info().command().map(Paths::get).orElse(null);
		} catch (Exception e) {
			return null;
		}
	}

	private static Path findSiblingExe(Path root) {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*.exe")) {
			for (Path p : stream) {
				return p; // в app-image ровно один .exe
			}
		} catch (IOException ignored) {
			// не критично — просто не удалим .exe отдельно
		}

		return null;
	}

	/**
	 * Планирует удаление собственных файлов якоря отдельным (не дочерним для JVM) процессом
	 * с небольшой задержкой — напрямую удалить их сейчас нельзя: .exe/jar ещё заняты, пока
	 * этот процесс не завершится. На игру и уже записанные versions/libraries не влияет —
	 * в список leftovers они не входят.
	 */
	private static void scheduleCleanup(List<Path> leftovers) {
		if (leftovers.isEmpty() || !isWindows()) {
			return;
		}

		StringBuilder cmd = new StringBuilder("ping 127.0.0.1 -n 3 > nul");

		for (Path p : leftovers) {
			String quoted = "\"" + p.toAbsolutePath() + "\"";
			cmd.append(" & ").append(Files.isDirectory(p) ? "rmdir /s /q " : "del /f /q ").append(quoted);
		}

		try {
			new ProcessBuilder("cmd", "/c", cmd.toString())
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.start();
		} catch (IOException e) {
			// самоочистка — необязательный бонус, установку из-за её сбоя не проваливаем
			e.printStackTrace();
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
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
