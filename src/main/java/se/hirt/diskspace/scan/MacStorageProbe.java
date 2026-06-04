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
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;
import se.hirt.diskspace.model.StorageProfile;
import se.hirt.diskspace.model.Volume;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Native-image-only IOKit classifier that replaces the {@code diskutil info -plist} shellout in
 * {@code StorageProfileProbe.probeMac}. Per volume:
 * <ol>
 *   <li><b>Primary path:</b> Take the BSD device name straight from {@link Volume#deviceName()} (e.g.
 *       {@code "/dev/disk3s5"} → {@code "disk3s5"}), look up the matching {@code IOMedia} service via
 *       {@code IOBSDNameMatching} + {@code IOServiceGetMatchingService}, then walk its parent chain via
 *       {@code IORegistryEntrySearchCFProperty(kIORegistryIterateParents | kIORegistryIterateRecursively)} for
 *       {@code "Device Characteristics"}, and read {@code "Medium Type"} ({@code "Solid State"} /
 *       {@code "Rotational"}) from that dictionary. Same data path {@code diskutil info} uses internally. Works
 *       regardless of whether {@link Volume#root()} is a mount point or a subdirectory, because
 *       {@link java.nio.file.FileStore#name()} always reports the backing device of the containing volume.</li>
 *   <li><b>Disk Arbitration fallback:</b> when {@link Volume#deviceName()} doesn't look like {@code /dev/diskN}
 *       (rare — some FUSE volumes), try {@code DADiskCreateFromVolumePath} on the mount path and run the same
 *       parent-chain walk after pulling the BSD name with {@code DADiskGetBSDName}. This requires
 *       {@link Volume#root()} to be an actual volume mount point; for arbitrary subdirectories DA returns null and
 *       we fall through to {@link StorageProfile#UNKNOWN}, matching the legacy diskutil behaviour for unsupported
 *       inputs.</li>
 * </ol>
 * Together this replaces the per-volume {@code diskutil} subprocess (~200–500 ms cold start, plist parse) with a
 * single-digit-millisecond IOKit lookup. No admin / privilege required: IOKit's IOMedia plane is unprivileged-readable.
 * Failures degrade to {@link StorageProfile#UNKNOWN}.
 */
@Platforms(Platform.DARWIN.class)
public final class MacStorageProbe {

	private static final Logger LOG = Logger.getLogger(MacStorageProbe.class.getName());

	/** {@code kDADiskDescriptionMediaSolidStateKey}. Stage 1's CFBoolean — true=SSD, false=HDD. Often absent. */
	private static final String SOLID_STATE_KEY = "DAMediaSolidState";
	/** {@code kIOPropertyDeviceCharacteristicsKey}. Stage 2's parent-walk CFDictionary lookup. */
	private static final String DEVICE_CHARACTERISTICS_KEY = "Device Characteristics";
	/** {@code kIOPropertyMediumTypeKey}. Sub-key inside Device Characteristics. */
	private static final String MEDIUM_TYPE_KEY = "Medium Type";
	/** {@code kIOPropertyMediumTypeSolidStateKey}. Expected value for SSDs. */
	private static final String MEDIUM_TYPE_SOLID_STATE = "Solid State";
	/** {@code kIOPropertyMediumTypeRotationalKey}. Expected value for spinning disks. */
	private static final String MEDIUM_TYPE_ROTATIONAL = "Rotational";
	/** Standard IOKit plane name; everything we want is under the IOService plane. */
	private static final String IO_SERVICE_PLANE = "IOService";

	private MacStorageProbe() {
	}

	/**
	 * Pre-allocated DA session + the small handful of CFStrings / C strings every {@link #probeOne} call needs.
	 * Allocated once per {@link #probeAll} invocation, freed in reverse order on the way out so the per-volume hot path
	 * does no CF/IOKit string allocation of its own.
	 */
	private static final class ProbeContext implements AutoCloseable {
		final PointerBase session;
		final PointerBase solidStateKey;
		final PointerBase deviceCharsKey;
		final PointerBase mediumTypeKey;
		/** {@code "IOService"} as a C string for {@code IORegistryEntrySearchCFProperty}'s plane parameter. */
		final CCharPointer ioServicePlane;

		private ProbeContext(
				PointerBase session, PointerBase solidStateKey, PointerBase deviceCharsKey,
				PointerBase mediumTypeKey, CCharPointer ioServicePlane) {
			this.session = session;
			this.solidStateKey = solidStateKey;
			this.deviceCharsKey = deviceCharsKey;
			this.mediumTypeKey = mediumTypeKey;
			this.ioServicePlane = ioServicePlane;
		}

		/**
		 * Allocates everything; on partial failure (any sub-allocation returning NULL) frees what's already been
		 * allocated and returns null. Caller treats null as "fall through to UNKNOWN-for-all".
		 */
		static ProbeContext create() {
			PointerBase session = Darwin.DASessionCreate(WordFactory.nullPointer());
			if (session.isNull())
				return null;
			PointerBase solidState = Darwin.newCFString(SOLID_STATE_KEY);
			if (solidState.isNull()) {
				Darwin.CFRelease(session);
				return null;
			}
			PointerBase deviceChars = Darwin.newCFString(DEVICE_CHARACTERISTICS_KEY);
			if (deviceChars.isNull()) {
				Darwin.CFRelease(solidState);
				Darwin.CFRelease(session);
				return null;
			}
			PointerBase mediumType = Darwin.newCFString(MEDIUM_TYPE_KEY);
			if (mediumType.isNull()) {
				Darwin.CFRelease(deviceChars);
				Darwin.CFRelease(solidState);
				Darwin.CFRelease(session);
				return null;
			}
			CCharPointer plane = Darwin.allocCString(IO_SERVICE_PLANE);
			return new ProbeContext(session, solidState, deviceChars, mediumType, plane);
		}

		@Override
		public void close() {
			UnmanagedMemory.free(ioServicePlane);
			Darwin.CFRelease(mediumTypeKey);
			Darwin.CFRelease(deviceCharsKey);
			Darwin.CFRelease(solidStateKey);
			Darwin.CFRelease(session);
		}
	}

	/**
	 * Classifies every {@code volume} in {@code volumes} via Disk Arbitration + IOKit. Preserves input order in the
	 * returned map. Volumes whose mount path can't be resolved to a DA disk return {@link StorageProfile#UNKNOWN}. Pure
	 * native-image path; calling on the JVM throws {@code UnsatisfiedLinkError} via {@link Darwin}.
	 */
	public static Map<Path, StorageProfile> probeAll(List<Volume> volumes) {
		Map<Path, StorageProfile> results = new LinkedHashMap<>();
		if (volumes == null || volumes.isEmpty())
			return results;

		ProbeContext ctx = ProbeContext.create();
		if (ctx == null) {
			LOG.fine(
					"ProbeContext allocation failed — diskarbitrationd unreachable or CF OOM; classifying all as UNKNOWN");
			for (Volume v : volumes)
				results.put(v.root(), StorageProfile.UNKNOWN);
			return results;
		}
		try {
			for (Volume v : volumes) {
				results.put(v.root(), probeOne(ctx, v));
			}
		} finally {
			ctx.close();
		}
		return results;
	}

	/**
	 * Classifies a single volume. Primary path: BSD device name from {@link Volume#deviceName()} → IOKit parent walk
	 * (works for any path, including subdirectories). Falls back to a DA-via-mount-path lookup only when deviceName
	 * isn't a {@code /dev/diskN} path.
	 */
	private static StorageProfile probeOne(ProbeContext ctx, Volume v) {
		long startNanos = System.nanoTime();
		String mountStr = v.root().toString();

		String bsdName = bsdNameFromDevice(v.deviceName());
		if (bsdName != null) {
			CCharPointer bsdC = Darwin.allocCString(bsdName);
			try {
				StorageProfile lookup = lookupViaIOKit(ctx, mountStr, bsdC);
				StorageProfile result = (lookup != null) ? lookup : StorageProfile.UNKNOWN;
				long ms = (System.nanoTime() - startNanos) / 1_000_000L;
				LOG.fine(() -> "  mac: " + mountStr + " bsd=" + bsdName + " -> " + result + " (" + ms + "ms)");
				return result;
			} finally {
				UnmanagedMemory.free(bsdC);
			}
		}

		// deviceName isn't /dev/diskN (rare — FUSE volumes etc.). Try DA via mount
		// path; only succeeds when mountStr is the actual volume mount point.
		return probeViaDA(ctx, v, mountStr, startNanos);
	}

	/**
	 * Strips the {@code /dev/} prefix off a FileStore-reported device name. Returns the bare BSD identifier (e.g.
	 * {@code "disk3s5"}) or {@code null} when the input doesn't start with {@code /dev/} — typical for network mounts
	 * (already short-circuited upstream by {@code isNetworkFsType}) and some FUSE volumes whose device names are
	 * synthetic strings like {@code "macFUSE"}.
	 */
	private static String bsdNameFromDevice(String deviceName) {
		if (deviceName == null)
			return null;
		final String prefix = "/dev/";
		if (!deviceName.startsWith(prefix))
			return null;
		String rest = deviceName.substring(prefix.length());
		return rest.isEmpty() ? null : rest;
	}

	/**
	 * Mount-path-based DA fallback. Used only when {@link #bsdNameFromDevice} returns null. Requires {@code mountStr}
	 * to be a real volume mount point — {@code DADiskCreateFromVolumePath} doesn't normalize subdirectories and
	 * silently returns NULL otherwise, in which case this method ends at UNKNOWN.
	 */
	private static StorageProfile probeViaDA(ProbeContext ctx, Volume v, String mountStr, long startNanos) {
		PointerBase pathStr = Darwin.newCFString(mountStr);
		if (pathStr.isNull()) {
			LOG.fine(() -> "  mac: " + mountStr + " CFString alloc failed");
			return StorageProfile.UNKNOWN;
		}
		try {
			PointerBase url = Darwin.CFURLCreateWithFileSystemPath(WordFactory.nullPointer(), pathStr,
					Darwin.kCFURLPOSIXPathStyle, (byte) 1);
			if (url.isNull()) {
				LOG.fine(() -> "  mac: " + mountStr + " CFURL alloc failed");
				return StorageProfile.UNKNOWN;
			}
			try {
				PointerBase disk = Darwin.DADiskCreateFromVolumePath(WordFactory.nullPointer(), ctx.session, url);
				if (disk.isNull()) {
					LOG.fine(() -> "  mac: " + mountStr + " DADiskCreateFromVolumePath returned NULL -> UNKNOWN");
					return StorageProfile.UNKNOWN;
				}
				try {
					StorageProfile p = classifyDisk(ctx, mountStr, disk);
					long ms = (System.nanoTime() - startNanos) / 1_000_000L;
					LOG.fine(() -> "  mac: " + mountStr + " DA -> " + p + " (" + ms + "ms)");
					return p;
				} finally {
					Darwin.CFRelease(disk);
				}
			} finally {
				Darwin.CFRelease(url);
			}
		} finally {
			Darwin.CFRelease(pathStr);
		}
	}

	/**
	 * Two-stage classifier for an open DA disk handle. Tries the volume's own description first (cheap CFDict lookup);
	 * on the typical APFS miss, falls through to the IOKit parent walk via {@code DADiskGetBSDName}.
	 */
	private static StorageProfile classifyDisk(ProbeContext ctx, String mountStr, PointerBase disk) {
		// Stage 1: per-volume DA description.
		PointerBase desc = Darwin.DADiskCopyDescription(disk);
		if (desc.isNonNull()) {
			try {
				PointerBase val = Darwin.CFDictionaryGetValue(desc, ctx.solidStateKey);
				if (val.isNonNull()) {
					int b = Darwin.CFBooleanGetValue(val) & 0xFF;
					return b != 0 ? StorageProfile.SSD : StorageProfile.HDD;
				}
			} finally {
				Darwin.CFRelease(desc);
			}
		}

		// Stage 2: DA didn't expose SolidState (typical for APFS volume disks where the
		// property only lives on the parent physical media). Walk IOKit parents.
		CCharPointer bsdName = Darwin.DADiskGetBSDName(disk);
		if (bsdName.isNull()) {
			LOG.fine(() -> "  mac: " + mountStr + " DAMediaSolidState absent + no BSD name -> UNKNOWN");
			return StorageProfile.UNKNOWN;
		}
		StorageProfile p = lookupViaIOKit(ctx, mountStr, bsdName);
		return p != null ? p : StorageProfile.UNKNOWN;
	}

	/**
	 * Resolves an IOMedia node by BSD name and walks its parent chain (recursively) for a
	 * {@code "Device Characteristics"} dictionary, then reads {@code "Medium Type"} out of it. Returns SSD/HDD on a
	 * recognised value or {@code null} on any failure (no match, property missing, unrecognised medium type). Caller
	 * maps null to UNKNOWN.
	 */
	private static StorageProfile lookupViaIOKit(ProbeContext ctx, String mountStr, CCharPointer bsdName) {
		// IOServiceGetMatchingService consumes the matching dictionary, so we don't release
		// it ourselves — even on the no-match (service == 0) path, the dict is freed.
		PointerBase matching = Darwin.IOBSDNameMatching(Darwin.kIOMainPortDefault, 0, bsdName);
		if (matching.isNull()) {
			LOG.fine(() -> "  mac: " + mountStr + " IOBSDNameMatching returned NULL -> UNKNOWN");
			return null;
		}
		int service = Darwin.IOServiceGetMatchingService(Darwin.kIOMainPortDefault, matching);
		if (service == 0) {
			LOG.fine(() -> "  mac: " + mountStr + " IOServiceGetMatchingService no-match -> UNKNOWN");
			return null;
		}
		try {
			int options = Darwin.kIORegistryIterateRecursively | Darwin.kIORegistryIterateParents;
			PointerBase props = Darwin.IORegistryEntrySearchCFProperty(service, ctx.ioServicePlane, ctx.deviceCharsKey,
					WordFactory.nullPointer(), options);
			if (props.isNull()) {
				LOG.fine(() -> "  mac: " + mountStr + " no Device Characteristics in IORegistry parents -> UNKNOWN");
				return null;
			}
			try {
				PointerBase mediumVal = Darwin.CFDictionaryGetValue(props, ctx.mediumTypeKey);
				if (mediumVal.isNull()) {
					LOG.fine(() -> "  mac: " + mountStr + " Device Characteristics missing Medium Type -> UNKNOWN");
					return null;
				}
				String mediumType = Darwin.cfStringToJava(mediumVal);
				if (MEDIUM_TYPE_SOLID_STATE.equals(mediumType)) {
					LOG.fine(() -> "  mac: " + mountStr + " IOKit Medium Type='Solid State' -> SSD");
					return StorageProfile.SSD;
				}
				if (MEDIUM_TYPE_ROTATIONAL.equals(mediumType)) {
					LOG.fine(() -> "  mac: " + mountStr + " IOKit Medium Type='Rotational' -> HDD");
					return StorageProfile.HDD;
				}
				LOG.fine(() -> "  mac: " + mountStr + " IOKit Medium Type='" + mediumType + "' -> UNKNOWN");
				return null;
			} finally {
				Darwin.CFRelease(props);
			}
		} finally {
			Darwin.IOObjectRelease(service);
		}
	}
}
