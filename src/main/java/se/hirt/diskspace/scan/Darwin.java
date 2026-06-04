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
import org.graalvm.word.PointerBase;

import java.nio.charset.StandardCharsets;

/**
 * Direct bindings to the macOS system frameworks used by {@link MacStorageProbe}: CoreFoundation (for the opaque
 * {@code CFTypeRef} reference-counted object plumbing) and DiskArbitration (for the synchronous volume description
 * query that classifies SSD vs HDD without forking {@code diskutil}). The native-image linker resolves these symbols at
 * build time against the standard macOS frameworks; CoreFoundation is already linked transitively by JavaFX glass, but
 * DiskArbitration needs an explicit {@code -framework DiskArbitration} linker arg added by the Mac build profile in
 * {@code pom.xml}.
 * <p><b>Native-image only.</b> In JVM dev mode (e.g. {@code mvn javafx:run}) the
 * {@code @CFunction} methods are stubs that throw on invocation; callers must guard with an availability check before
 * calling. The {@code @Platforms} annotation also gates this class out of non-Darwin native-image builds entirely so
 * the linker never tries to find these symbols on Linux/Windows.
 * <p>CoreFoundation memory model: every {@code Create}/{@code Copy} function returns a
 * retained reference the caller owns and must release with {@link #CFRelease}; {@code Get} functions return a
 * non-retained borrow and must <em>not</em> be released. Following that rule is on the call site — there is no ARC in
 * native-image-bound Java.
 */
@Platforms(Platform.DARWIN.class)
public final class Darwin {

	private Darwin() {
	}

	/** {@code kCFStringEncodingUTF8} — the encoding we always pass to {@link #CFStringCreateWithCString}. */
	public static final int kCFStringEncodingUTF8 = 0x08000100;
	/** {@code kCFURLPOSIXPathStyle} — POSIX path interpretation for {@link #CFURLCreateWithFileSystemPath}. */
	public static final int kCFURLPOSIXPathStyle = 0;

	/** {@code kIOMainPortDefault} — pass to IOKit calls that take a {@code mach_port_t} for the default port. */
	public static final int kIOMainPortDefault = 0;
	/** {@code kIORegistryIterateRecursively} — descend the IORegistry tree during property search. */
	public static final int kIORegistryIterateRecursively = 1;
	/** {@code kIORegistryIterateParents} — walk parents instead of children during property search. */
	public static final int kIORegistryIterateParents = 2;

	// ── POSIX file / errno (libSystem) ────────────────────────────────────

	/** {@code O_RDONLY} — open for read-only. */
	public static final int O_RDONLY = 0;
	/** {@code EACCES} — permission denied (errno value). */
	public static final int EACCES = 13;
	/** {@code ENOTDIR} — not a directory (errno value). */
	public static final int ENOTDIR = 20;

	// ── getattrlist / getattrlistbulk constants ───────────────────────────

	/**
	 * Number of {@code attrgroup_t} fields in {@code struct attrlist}. Always 5 on macOS: {@code commonattr},
	 * {@code volattr}, {@code dirattr}, {@code fileattr}, {@code forkattr}.
	 */
	public static final int ATTR_BIT_MAP_COUNT = 5;
	/**
	 * Size of {@code struct attrlist} in bytes: {@code u_short bitmapcount + u_int16_t reserved + 5 × attrgroup_t} = 2
	 * + 2 + 20 = 24.
	 */
	public static final int ATTRLIST_SIZE_BYTES = 24;

	/** {@code FSOPT_NOFOLLOW} — don't follow a symlink at the start path. */
	public static final int FSOPT_NOFOLLOW = 0x00000001;
	/**
	 * {@code FSOPT_PACK_INVAL_ATTRS} — pack invalid attributes in the buffer with zeros instead of skipping them, so
	 * every entry has the same fixed layout. Mandatory for sane parsing; without it the per-entry offsets shift based
	 * on which attrs apply (file vs. directory).
	 */
	public static final int FSOPT_PACK_INVAL_ATTRS = 0x00000008;
	/**
	 * {@code FSOPT_ATTR_CMN_EXTENDED} — enable the {@code ATTR_CMNEXT_*} group. With this flag, the {@code forkattr}
	 * slot in {@code struct attrlist} is reinterpreted as a bitmap of {@code ATTR_CMNEXT_*} attributes, and the
	 * corresponding values are appended to each returned entry after the regular fileattr group. macOS 10.13+.
	 */
	public static final int FSOPT_ATTR_CMN_EXTENDED = 0x00000020;

