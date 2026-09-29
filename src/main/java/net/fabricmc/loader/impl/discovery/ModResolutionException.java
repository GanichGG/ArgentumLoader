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

package net.fabricmc.loader.impl.discovery;

@SuppressWarnings("serial")
public class ModResolutionException extends Exception {
	// ArgentumLoader: for incompatibility errors, keep the full mod-by-mod report (all "участники
	// ДТП") separate from the short message so callers can log/display the short one and still
	// write the full report to a file instead of spamming the console with it.
	private final String fullReport;

	public ModResolutionException(String s) {
		super(s);
		this.fullReport = null;
	}

	public ModResolutionException(String format, Object... args) {
		super(String.format(format, args));
		this.fullReport = null;
	}

	public ModResolutionException(String s, Throwable t) {
		super(s, t);
		this.fullReport = null;
	}

	private ModResolutionException(String shortMessage, String fullReport) {
		super(shortMessage);
		this.fullReport = fullReport;
	}

	public static ModResolutionException withFullReport(String shortMessage, String fullReport) {
		return new ModResolutionException(shortMessage, fullReport);
	}

	/**
	 * The full, unabridged report (all mods involved in the conflict), for writing to a file.
	 * Falls back to the regular message if this exception wasn't constructed with a separate report.
	 */
	public String getFullReport() {
		return fullReport != null ? fullReport : getMessage();
	}
}
