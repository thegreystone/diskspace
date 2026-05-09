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
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;
import se.hirt.diskspace.model.StorageProfile;
import se.hirt.diskspace.model.Volume;
import se.hirt.diskspace.scan.Win32.HANDLE;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Native-image-only Win32 classifier that replaces the PowerShell shellout in {@code StorageProfileProbe.probeWindowsBatch}. Per drive:
 * {@code GetDriveTypeW} for network/removable detection, then {@code IOCTL_STORAGE_QUERY_PROPERTY(StorageDeviceSeekPenaltyProperty)} on the
 * volume handle to read {@code DEVICE_SEEK_PENALTY_DESCRIPTOR.IncursSeekPenalty} for SSD-vs-HDD. Total cost ~5–10 ms per drive vs. ~2.4 s
 * for the PowerShell batch (PowerShell startup + WMI/CIM connection dominate that figure).
 * <p>No admin / privilege required: the volume is opened with zero {@code dwDesiredAccess},
 * which is sufficient for {@code IOCTL_STORAGE_QUERY_PROPERTY}. Failures (drive not present, ioctl unsupported on enterprise / SAS / RAID
 * controllers, etc.) degrade to {@link StorageProfile#UNKNOWN} rather than blocking — matches the existing PowerShell-failure semantics.
 */
@Platforms(Platform.WINDOWS.class)
public final class Win32StorageProbe {

	private static final Logger LOG = Logger.getLogger(Win32StorageProbe.class.getName());

	// GetDriveTypeW return values
	private static final int DRIVE_REMOVABLE = 2;
	private static final int DRIVE_FIXED = 3;
	private static final int DRIVE_REMOTE = 4;

	/** {@code IOCTL_STORAGE_QUERY_PROPERTY = CTL_CODE(IOCTL_STORAGE_BASE, 0x500, METHOD_BUFFERED, FILE_ANY_ACCESS) = 0x2D1400}. */
	private static final int IOCTL_STORAGE_QUERY_PROPERTY = 0x2D1400;
	/** {@code STORAGE_PROPERTY_ID.StorageDeviceSeekPenaltyProperty = 7}. */
	private static final int StorageDeviceSeekPenaltyProperty = 7;
	/** {@code STORAGE_QUERY_TYPE.PropertyStandardQuery = 0}. */
	private static final int PropertyStandardQuery = 0;

	// CreateFile parameters for opening the volume with zero access. FILE_SHARE_READ|WRITE
	// is required because the volume is already mounted for normal use; without share flags
	// the open would fail with ERROR_SHARING_VIOLATION.
	private static final int FILE_SHARE_READ = 0x00000001;
	private static final int FILE_SHARE_WRITE = 0x00000002;
	private static final int OPEN_EXISTING = 3;

	private Win32StorageProbe() {
	}

	/**
	 * Classifies every {@code volume} in {@code volumes} via direct Win32 ioctls. Preserves input order in the returned map. UNC mounts (no
	 * drive letter) and unrecognised inputs return {@link StorageProfile#UNKNOWN}; the caller is responsible for short-circuiting those
	 * before calling this method if it has cheaper information. Pure native-image path; calling on the JVM throws
	 * {@code UnsatisfiedLinkError} via {@link Win32}.
	 */
	public static Map<Path, StorageProfile> probeAll(List<Volume> volumes) {
		Map<Path, StorageProfile> results = new LinkedHashMap<>();
		for (Volume v : volumes) {
			Path root = v.root();
			String mountStr = root.toString();
			StorageProfile p;
			if (mountStr.startsWith("\\\\")) {
				// Direct UNC mount, no drive letter.
				LOG.fine(() -> "  win32: " + mountStr + " UNC -> NETWORK");
				p = StorageProfile.NETWORK;
			} else if (mountStr.length() < 2 || mountStr.charAt(1) != ':') {
				LOG.fine(() -> "  win32: cannot extract drive letter from " + mountStr);
				p = StorageProfile.UNKNOWN;
			} else {
				String drive = mountStr.substring(0, 1).toUpperCase();
				p = probeOne(drive);
			}
			results.put(root, p);
		}
		return results;
	}

	/**
	 * Classifies a single drive letter (e.g. {@code "C"}). {@link StorageProfile#NETWORK} for {@code DRIVE_REMOTE},
	 * {@link StorageProfile#SSD} / {@link StorageProfile#HDD} from the seek-penalty descriptor, otherwise {@link StorageProfile#UNKNOWN}.
	 */
	public static StorageProfile probeOne(String driveLetter) {
		long startNanos = System.nanoTime();

		// Step 1: GetDriveTypeW — handles network mappings (DRIVE_REMOTE) without any volume open.
		int driveType;
		CCharPointer rootBuf = Win32.allocWideString(driveLetter + ":\\");
		try {
			driveType = Win32.GetDriveTypeW(rootBuf);
		} finally {
			UnmanagedMemory.free(rootBuf);
		}
		if (driveType == DRIVE_REMOTE) {
			long ms = (System.nanoTime() - startNanos) / 1_000_000L;
			LOG.fine(() -> "  win32: " + driveLetter + " driveType=DRIVE_REMOTE -> NETWORK (" + ms + "ms)");
			return StorageProfile.NETWORK;
		}
		if (driveType != DRIVE_FIXED && driveType != DRIVE_REMOVABLE) {
			LOG.fine(() -> "  win32: " + driveLetter + " driveType=" + driveType + " -> UNKNOWN");
			return StorageProfile.UNKNOWN;
		}

		// Step 2: open the volume with zero desired access. No admin needed; this is a metadata
		// query, not a read. Share flags are mandatory because the volume is already mounted.
		HANDLE h;
		CCharPointer volBuf = Win32.allocWideString("\\\\.\\" + driveLetter + ":");
		try {
			h = Win32.CreateFileW(volBuf, 0, FILE_SHARE_READ | FILE_SHARE_WRITE, WordFactory.nullPointer(), OPEN_EXISTING, 0,
					Win32.nullHandle());
		} finally {
			UnmanagedMemory.free(volBuf);
		}
		if (h.isNull() || h.equal(Win32.invalidHandleValue())) {
			int err = Win32.GetLastError();
			LOG.fine(() -> "  win32: " + driveLetter + " CreateFile(\\\\.\\" + driveLetter + ":) failed err=" + err);
			return StorageProfile.UNKNOWN;
		}

		try {
			StorageProfile p = querySeekPenalty(h, driveLetter);
			long ms = (System.nanoTime() - startNanos) / 1_000_000L;
			LOG.fine(() -> "  win32: " + driveLetter + " -> " + p + " (" + ms + "ms)");
			return p;
		} finally {
			Win32.CloseHandle(h);
		}
	}

	/**
	 * Runs {@code IOCTL_STORAGE_QUERY_PROPERTY(StorageDeviceSeekPenaltyProperty)}. Returns {@link StorageProfile#SSD} when
	 * {@code IncursSeekPenalty == 0}, {@link StorageProfile#HDD} when nonzero, or {@link StorageProfile#UNKNOWN} on ioctl failure (e.g.
	 * enterprise / SAS / RAID controllers that don't implement the descriptor).
	 * <pre>
	 *   STORAGE_PROPERTY_QUERY input (12 bytes):
	 *     +0  DWORD PropertyId         = StorageDeviceSeekPenaltyProperty (7)
	 *     +4  DWORD QueryType          = PropertyStandardQuery (0)
	 *     +8  BYTE  AdditionalParameters[1] (4 bytes pad to 12)
	 *   DEVICE_SEEK_PENALTY_DESCRIPTOR output (12 bytes):
	 *     +0  DWORD   Version
	 *     +4  DWORD   Size
	 *     +8  BOOLEAN IncursSeekPenalty  ← what we read
	 *     +9  3 bytes pad
	 * </pre>
	 */
	private static StorageProfile querySeekPenalty(HANDLE h, String driveLetter) {
		Pointer query = UnmanagedMemory.malloc(12);
		Pointer out = UnmanagedMemory.malloc(12);
		CIntPointer bytesReturned = StackValue.get(CIntPointer.class);
		try {
			query.writeInt(0, StorageDeviceSeekPenaltyProperty);
			query.writeInt(4, PropertyStandardQuery);
			query.writeInt(8, 0);
			out.writeInt(0, 0);
			out.writeInt(4, 0);
			out.writeInt(8, 0);
			int ok = Win32.DeviceIoControl(h, IOCTL_STORAGE_QUERY_PROPERTY, query, 12, out, 12, bytesReturned, WordFactory.nullPointer());
			if (ok == 0) {
				int err = Win32.GetLastError();
				LOG.fine(() -> "  win32: " + driveLetter + " StorageDeviceSeekPenaltyProperty err=" + err);
				return StorageProfile.UNKNOWN;
			}
			int incursSeekPenalty = out.readByte(8) & 0xFF;
			return incursSeekPenalty != 0 ? StorageProfile.HDD : StorageProfile.SSD;
		} finally {
			UnmanagedMemory.free(query);
			UnmanagedMemory.free(out);
		}
	}
}