	// commonattr bits (subset; see <sys/attr.h>)
	public static final int ATTR_CMN_NAME = 0x00000001;
	public static final int ATTR_CMN_DEVID = 0x00000002;
	public static final int ATTR_CMN_OBJTYPE = 0x00000008;
	public static final int ATTR_CMN_FILEID = 0x02000000;
	/**
	 * {@code ATTR_CMN_RETURNED_ATTRS} — when requested, the first attribute in the buffer is an {@code attribute_set_t}
	 * (5 × uint32 = 20 bytes) indicating which attributes were actually populated. With {@link #FSOPT_PACK_INVAL_ATTRS}
	 * that's everything we asked for, but we include the bit anyway so the layout is well-defined (and so we could drop
	 * {@code FSOPT_PACK_INVAL_ATTRS} later if we want a more compact buffer).
	 */
	public static final int ATTR_CMN_RETURNED_ATTRS = 0x80000000;

	// fileattr bits
	/** {@code ATTR_FILE_ALLOCSIZE} — bytes allocated on disk (physical size). uint64. */
	public static final int ATTR_FILE_ALLOCSIZE = 0x00000004;

	// commonextattr bits (only valid when FSOPT_ATTR_CMN_EXTENDED is passed; see <sys/attr.h>)
	/**
	 * {@code ATTR_CMNEXT_PRIVATESIZE} — {@code off_t} (uint64) count of bytes that would be freed if this file were
	 * deleted: bytes uniquely referenced by this file and not shared via APFS cloning. For non-cloned files this equals
	 * {@code ATTR_FILE_ALLOCSIZE}; for files in a clone family it's just the file's own CoW-modified blocks, excluding
	 * the still-shared extents. macOS 10.10+ on APFS; returns {@code allocsize} on other filesystems.
	 */
	public static final int ATTR_CMNEXT_PRIVATESIZE = 0x00000008;
	/**
	 * {@code ATTR_CMNEXT_CLONEID} — uint64 APFS data-stream identifier. Every APFS inode has a unique non-zero
	 * cloneid. Two files that share extents via {@code clonefile(2)} or {@code cp -c} can be grouped by their
	 * (currently identical) cloneids only in combination with {@link #ATTR_CMNEXT_CLONE_REFCNT} &gt; 1, which is what
	 * actually indicates the file is currently sharing extents with at least one other file in the clone family.
	 * macOS 10.13+ on APFS; returns {@code 0} on other filesystems.
	 */
	public static final int ATTR_CMNEXT_CLONEID = 0x00000100;
	/**
	 * {@code ATTR_CMNEXT_CLONE_REFCNT} — uint32 count of files currently sharing extents via the clone family this
	 * file belongs to. A value of {@code 1} (or {@code 0} on non-APFS / older kernels) means the file isn't currently
	 * sharing any extents and should be charged at its full {@code allocsize}. A value of {@code 2+} means at least one
	 * sibling shares extents with this file, and per-family dedup should fire. macOS 12+ on APFS (volume must have
	 * {@code VOL_CAP_FMT_CLONE_MAPPING}); 0 elsewhere.
	 */
	public static final int ATTR_CMNEXT_CLONE_REFCNT = 0x00001000;

	// ATTR_CMNEXT_EXT_FLAGS = 0x00000200 (uint64). Not bound; listed here so the adjacent CLONEID bit (0x100) and
	// CLONE_REFCNT bit (0x1000) are unambiguous if someone adds more CMNEXT attrs in the future.

	// fsobj_type_t values (vtype enum in <sys/vnode.h>) — what ATTR_CMN_OBJTYPE returns
	/** Regular file. */
	public static final int VREG = 1;
	/** Directory. */
	public static final int VDIR = 2;
	/** Symbolic link. */
	public static final int VLNK = 5;

