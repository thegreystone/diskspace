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

import org.graalvm.nativeimage.*;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;
import se.hirt.diskspace.scan.Win32.HANDLE;

import java.util.List;
import java.util.logging.Logger;

/**
 * Windows-only helpers for detecting and requesting administrator elevation. Wraps {@code OpenProcessToken} /
 * {@code GetTokenInformation(TokenElevation)} for the detect side and {@code ShellExecuteW} with the {@code "runas"}
 * verb for the relaunch side.
 * <p><b>Native-image only.</b> {@link #isAvailable()} returns false in JVM dev mode, so the
 * caller (typically {@code App.maybeOfferElevation}) just won't surface the prompt there. The Win32 calls are direct
 * {@code @CFunction} invocations linked by native-image — no JNI shim, no extra DLL.
 * <p>The class is gated with {@code @Platforms(WINDOWS)} so it does not exist on non-Windows
 * native-images at all; cross-platform code reaches it only via {@code platform.Capabilities.ELEVATION}. Direct imports
 * from cross-platform code are a build-time error rather than a silent native-image regression.
 */
@Platforms(Platform.WINDOWS.class)
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
	/** {@code TOKEN_ADJUST_PRIVILEGES = 0x0020}. */
	private static final int TOKEN_ADJUST_PRIVILEGES = 0x0020;
	/** {@code TokenInformationClass.TokenPrivileges = 3} — enumerates the token's privilege array. */
	private static final int TOKEN_PRIVILEGES_CLASS = 3;
	/** {@code SE_PRIVILEGE_REMOVED = 0x00000004} — {@code AdjustTokenPrivileges} deletes the privilege (irreversible). */
	private static final int SE_PRIVILEGE_REMOVED = 0x00000004;

	/**
	 * Privileges to keep when {@link #dropToBackupPrivileges()} hardens an elevated token: what a standard-user token
	 * always carries, plus the two the MFT scanner actually uses ({@code SeBackupPrivilege} for ACL-bypass reads,
	 * {@code SeManageVolumePrivilege} for volume queries). Everything else in the full admin token is removed.
	 * {@code SeChangeNotifyPrivilege} in particular must stay — it's bypass-traverse-checking, relied on for ordinary
	 * path resolution.
	 */
	private static final String[] KEEP_PRIVILEGES = {
			"SeBackupPrivilege", "SeManageVolumePrivilege",
			"SeChangeNotifyPrivilege", "SeShutdownPrivilege", "SeUndockPrivilege",
			"SeIncreaseWorkingSetPrivilege", "SeTimeZonePrivilege"
	};

	/**
	 * True iff we're on Windows AND running as a built native-image (not JVM dev mode). The {@code @CFunction} bindings
	 * only resolve in native-image builds; calling them from a regular JVM throws {@code UnsatisfiedLinkError}, so this
	 * guard is mandatory.
	 */
	public static boolean isAvailable() {
		if (!System.getProperty("os.name", "").toLowerCase().contains("win"))
			return false;
		return ImageInfo.inImageRuntimeCode();
	}

	/**
	 * Returns true iff the current process token is elevated (i.e. running as administrator via UAC). Returns false on
	 * any error or when {@link #isAvailable()} is false.
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
	 * Spawns a new copy of the current process via {@code ShellExecuteW} with the {@code "runas"} verb. Windows shows
	 * the UAC prompt and, on consent, launches the new process elevated. Returns true if the spawn was initiated
	 * successfully (the user may still decline UAC, which produces a successful return here but no elevated process).
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
			long status = Win32.ShellExecuteW(Win32.nullHandle(), verb, file, pars, WordFactory.nullPointer(),
					SW_SHOWNORMAL);
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
	 * Quoting that's just-good-enough for re-passing argv through {@code ShellExecute}'s {@code lpParameters}. Wraps
	 * anything containing whitespace or quotes in double quotes with internal quotes escaped. Skips empty args.
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

	/**
	 * Defense-in-depth privilege drop. Irreversibly removes every privilege except {@link #KEEP_PRIVILEGES} from this
	 * process's primary token via {@code AdjustTokenPrivileges} with {@code SE_PRIVILEGE_REMOVED}. After this, dormant
	 * admin privileges ({@code SeDebug}, {@code SeRestore}, {@code SeTakeOwnership}, {@code SeLoadDriver}, …) can no
	 * longer be enabled even by a bug, while {@code SeBackup}/{@code SeManageVolume} remain present for the MFT scanner
	 * to enable on its first scan. Edits the running token in place — no child process.
	 * <p>No-op when un-elevated (a UAC-filtered token has already shed these) or
	 * when {@link #isAvailable()} is false. <b>Does not</b> remove Administrators group membership: ACL-based admin
	 * access still exists — neutering the group would require {@code CreateRestrictedToken} + a re-spawned child.
	 * <p>Removal is irreversible for the process lifetime, so the keep-list is
	 * load-bearing; in particular {@code SeChangeNotifyPrivilege} must stay or ordinary path traversal can break.
	 */
	public static void dropToBackupPrivileges() {
		if (!isAvailable() || !isElevated())
			return;
		WordPointer tokenRef = StackValue.get(WordPointer.class);
		if (Win32.OpenProcessToken(Win32.GetCurrentProcess(), TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY, tokenRef) == 0) {
			LOG.warning("dropToBackupPrivileges: OpenProcessToken failed, err=" + Win32.GetLastError());
			return;
		}
		HANDLE token = (HANDLE) tokenRef.read();
		try {
			// Resolve the keep-list names to their (system-specific) 8-byte LUIDs so we can compare by value.
			java.util.Set<Long> keep = new java.util.HashSet<>();
			for (String name : KEEP_PRIVILEGES) {
				long luid = lookupLuid(name);
				if (luid != 0L)
					keep.add(luid);
			}

			// Two-call GetTokenInformation(TokenPrivileges): size, then fill.
			CIntPointer needed = StackValue.get(CIntPointer.class);
			needed.write(0);
			Win32.GetTokenInformation(token, TOKEN_PRIVILEGES_CLASS, WordFactory.nullPointer(), 0, needed);
			int size = needed.read();
			if (size <= 0)
				return;
			Pointer buf = UnmanagedMemory.malloc(size);
			try {
				if (Win32.GetTokenInformation(token, TOKEN_PRIVILEGES_CLASS, buf, size, needed) == 0) {
					LOG.warning("dropToBackupPrivileges: GetTokenInformation failed, err=" + Win32.GetLastError());
					return;
				}
				// TOKEN_PRIVILEGES { DWORD PrivilegeCount; LUID_AND_ATTRIBUTES[] } — 12B each (LUID 8 + DWORD attrs 4).
				int count = buf.readInt(0);
				java.util.List<Long> remove = new java.util.ArrayList<>();
				for (int i = 0; i < count; i++) {
					long luid = buf.readLong(4 + i * 12);
					if (!keep.contains(luid))
						remove.add(luid);
				}
				if (remove.isEmpty()) {
					LOG.fine("dropToBackupPrivileges: nothing to remove (already minimal)");
					return;
				}

				// Build a TOKEN_PRIVILEGES of the removals, each tagged SE_PRIVILEGE_REMOVED, and apply in one call.
				int n = remove.size();
				int rmSize = 4 + n * 12;
				Pointer rm = UnmanagedMemory.malloc(rmSize);
				try {
					rm.writeInt(0, n);
					for (int i = 0; i < n; i++) {
						rm.writeLong(4 + i * 12, remove.get(i));
						rm.writeInt(4 + i * 12 + 8, SE_PRIVILEGE_REMOVED);
					}
					int adj = Win32.AdjustTokenPrivileges(token, 0, rm, rmSize, WordFactory.nullPointer(),
							WordFactory.nullPointer());
					int err = Win32.GetLastError();
					LOG.fine(() -> "dropToBackupPrivileges: removed " + n + " privilege(s), adj=" + adj + " err=" + err);
				} finally {
					UnmanagedMemory.free(rm);
				}
			} finally {
				UnmanagedMemory.free(buf);
			}
		} finally {
			Win32.CloseHandle(token);
		}
	}

	/** {@code LookupPrivilegeValueW(name)} → the 8-byte LUID as a long, or {@code 0} if the name doesn't resolve. */
	private static long lookupLuid(String name) {
		Pointer luidBuf = UnmanagedMemory.malloc(8);
		CCharPointer nameBuf = Win32.allocWideString(name);
		try {
			if (Win32.LookupPrivilegeValueW(WordFactory.nullPointer(), nameBuf, luidBuf) == 0)
				return 0L;
			return luidBuf.readLong(0);
		} finally {
			UnmanagedMemory.free(nameBuf);
			UnmanagedMemory.free(luidBuf);
		}
	}
}
