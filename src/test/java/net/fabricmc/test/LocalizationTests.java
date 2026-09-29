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

package net.fabricmc.test;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import net.fabricmc.loader.impl.util.Localization;

public class LocalizationTests {
	@Test
	public void formatRoot() {
		// ArgentumLoader: root bundle is pinned to Russian (see Localization.BUNDLE), unlike
		// upstream Fabric Loader where Locale.ROOT resolves to the English default.
		Assertions.assertEquals("клиент", Localization.formatRoot("environment.client"));
		Assertions.assertEquals("Установите A, B.", Localization.formatRoot("resolution.solution.addMod", "A", "B"));
	}
}