	// ── statfs (libSystem) ────────────────────────────────────────────────

	/**
	 * {@code MFSTYPENAMELEN} from {@code <sys/mount.h>} — bytes reserved for the {@code f_fstypename} field in
	 * {@code struct statfs} (15 chars + NUL).
	 */
	public static final int MFSTYPENAMELEN = 16;
	/**
	 * Conservative size to allocate for a {@code struct statfs} buffer. The modern (64-bit-inode) struct on Darwin is
	 * about 2168 bytes — fixed header + two MAXPATHLEN (1024) path arrays + reserved trailer. We round up to a 4 KiB
	 * page so the binding remains correct if Apple ever extends the trailer.
	 */
	public static final int STATFS_SIZE_BYTES = 4096;
	/**
	 * Byte offset of {@code f_fstypename} inside the modern {@code struct statfs} (the {@code __DARWIN_STRUCT_STATFS64}
	 * layout, which is what plain {@code struct statfs} expands to on every platform we build for — arm64 macOS forces
	 * {@code __DARWIN_ONLY_64_BIT_INO_T}). Header: 8 B (bsize+iosize) + 5×8 B (blocks/bfree/bavail/files/ffree) + 8 B
	 * (fsid) + 4 B (owner) + 12 B (type/flags/fssubtype) = 72.
	 */
	public static final int STATFS_FSTYPENAME_OFFSET = 72;

	/**
	 * {@code int statfs(const char *path, struct statfs *buf)}. Fills {@code buf} with filesystem statistics for the
	 * filesystem containing {@code path}; returns 0 on success or -1 on failure (errno via {@link #__error}). Used at
	 * scan start to discover whether the scan root lives on APFS, so the bulk scanner can request {@code ATTR_CMNEXT_*}
	 * attributes only when they will actually populate; on non-APFS filesystems CMNEXT is either zero-filled or returns
	 * an error, so paying the extra per-entry bytes is wasted. Bound to the bare symbol — on arm64 macOS the
	 * {@code $INODE64} mangling collapses to nothing because {@code __DARWIN_ONLY_64_BIT_INO_T} is 1.
	 */
	@CFunction(value = "statfs", transition = Transition.NO_TRANSITION)
	public static native int statfs(CCharPointer path, PointerBase buf);

	// ── POSIX open / close / errno (libSystem) ────────────────────────────

	/**
	 * {@code int open(const char *path, int flags)}. Returns a new file descriptor on success or -1 on failure; on
	 * failure, {@link #__error} returns the address of the errno value. We bind the 2-argument form only — a
	 * {@code mode_t} third argument is only required when {@code O_CREAT} or {@code O_TMPFILE} is in {@code flags},
	 * neither of which we use.
	 */
	@CFunction(value = "open", transition = Transition.NO_TRANSITION)
	public static native int open(CCharPointer path, int flags);

	/**
	 * {@code int close(int fd)}. Closes a file descriptor.
	 */
	@CFunction(value = "close", transition = Transition.NO_TRANSITION)
	public static native int close(int fd);

	/**
	 * {@code int *__error(void)}. Returns the address of the thread-local errno integer. On Darwin, the {@code errno}
	 * macro in C headers expands to {@code (*__error())}; we mirror that by reading via the returned pointer. Stable
	 * libSystem symbol since Mac OS X 10.0.
	 */
	@CFunction(value = "__error", transition = Transition.NO_TRANSITION)
	public static native CIntPointer __error();

	// ── getattrlist / getattrlistbulk (libSystem) ─────────────────────────

	/**
	 * {@code int getattrlist(const char *path, struct attrlist *alist, void *attrBuf, size_t attrBufSize, unsigned long
	 * options)}. Returns 0 on success, -1 on error. Single-entry version of {@link #getattrlistbulk}; used by the bulk
	 * scanner to read the scan root's device ID once at startup so we can detect mount-point crossings as children are
	 * enumerated.
	 */
	@CFunction(value = "getattrlist", transition = Transition.NO_TRANSITION)
	public static native int getattrlist(
			CCharPointer path, PointerBase alist, PointerBase buf, long bufSize, long options);

