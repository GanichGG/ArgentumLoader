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

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * GraalVM native-image on Windows crashes with "platform encoding not initialized" the very
 * first time AWT/Swing touches a {@link java.awt.Font}, because the real (native, backed by
 * fontmanager.dll) {@code sun.awt.Win32FontManager.getFontPath} calls into JNI string-encoding
 * machinery that isn't set up yet at that point under a native image — a known, still-open
 * GraalVM/SubstrateVM gap (see github.com/gluonhq/substrate/issues/945 and
 * github.com/oracle/graal/issues/12393). {@code --initialize-at-run-time} flags don't help since
 * the failure is inside the native call itself, not in JVM-side class initialization order.
 *
 * <p>Substitutes the native method with a pure-Java equivalent that just returns the standard
 * Windows fonts directory — the same answer the native call would normally give — sidestepping
 * the native call (and the bug) entirely.
 */
@TargetClass(className = "sun.awt.Win32FontManager")
final class Target_sun_awt_Win32FontManager {
	@Substitute
	@SuppressWarnings("unused")
	protected String getFontPath(boolean noType1Fonts) {
		String windir = System.getenv("SystemRoot");

		if (windir == null || windir.isEmpty()) {
			windir = System.getenv("WINDIR");
		}

		if (windir == null || windir.isEmpty()) {
			windir = "C:\\Windows";
		}

		return windir + "\\Fonts";
	}
}
