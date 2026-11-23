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
package se.hirt.diskspace.model;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lists the mount points worth offering in the picker on Unix-like systems, where
 * {@link java.nio.file.FileSystem#getRootDirectories()} only ever returns {@code /}.
 * <p>Everything here is cheap and non-blocking: it reads the kernel's mount table (Linux) or lists {@code /Volumes}
 * (macOS) without touching the mounted filesystems. Anything that needs a stat — {@link Volume#isSeparateMount} and
 * the size queries in {@link Volume#resolve} — runs later, on the picker's per-root background threads.
 */
final class MountTable {
	private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(MountTable.class.getName());

	private static final Path LINUX_MOUNTINFO = Path.of("/proc/self/mountinfo");
	static final Path MAC_VOLUMES = Path.of("/Volumes");

	/**
	 * Mount point prefixes that only ever hold kernel, runtime, container or WSL plumbing. WSL2 keeps its GPU driver
	 * store under {@code /usr/lib/wsl} and the WSLg system distro (including a second mount of the root filesystem)
	 * under {@code /mnt/wslg}; the Windows drives at {@code /mnt/c} and so on stay visible.
	 */
	private static final List<String> HIDDEN_PREFIXES = List.of("/proc", "/sys", "/dev", "/run", "/snap", "/var/snap",
			"/var/lib/docker", "/var/lib/containers", "/var/lib/kubelet", "/usr/lib/wsl", "/mnt/wsl", "/mnt/wslg");
	/** Exceptions to {@link #HIDDEN_PREFIXES}: where udisks2 mounts removable media. */
	private static final List<String> VISIBLE_PREFIXES = List.of("/run/media");

	/**
	 * One {@code /proc/self/mountinfo} line, reduced to the fields used here.
	 *
	 * @param device     the filesystem's {@code major:minor}; shared by every mount of it, btrfs subvolumes included
	 * @param root       the directory within the filesystem that is mounted ({@code /} for a whole filesystem)
	 * @param mountPoint where it is mounted
	 * @param fsType     the filesystem type
	 * @param subvolume  on btrfs, the subvolume that {@code root} lies in ({@code subvol=}); otherwise {@code null}
	 */
	record MountInfo(String device, String root, String mountPoint, String fsType, String subvolume) {

		/**
		 * The top of the directory tree this mount shows part or all of: the btrfs subvolume, or the filesystem's own
		 * root. Scanning stops wherever the device number changes, and every btrfs subvolume has its own, so each tree
		 * is a separate disk as far as the picker is concerned.
		 */
		String treeRoot() {
			return subvolume != null ? subvolume : "/";
		}
	}

	private MountTable() {
	}

	/**
	 * The candidate roots for this platform: {@code /} first, then the other mount points. Never empty and never
	 * throws: a mount table that can't be read degrades to just {@code /}, and a single unusable entry is skipped.
	 */
	static List<Path> unixRoots() {
		Set<Path> roots = new LinkedHashSet<>();
		roots.add(Path.of("/"));
		try {
			if (Files.isReadable(LINUX_MOUNTINFO)) {
				for (String mountPoint : parseLinuxMountInfo(readLines(LINUX_MOUNTINFO, fileNameCharset()))) {
					try {
						roots.add(Path.of(mountPoint));
					} catch (InvalidPathException e) {
						LOG.info("Skipping mount point the JVM can't represent: " + e.getMessage());
					}
				}
			} else if (MacVolumeInfo.isMac()) {
				roots.addAll(macVolumes(MAC_VOLUMES));
			}
		} catch (IOException | RuntimeException e) {
			LOG.info("Could not read the mount table, offering only /: " + e);
		}
		return new ArrayList<>(roots);
	}

	/**
	 * Reads the mount table without failing on names that aren't valid in {@code charset}. The kernel prints mount
	 * points as raw bytes, so a name in another encoding decodes to U+FFFD (and is skipped by
	 * {@link #parseLinuxMountInfo}) instead of throwing and losing every other mount.
	 */
	static List<String> readLines(Path file, Charset charset) throws IOException {
		return new String(Files.readAllBytes(file), charset).lines().toList();
	}

	/** The charset the JVM encodes file names with, so a decoded mount point maps back to the same bytes. */
	private static Charset fileNameCharset() {
		String name = System.getProperty("sun.jnu.encoding");
		try {
			return name != null ? Charset.forName(name) : StandardCharsets.UTF_8;
		} catch (IllegalArgumentException e) {
			return StandardCharsets.UTF_8;
		}
	}

	/**
	 * Extracts the mount points worth listing from {@code /proc/self/mountinfo} lines, in mount order.
	 * <p>Line format ({@code proc(5)}):
	 * {@code 36 35 98:0 /mnt1 /mnt2 rw,noatime master:1 - ext3 /dev/root rw,errors=continue} — mount ID, parent ID,
	 * device, root within the filesystem, mount point, mount options, any number of optional fields, a {@code -}
	 * separator, then the filesystem type, the source and the superblock options.
	 * <p>Drops pseudo/virtual filesystems and plumbing locations ({@link #isHiddenLocation}) first, then any mount
	 * whose contents another remaining mount already shows ({@link #isSubsumed}).
	 */
	static List<String> parseLinuxMountInfo(List<String> lines) {
		List<MountInfo> mounts = new ArrayList<>();
		for (String line : lines) {
			MountInfo m = parseLine(line);
			if (m != null && isCandidate(m))
				mounts.add(m);
		}
		List<String> result = new ArrayList<>();
		for (int i = 0; i < mounts.size(); i++) {
			if (!isSubsumed(i, mounts))
				result.add(mounts.get(i).mountPoint());
		}
		return result;
	}

	/** Parses one mountinfo line, or returns {@code null} when it is malformed. */
	static MountInfo parseLine(String line) {
		String[] f = line.trim().split(" ");
		int sep = indexOfSeparator(f);
		if (sep < 0 || sep + 1 >= f.length)
			return null;
		String fsType = f[sep + 1];
		String subvolume = null;
		if ("btrfs".equals(fsType) && sep + 3 < f.length) {
			for (String option : f[sep + 3].split(",")) {
				if (option.startsWith("subvol="))
					subvolume = unescape(option.substring("subvol=".length()));
			}
		}
		return new MountInfo(f[2], unescape(f[3]), unescape(f[4]), fsType, subvolume);
	}

	private static boolean isCandidate(MountInfo m) {
		// U+FFFD: the name isn't valid in the file-name encoding (see readLines), so no Path can reach it.
		return !Volume.isPseudoFs(m.fsType()) && !isHiddenLocation(m.mountPoint())
				&& m.mountPoint().indexOf('�') < 0;
	}

	/**
	 * Whether another candidate already shows everything {@code mounts[i]} shows, within the same filesystem tree
	 * (see {@link MountInfo#treeRoot}):
	 * <ul>
	 * <li>a mount of an enclosing directory — {@code mounts[i]} is a bind mount of part of a disk that is already
	 * listed, such as {@code /repositories/git} bound onto {@code ~/git}, or Fedora Atomic's {@code /etc} and
	 * {@code /var} inside {@code /sysroot};</li>
	 * <li>a mount of the same directory at a better place — {@code /} beats everything, then shallower mount points,
	 * then earlier ones.</li>
	 * </ul>
	 * Both relations always leave at least one mount of every tree in the list.
	 */
	private static boolean isSubsumed(int i, List<MountInfo> mounts) {
		MountInfo m = mounts.get(i);
		for (int j = 0; j < mounts.size(); j++) {
			MountInfo other = mounts.get(j);
			if (j == i || !other.device().equals(m.device()) || !other.treeRoot().equals(m.treeRoot()))
				continue;
			if (isProperAncestor(other.root(), m.root()))
				return true;
			if (other.root().equals(m.root()) && isPreferred(other.mountPoint(), j, m.mountPoint(), i))
				return true;
		}
		return false;
	}

	private static boolean isProperAncestor(String ancestor, String path) {
		if (ancestor.equals(path))
			return false;
		return "/".equals(ancestor) || path.startsWith(ancestor + "/");
	}

	private static boolean isPreferred(String mountPoint, int index, String otherMountPoint, int otherIndex) {
		int depth = depth(mountPoint);
		int otherDepth = depth(otherMountPoint);
		return depth != otherDepth ? depth < otherDepth : index < otherIndex;
	}

	private static int depth(String mountPoint) {
		return "/".equals(mountPoint) ? 0 : (int) mountPoint.chars().filter(c -> c == '/').count();
	}

	/**
	 * External and secondary volumes on macOS: the entries of {@code volumesDir}, minus hidden ones and Apple's own
	 * housekeeping mounts, sorted by name the way Finder sorts them (ignoring case). Only the directory itself is
	 * read — the entries aren't stat'ed, since that would touch each mounted filesystem and can hang on a dead network
	 * share. The boot-volume symlink ({@code Macintosh HD -> /}) and any stale non-mount directory are weeded out
	 * later, off the FX thread, by {@link Volume#isSeparateMount}.
	 */
	static List<Path> macVolumes(Path volumesDir) throws IOException {
		List<Path> result = new ArrayList<>();
		if (!Files.isDirectory(volumesDir))
			return result;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(volumesDir)) {
			for (Path entry : ds) {
				Path name = entry.getFileName();
				if (name == null || name.toString().startsWith(".") || name.toString().startsWith("com.apple."))
					continue;
				result.add(entry);
			}
		}
		result.sort(Comparator.comparing((Path p) -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
		return result;
	}

	/**
	 * Whether a mount point only ever holds helper mounts rather than disks: kernel, runtime, container and WSL
	 * plumbing ({@link #HIDDEN_PREFIXES}), and anything inside a hidden directory — AppImages
	 * ({@code /tmp/.mount_*}), VeraCrypt's auxiliary mount ({@code /tmp/.veracrypt_aux_mnt*}), ZFS snapshots
	 * ({@code .zfs/snapshot/*}) and the like.
	 */
	static boolean isHiddenLocation(String mountPoint) {
		if (mountPoint.contains("/."))
			return true;
		for (String visible : VISIBLE_PREFIXES) {
			if (isAtOrUnder(mountPoint, visible))
				return false;
		}
		for (String hidden : HIDDEN_PREFIXES) {
			if (isAtOrUnder(mountPoint, hidden))
				return true;
		}
		return false;
	}

	private static boolean isAtOrUnder(String path, String prefix) {
		return path.equals(prefix) || path.startsWith(prefix + "/");
	}

	/**
	 * Index of the {@code -} that ends the optional fields, or -1. The six fixed fields come first, so the search
	 * starts at index 6 — a {@code -} can't be mistaken for anything earlier.
	 */
	private static int indexOfSeparator(String[] fields) {
		for (int i = 6; i < fields.length; i++) {
			if (fields[i].equals("-"))
				return i;
		}
		return -1;
	}

	/** Decodes the kernel's octal escapes for space, tab, newline and backslash ({@code \040} and friends). */
	static String unescape(String s) {
		if (s.indexOf('\\') < 0)
			return s;
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' && isOctal(s, i + 1)) {
				sb.append((char) Integer.parseInt(s.substring(i + 1, i + 4), 8));
				i += 3;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static boolean isOctal(String s, int from) {
		if (from + 3 > s.length())
			return false;
		for (int i = from; i < from + 3; i++) {
			char c = s.charAt(i);
			if (c < '0' || c > '7')
				return false;
		}
		return true;
	}
}