	/**
	 * {@code int getattrlistbulk(int dirfd, struct attrlist *alist, void *attrBuf, size_t attrBufSize, uint64_t
	 * options)}. Returns the number of entries packed into {@code attrBuf} (&gt; 0), 0 on end-of-directory, or -1 on
	 * error. Each entry is a variable-length record starting with a {@code uint32 entry_length}; subsequent entries
	 * follow contiguously. With {@link #FSOPT_PACK_INVAL_ATTRS}, the per-entry fixed-attribute area has the same layout
	 * regardless of file vs. directory — see the parser in {@code MacBulkScanner} for the offsets we depend on. Apple
	 * added this syscall in macOS 10.10 (Yosemite); always available on our minimum target (12).
	 */
	@CFunction(value = "getattrlistbulk", transition = Transition.NO_TRANSITION)
	public static native int getattrlistbulk(int dirfd, PointerBase alist, PointerBase buf, long bufSize, long options);

	// ── CoreFoundation ────────────────────────────────────────────────────
	//
	// All CFunctions use Transition.NO_TRANSITION matching Win32.java — see that file's
	// header comment for the optimizer-quirk rationale. The DiskArbitration calls below
	// can in principle XPC-block to diskarbitrationd; we only invoke them at startup
	// when the GC is idle, so blocking the safepoint for a few ms is harmless. If we
	// ever invoke these from inside an active scan loop, revisit and switch to TO_NATIVE
	// (which currently fails to compile on Substrate VM under any control-flow shape, per
	// the same optimizer bug Win32.java documents).

	@CFunction(value = "CFRelease", transition = Transition.NO_TRANSITION)
	public static native void CFRelease(PointerBase cf);

	/**
	 * {@code CFStringRef CFStringCreateWithCString(CFAllocatorRef alloc, const char *cStr, CFStringEncoding encoding)}.
	 * Pass null allocator + {@link #kCFStringEncodingUTF8}; caller must {@link #CFRelease} the result.
	 */
	@CFunction(value = "CFStringCreateWithCString", transition = Transition.NO_TRANSITION)
	public static native PointerBase CFStringCreateWithCString(PointerBase allocator, CCharPointer cStr, int encoding);

	/**
	 * {@code CFURLRef CFURLCreateWithFileSystemPath(CFAllocatorRef, CFStringRef filePath, CFURLPathStyle, Boolean
	 * isDirectory)}. Caller must {@link #CFRelease} the result.
	 */
	@CFunction(value = "CFURLCreateWithFileSystemPath", transition = Transition.NO_TRANSITION)
	public static native PointerBase CFURLCreateWithFileSystemPath(
			PointerBase allocator, PointerBase filePath, int pathStyle, byte isDirectory);

	/**
	 * {@code const void * CFDictionaryGetValue(CFDictionaryRef, const void *key)}. Returns a non-retained borrow that
	 * must <em>not</em> be released. Returns NULL when the key is absent.
	 */
	@CFunction(value = "CFDictionaryGetValue", transition = Transition.NO_TRANSITION)
	public static native PointerBase CFDictionaryGetValue(PointerBase dict, PointerBase key);

	/**
	 * {@code Boolean CFBooleanGetValue(CFBooleanRef boolean)}. {@code Boolean} on macOS is {@code unsigned char}; mask
	 * with {@code & 0xFF} on the Java side if you treat the result as signed.
	 */
	@CFunction(value = "CFBooleanGetValue", transition = Transition.NO_TRANSITION)
	public static native byte CFBooleanGetValue(PointerBase booleanRef);

	/**
	 * {@code Boolean CFStringGetCString(CFStringRef, char *buffer, CFIndex bufferSize, CFStringEncoding encoding)}.
	 * Writes a null-terminated copy of the string into {@code buffer} using the requested encoding. Returns non-zero on
	 * success, zero if the buffer is too small or the encoding is unsupported. Used by {@link #cfStringToJava} to read
	 * short identifier strings ({@code "Solid State"}, {@code "Rotational"}, BSD names) without needing CFString →
	 * byte[] conversion shims.
	 */
	@CFunction(value = "CFStringGetCString", transition = Transition.NO_TRANSITION)
	public static native byte CFStringGetCString(
			PointerBase string, CCharPointer buffer, long bufferSize, int encoding);

