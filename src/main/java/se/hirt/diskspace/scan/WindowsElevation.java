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

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.word.WordFactory;
import se.hirt.diskspace.scan.Win32.HANDLE;

import java.util.List;
import java.util.logging.Logger;

/**
 * Windows-only helpers for detecting and requesting administrator elevation. Wraps {@code OpenProcessToken} /
 * {@code GetTokenInformation(TokenElevation)} for the detect side and {@code ShellExecuteW} with the {@code "runas"} verb for the relaunch
 * side.
 * <p><b>Native-image only.</b> {@link #isAvailable()} returns false in JVM dev mode, so the
 * caller (typically {@code App.maybeOfferElevation}) just won't surface the prompt there. Migrating off JNA means the Win32 calls are
 * direct {@code @CFunction} invocations linked by native-image — no JNI shim, no extra DLL.
 */
public final class WindowsElevation {

	private static final Logger LOG = Logger.getLogger(WindowsElevation.class.getName());

	private WindowsElevation() {
	}

	// ── Win32 constants ───────────────────────────────────────────────────
	/** {@code TOKEN_QUERY = 0x0008}. */
	private static final int TOKEN_QUERY = 0x0008;
	/** {@code TokenInformationClass.TokenElevation = 20}. */
	private static final int TOKEN_ELEVATION = 20;
	/** {@code SW_SHOWNORMAL = 1} for {@code ShellExecuteW}. */
	private static final int SW_SHOWNORMAL = 1;

	/**
	 * True iff we're on Windows AND running as a built native-image (not JVM dev mode). The {@code @CFunction} bindings only resolve in
	 * native-image builds; calling them from a regular JVM throws {@code UnsatisfiedLinkError}, so this guard is mandatory.
	 */
	public static boolean isAvailable() {
		if (!System.getProperty("os.name", "").toLowerCase().contains("win"))
			return false;
		return ImageInfo.inImageRuntimeCode();
	}

	/**
	 * Returns true iff the current process token is elevated (i.e. running as administrator via UAC). Returns false on any error or when
	 * {@link #isAvailable()} is false.
	 */
	public static boolean isElevated() {
		if (!isAvailable())
			return false;
		WordPointer tokenRef = StackValue.get(WordPointer.class);
		int opened = Win32.OpenProcessToken(Win32.GetCurrentProcess(), TOKEN_QUERY, tokenRef);
		if (opened == 0)
			return false;
		HANDLE token = (HANDLE) tokenRef.read();
		try {
			// TOKEN_ELEVATION is a single DWORD, nonzero == elevated.
			CIntPointer elev = StackValue.get(CIntPointer.class);
			elev.write(0);
			CIntPointer returnedLen = StackValue.get(CIntPointer.class);
			int ok = Win32.GetTokenInformation(token, TOKEN_ELEVATION, elev, 4, returnedLen);
			if (ok == 0)
				return false;
			return elev.read() != 0;
		} finally {
			Win32.CloseHandle(token);
		}
	}

	/**
	 * Spawns a new copy of the current process via {@code ShellExecuteW} with the {@code "runas"} verb. Windows shows the UAC prompt and,
	 * on consent, launches the new process elevated. Returns true if the spawn was initiated successfully (the user may still decline UAC,
	 * which produces a successful return here but no elevated process).
	 * <p>Do {@code Platform.exit()} the current (un-elevated) process after a
	 * successful return so the user isn't left with two windows.
	 */
	public static boolean relaunchElevated() {
		if (!isAvailable())
			return false;
		ProcessHandle.Info info = ProcessHandle.current().info();
		String cmd = info.command().orElse(null);
		if (cmd == null || cmd.isBlank()) {
			LOG.warning("relaunchElevated: ProcessHandle.command() unavailable; cannot self-relaunch");
			return false;
		}
		String params = info.arguments().map(args -> joinArgs(List.of(args))).orElse("");
		LOG.fine(() -> "relaunchElevated: ShellExecute runas cmd=" + cmd + " params=" + params);

		CCharPointer verb = Win32.allocWideString("runas");
		CCharPointer file = Win32.allocWideString(cmd);
		CCharPointer pars = params.isEmpty() ? WordFactory.nullPointer() : Win32.allocWideString(params);
		try {
			// ShellExecute's return is encoded as HINSTANCE but is really an integer status
			// code: 0..32 are SE_ERR_* error codes; > 32 means a launched-app HINSTANCE.
			long status = Win32.ShellExecuteW(Win32.nullHandle(), verb, file, pars, WordFactory.nullPointer(), SW_SHOWNORMAL);
			boolean spawned = status > 32;
			if (!spawned) {
				LOG.warning(
						"relaunchElevated: ShellExecute returned " + status + " (likely user declined UAC, path not found, or runas not allowed)");
			}
			return spawned;
		} finally {
			UnmanagedMemory.free(verb);
			UnmanagedMemory.free(file);
			if (pars.isNonNull())
				UnmanagedMemory.free(pars);
		}
	}

	/**
	 * Quoting that's just-good-enough for re-passing argv through {@code ShellExecute}'s {@code lpParameters}. Wraps anything containing
	 * whitespace or quotes in double quotes with internal quotes escaped. Skips empty args.
	 */
	private static String joinArgs(List<String> args) {
		StringBuilder sb = new StringBuilder();
		for (String a : args) {
			if (a == null || a.isEmpty())
				continue;
			if (sb.length() > 0)
				sb.append(' ');
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
