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

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CFunction.Transition;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;

/**
 * Direct bindings to the Windows system DLLs (kernel32, advapi32, shell32) used by {@link MftScanner} and
 * {@link WindowsElevation}, via GraalVM's {@code @CFunction}. The native-image linker resolves these symbols at build
 * time against the standard Windows SDK import libraries, so no extra DLL needs to ship alongside our executable.
 * <p><b>Native-image only.</b> In JVM dev mode (e.g. {@code mvn javafx:run}) the
 * {@code @CFunction} methods are stubs that throw on invocation; callers must guard with an {@code isAvailable()} /
 * native-image check before calling. The {@code @Platforms} annotation also gates this class out of non-Windows
 * native-image builds entirely so the linker never tries to find these symbols on Linux/macOS.
 * <p>Wide-string convention: every {@code W}-suffixed Win32 function expects {@code wchar_t*}
 * (UTF-16LE on Windows). We pass these as opaque {@link CCharPointer}s — the underlying memory is a byte buffer we fill
 * via {@link #allocWideString(String)}.
 */
@Platforms(Platform.WINDOWS.class)
public final class Win32 {

	private Win32() {
	}

	/**
	 * Marker interface for Win32 {@code HANDLE} values. Just {@link PointerBase} under the hood — the type only exists
	 * so signatures read more clearly.
	 */
	public interface HANDLE extends PointerBase {
	}

	// ── kernel32 ──────────────────────────────────────────────────────────
	//
	// All declarations use Transition.NO_TRANSITION: the Java thread state stays "in Java"
	// across the call, no thread-state prologue/epilogue is generated, and the C function is
	// invoked as a plain near-call. The default TO_NATIVE generates prologue/epilogue nodes
	// that Substrate VM's optimizer can't keep co-located with the call once the surrounding
	// method has any control flow ("Did not find a matching CFunctionEpilogueNode in same
	// block"). NO_TRANSITION sidesteps that entirely. Safe here because all our Win32 calls
	// are short kernel calls that don't touch Java objects.

	@CFunction(value = "CreateFileW", transition = Transition.NO_TRANSITION)
	public static native HANDLE CreateFileW(
			CCharPointer lpFileName, int dwDesiredAccess, int dwShareMode, PointerBase lpSecurityAttributes,
			int dwCreationDisposition, int dwFlagsAndAttributes, HANDLE hTemplateFile);

	@CFunction(value = "CloseHandle", transition = Transition.NO_TRANSITION)
	public static native int CloseHandle(HANDLE hObject);

	@CFunction(value = "DeviceIoControl", transition = Transition.NO_TRANSITION)
	public static native int DeviceIoControl(
			HANDLE hDevice, int dwIoControlCode, PointerBase lpInBuffer, int nInBufferSize, PointerBase lpOutBuffer,
			int nOutBufferSize, CIntPointer lpBytesReturned, PointerBase lpOverlapped);

	@CFunction(value = "OpenFileById", transition = Transition.NO_TRANSITION)
	public static native HANDLE OpenFileById(
			HANDLE hVolumeHint, PointerBase lpFileId, int dwDesiredAccess, int dwShareMode,
			PointerBase lpSecurityAttributes, int dwFlagsAndAttributes);

	@CFunction(value = "GetFileInformationByHandleEx", transition = Transition.NO_TRANSITION)
	public static native int GetFileInformationByHandleEx(
			HANDLE hFile, int FileInformationClass,
			PointerBase lpFileInformation, int dwBufferSize);

	@CFunction(value = "GetCurrentProcess", transition = Transition.NO_TRANSITION)
	public static native HANDLE GetCurrentProcess();

	@CFunction(value = "GetLastError", transition = Transition.NO_TRANSITION)
	public static native int GetLastError();

	/**
	 * Returns one of {@code DRIVE_UNKNOWN(0)}, {@code DRIVE_NO_ROOT_DIR(1)}, {@code DRIVE_REMOVABLE(2)},
	 * {@code DRIVE_FIXED(3)}, {@code DRIVE_REMOTE(4)}, {@code DRIVE_CDROM(5)}, or {@code DRIVE_RAMDISK(6)}. Used by
	 * storage classification to pick out network drives without any volume open.
	 */
	@CFunction(value = "GetDriveTypeW", transition = Transition.NO_TRANSITION)
	public static native int GetDriveTypeW(CCharPointer lpRootPathName);

	// ── advapi32 ──────────────────────────────────────────────────────────

	@CFunction(value = "OpenProcessToken", transition = Transition.NO_TRANSITION)
	public static native int OpenProcessToken(HANDLE ProcessHandle, int DesiredAccess, WordPointer TokenHandle);

	@CFunction(value = "LookupPrivilegeValueW", transition = Transition.NO_TRANSITION)
	public static native int LookupPrivilegeValueW(CCharPointer lpSystemName, CCharPointer lpName, PointerBase lpLuid);

	@CFunction(value = "AdjustTokenPrivileges", transition = Transition.NO_TRANSITION)
	public static native int AdjustTokenPrivileges(
			HANDLE TokenHandle, int DisableAllPrivileges, PointerBase NewState, int BufferLength,
			PointerBase PreviousState, CIntPointer ReturnLength);

	@CFunction(value = "GetTokenInformation", transition = Transition.NO_TRANSITION)
	public static native int GetTokenInformation(
			HANDLE TokenHandle, int TokenInformationClass, PointerBase TokenInformation, int TokenInformationLength,
			CIntPointer ReturnLength);

	// ── shell32 ───────────────────────────────────────────────────────────

	/**
	 * Returns {@code HINSTANCE} cast as a pointer-sized integer; {@code > 32} means success, {@code <= 32} means an
	 * SE_ERR_* error code.
	 */
	@CFunction(value = "ShellExecuteW", transition = Transition.NO_TRANSITION)
	public static native long ShellExecuteW(
			HANDLE hwnd, CCharPointer lpOperation, CCharPointer lpFile,
			CCharPointer lpParameters, CCharPointer lpDirectory, int nShowCmd);

	// ── helpers ───────────────────────────────────────────────────────────

	/**
	 * Allocates an unmanaged UTF-16LE buffer for {@code s}, null-terminated, and returns a {@link CCharPointer}
	 * pointing at it. Caller must {@link UnmanagedMemory#free} when done — there is no GC for these.
	 */
	public static CCharPointer allocWideString(String s) {
		int charCount = s.length() + 1; // +1 null terminator
		int byteCount = charCount * 2;
		CCharPointer buf = UnmanagedMemory.malloc(byteCount);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			buf.write(i * 2, (byte) (c & 0xFF));
			buf.write(i * 2 + 1, (byte) ((c >> 8) & 0xFF));
		}
		buf.write(s.length() * 2, (byte) 0);
		buf.write(s.length() * 2 + 1, (byte) 0);
		return buf;
	}

	/** {@code (HANDLE)-1}, the value {@code CreateFile} returns on failure. */
	public static HANDLE invalidHandleValue() {
		return WordFactory.pointer(-1L);
	}

	/** {@code NULL} typed as a Win32 HANDLE. */
	public static HANDLE nullHandle() {
		return WordFactory.nullPointer();
	}
}