	// ── IOKit ─────────────────────────────────────────────────────────────
	//
	// IOKit lets us walk the parent chain of an IOMedia node up to the underlying physical
	// disk, where kIOPropertyDeviceCharacteristicsKey -> kIOPropertyMediumTypeKey ("Solid
	// State" / "Rotational") actually lives. Disk Arbitration's per-volume description does
	// not surface that — for an APFS volume disk like /dev/disk3s5 the SolidState key is
	// absent and we have to traverse via IOServiceGetMatchingService + IORegistryEntrySearchCFProperty
	// to find a parent IOMedia with the property. Same thing `diskutil info` does internally.

	/**
	 * {@code CFMutableDictionaryRef IOBSDNameMatching(mach_port_t mainPort, uint32_t options, const char *bsdName)}.
	 * Builds a matching dictionary keyed on the BSD device name (e.g. {@code "disk3s5"}). The dictionary is
	 * <em>consumed</em> by {@link #IOServiceGetMatchingService}: that call decrements its retain count, so do not
	 * {@code CFRelease} it manually after a successful pass-through.
	 */
	@CFunction(value = "IOBSDNameMatching", transition = Transition.NO_TRANSITION)
	public static native PointerBase IOBSDNameMatching(int mainPort, int options, CCharPointer bsdName);

	/**
	 * {@code io_service_t IOServiceGetMatchingService(mach_port_t mainPort, CFDictionaryRef matching)}. Looks up the
	 * first IOService matching the dictionary; consumes one reference of {@code matching}. Returns 0
	 * ({@code MACH_PORT_NULL}) on no match. The returned port must be released with {@link #IOObjectRelease}.
	 */
	@CFunction(value = "IOServiceGetMatchingService", transition = Transition.NO_TRANSITION)
	public static native int IOServiceGetMatchingService(int mainPort, PointerBase matching);

	/**
	 * {@code CFTypeRef IORegistryEntrySearchCFProperty(io_registry_entry_t entry, const io_name_t plane, CFStringRef
	 * key, CFAllocatorRef allocator, IOOptionBits options)}. Searches {@code entry} and (if requested via
	 * {@code options}) its parents/children for a property with the given key. Pass {@code "IOService"} as the plane
	 * for the standard service plane. Caller owns the returned reference; must {@link #CFRelease} when done.
	 */
	@CFunction(value = "IORegistryEntrySearchCFProperty", transition = Transition.NO_TRANSITION)
	public static native PointerBase IORegistryEntrySearchCFProperty(
			int entry, CCharPointer plane, PointerBase key, PointerBase allocator, int options);

	/**
	 * {@code kern_return_t IOObjectRelease(io_object_t object)}. Releases an IOKit object. Mandatory for every non-zero
	 * {@code io_service_t} returned by {@link #IOServiceGetMatchingService} — these are mach port references, separate
	 * from CoreFoundation retain counts, and {@link #CFRelease} does not work on them.
	 */
	@CFunction(value = "IOObjectRelease", transition = Transition.NO_TRANSITION)
	public static native int IOObjectRelease(int object);

	// ── DiskArbitration ───────────────────────────────────────────────────

	/**
	 * {@code DASessionRef DASessionCreate(CFAllocatorRef allocator)}. A session is a connection to
	 * {@code diskarbitrationd} over XPC; synchronous queries like {@link #DADiskCopyDescription} work without a run
	 * loop or dispatch queue scheduled on the session. Caller must {@link #CFRelease} the result.
	 */
	@CFunction(value = "DASessionCreate", transition = Transition.NO_TRANSITION)
	public static native PointerBase DASessionCreate(PointerBase allocator);

	/**
	 * {@code DADiskRef DADiskCreateFromVolumePath(CFAllocatorRef, DASessionRef, CFURLRef path)}. Resolves a mounted
	 * volume's URL to a DA disk handle. Returns NULL for paths that aren't volume mount points (e.g. a subdirectory of
	 * one). Caller must {@link #CFRelease} the result.
	 */
	@CFunction(value = "DADiskCreateFromVolumePath", transition = Transition.NO_TRANSITION)
	public static native PointerBase DADiskCreateFromVolumePath(
			PointerBase allocator, PointerBase session, PointerBase path);

