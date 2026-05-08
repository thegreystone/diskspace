/*
 * Copyright (C) 2026 Marcus Hirt
 *
 * This software is free:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.diskspace.scan;

import java.util.List;
import java.util.logging.Logger;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.WinDef.INT_PTR;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.ptr.IntByReference;

/**
 * Windows-only helpers for detecting and requesting administrator elevation. Wraps the
 * standard {@code OpenProcessToken} / {@code GetTokenInformation} dance to detect elevation,
 * and {@code ShellExecuteW} with the {@code "runas"} verb to relaunch the current process
 * elevated. Intended to be invoked at app startup so users who want MFT-class scanning can
 * grant the privilege at the natural moment.
 *
 * <p>Calls into JNA — same caveat as {@link MftScanner}: not safe to invoke from a GraalVM
 * native-image build (Substrate VM JNI loader rejects JNA's {@code jnidispatch.dll}). Guard
 * with {@link #isAvailable()} before use.
 */
public final class WindowsElevation {

	private static final Logger LOG = Logger.getLogger(WindowsElevation.class.getName());

	/** {@code TokenInformationClass.TokenElevation} = 20. */
	private static final int TOKEN_ELEVATION = 20;
	/** {@code SW_SHOWNORMAL} for {@code ShellExecuteW}. */
	private static final int SW_SHOWNORMAL = 1;

	private WindowsElevation() {}

	/** True iff we're on Windows and JNA is loadable (i.e. not a native-image build). */
	public static boolean isAvailable() {
		if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return false;
		if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) return false;
		return true;
	}

	/**
	 * Returns true iff the current process token is elevated (i.e. running as administrator
	 * via UAC). Returns false on any error or when not on Windows.
	 */
	public static boolean isElevated() {
		if (!isAvailable()) return false;
		HANDLEByReference tokenRef = new HANDLEByReference();
		boolean opened = Advapi32.INSTANCE.OpenProcessToken(
				Kernel32.INSTANCE.GetCurrentProcess(),
				WinNT.TOKEN_QUERY,
				tokenRef);
		if (!opened) return false;
		HANDLE token = tokenRef.getValue();
		try {
			TokenElevation elev = new TokenElevation();
			IntByReference returnedLen = new IntByReference();
			boolean ok = Advapi32.INSTANCE.GetTokenInformation(
					token, TOKEN_ELEVATION, elev, elev.size(), returnedLen);
			if (!ok) return false;
			elev.read();
			return elev.TokenIsElevated != 0;
		} finally {
			Kernel32.INSTANCE.CloseHandle(token);
		}
	}

	/** {@code TOKEN_ELEVATION}: single DWORD, nonzero == elevated. */
	public static class TokenElevation extends Structure {
		public int TokenIsElevated;

		@Override
		protected List<String> getFieldOrder() {
			return List.of("TokenIsElevated");
		}
	}

	/**
	 * Spawns a new copy of the current process via {@code ShellExecuteW} with the {@code "runas"}
	 * verb — Windows shows the UAC prompt and, on consent, launches the new process elevated.
	 * Returns true if the spawn was initiated successfully (the user may still decline UAC,
	 * which produces a successful return here but no elevated process).
	 *
	 * <p>The caller should {@code Platform.exit()} the current (un-elevated) process after a
	 * successful return so the user isn't left with two windows.
	 *
	 * <p>Both the executable path and the original argv are read via {@link ProcessHandle},
	 * so this works for the native {@code DiskSpace.exe} (relaunches the .exe) and for
	 * {@code mvn javafx:run} (relaunches the {@code java.exe} with the same classpath args).
	 */
	public static boolean relaunchElevated() {
		if (!isAvailable()) return false;
		ProcessHandle.Info info = ProcessHandle.current().info();
		String cmd = info.command().orElse(null);
		if (cmd == null || cmd.isBlank()) {
			LOG.warning("relaunchElevated: ProcessHandle.command() unavailable; cannot self-relaunch");
			return false;
		}
		String params = info.arguments()
				.map(args -> joinArgs(List.of(args)))
				.orElse("");
		LOG.fine(() -> "relaunchElevated: ShellExecute runas cmd=" + cmd + " params=" + params);
		// ShellExecute's return is encoded as INT_PTR but is really an integer status code:
		// values 0..32 are SE_ERR_* error codes; > 32 means a launched-app HINSTANCE handle.
		INT_PTR result = Shell32.INSTANCE.ShellExecute(
				null, "runas", cmd, params.isEmpty() ? null : params, null, SW_SHOWNORMAL);
		long status = result == null ? 0 : result.longValue();
		boolean spawned = status > 32;
		if (!spawned) {
			LOG.warning("relaunchElevated: ShellExecute returned " + status
					+ " (likely user declined UAC, path not found, or runas not allowed)");
		}
		return spawned;
	}

	/** Quoting that's just-good-enough for re-passing argv through {@code ShellExecute}'s
	 *  {@code lpParameters} — wraps anything containing whitespace or quotes in double quotes
	 *  with internal quotes escaped. Skips empty args. */
	private static String joinArgs(List<String> args) {
		StringBuilder sb = new StringBuilder();
		for (String a : args) {
			if (a == null || a.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			boolean needsQuote = a.indexOf(' ') >= 0 || a.indexOf('\t') >= 0 || a.indexOf('"') >= 0;
			if (needsQuote) {
				sb.append('"').append(a.replace("\"", "\\\"")).append('"');
			} else {
				sb.append(a);
			}
		}
		return sb.toString();
	}
}