	/**
	 * {@code CFDictionaryRef DADiskCopyDescription(DADiskRef disk)}. Synchronous XPC query to {@code diskarbitrationd};
	 * returns the same dictionary {@code diskutil info} parses. Caller must {@link #CFRelease} the result. Keys we read
	 * are the {@code kDADiskDescription*} constants, e.g. {@code "DAMediaSolidState"}.
	 */
	@CFunction(value = "DADiskCopyDescription", transition = Transition.NO_TRANSITION)
	public static native PointerBase DADiskCopyDescription(PointerBase disk);

	/**
	 * {@code const char *DADiskGetBSDName(DADiskRef disk)}. Returns the BSD device name (e.g. {@code "disk3s5"}) for a
	 * DA disk handle, or NULL if the disk has no BSD identity. The string is owned by the DA disk; do <em>not</em> free
	 * it. Lifetime is bounded by the DA disk's lifetime, so consume it before {@link #CFRelease}-ing the disk.
	 */
	@CFunction(value = "DADiskGetBSDName", transition = Transition.NO_TRANSITION)
	public static native CCharPointer DADiskGetBSDName(PointerBase disk);

	// ── helpers ───────────────────────────────────────────────────────────

	/**
	 * Allocates an unmanaged UTF-8 buffer for {@code s}, null-terminated, and returns a {@link CCharPointer} pointing
	 * at it. Caller must {@link UnmanagedMemory#free} when done — there is no GC for these.
	 */
	public static CCharPointer allocCString(String s) {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		CCharPointer buf = UnmanagedMemory.malloc(bytes.length + 1);
		for (int i = 0; i < bytes.length; i++) {
			buf.write(i, bytes[i]);
		}
		buf.write(bytes.length, (byte) 0);
		return buf;
	}

	/**
	 * Allocates a {@code CFStringRef} from {@code s} (UTF-8). Equivalent to
	 * {@code CFStringCreateWithCString(NULL, cstr, kCFStringEncodingUTF8)} with the temporary {@code char*} buffer
	 * freed before return. Caller must {@link #CFRelease} the result. Returns a NULL pointer on failure (allocation OOM
	 * in {@code CFString}).
	 */
	public static PointerBase newCFString(String s) {
		CCharPointer cstr = allocCString(s);
		try {
			return CFStringCreateWithCString(org.graalvm.word.WordFactory.nullPointer(), cstr, kCFStringEncodingUTF8);
		} finally {
			UnmanagedMemory.free(cstr);
		}
	}

	/**
	 * Reads a {@code CFStringRef} into a Java {@link String} via {@link #CFStringGetCString}, UTF-8. Returns
	 * {@code null} for a NULL input or on conversion failure (typically a buffer-too-small condition; we use 256 B
	 * which is generous for short identifiers like {@code "Solid State"}, {@code "Rotational"}, or BSD names like
	 * {@code "disk3s5"}). The CFString itself is not retained or released by this method.
	 */
	public static String cfStringToJava(PointerBase cfString) {
		// PointerBase is a GraalVM Word type, not a regular Object — `cfString == null` is
		// a compile-error-equivalent ("Should not compare Word to Object") under Substrate's
		// analyzer. isNull() is the only legal null-check.
		if (cfString.isNull())
			return null;
		final int bufSize = 256;
		CCharPointer buf = UnmanagedMemory.malloc(bufSize);
		try {
			byte ok = CFStringGetCString(cfString, buf, bufSize, kCFStringEncodingUTF8);
			if (ok == 0)
				return null;
			int len = 0;
			while (len < bufSize - 1 && buf.read(len) != 0)
				len++;
			byte[] javaBytes = new byte[len];
			for (int i = 0; i < len; i++)
				javaBytes[i] = buf.read(i);
			return new String(javaBytes, StandardCharsets.UTF_8);
		} finally {
			UnmanagedMemory.free(buf);
		}
	}
}
