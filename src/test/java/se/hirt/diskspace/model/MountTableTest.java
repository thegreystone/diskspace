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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MountTableTest {

	/** A typical Ubuntu desktop: ext4 root, ESP, a second data disk, plus the usual helper and plumbing mounts. */
	private static final List<String> UBUNTU_DESKTOP = List.of(
			"25 30 0:23 / /sys rw,nosuid,nodev,noexec,relatime shared:7 - sysfs sysfs rw",
			"26 30 0:24 / /proc rw,nosuid,nodev,noexec,relatime shared:12 - proc proc rw",
			"27 30 0:5 / /dev rw,nosuid,relatime shared:2 - devtmpfs udev rw,size=32G,nr_inodes=8M,mode=755",
			"29 30 0:26 / /run rw,nosuid,nodev,noexec,relatime shared:5 - tmpfs tmpfs rw,size=6G,mode=755",
			"30 1 259:2 / / rw,relatime shared:1 - ext4 /dev/nvme0n1p2 rw,errors=remount-ro",
			"31 25 0:6 / /sys/kernel/security rw,nosuid,nodev,noexec,relatime shared:8 - securityfs securityfs rw",
			"35 30 7:0 / /snap/core22/1380 ro,nodev,relatime shared:31 - squashfs /dev/loop0 ro,errors=continue",
			"36 30 259:1 / /boot/efi rw,relatime shared:100 - vfat /dev/nvme0n1p1 rw,fmask=0077,dmask=0077",
			"37 30 8:1 / /repositories rw,nosuid,nodev,relatime shared:104 - ext4 /dev/sda1 rw",
			"38 30 0:40 / /tmp rw,nosuid,nodev shared:20 - tmpfs tmpfs rw",
			// The same disk mounted a second time: /repositories is shallower, so it wins.
			"39 30 8:1 / /mnt/repos-again rw,relatime shared:104 - ext4 /dev/sda1 rw",
			// Bind mounts of directories on disks that are already listed.
			"40 30 259:2 /home/me/projects /srv/projects rw,relatime shared:1 - ext4 /dev/nvme0n1p2 rw",
			"41 30 8:1 /git /home/me/git rw,relatime shared:104 - ext4 /dev/sda1 rw",
			"42 30 0:50 / /var/lib/docker/overlay2/abc/merged rw,relatime - overlay overlay rw,lowerdir=/x",
			"43 30 259:2 /var/lib/docker /var/lib/docker/volumes rw shared:1 - ext4 /dev/nvme0n1p2 rw",
			"44 29 0:60 / /run/user/1000/doc rw,nosuid,nodev,relatime shared:500 - fuse.portal portal rw",
			"45 29 0:61 / /run/user/1000/gnome-remote-desktop/cliprdr rw shared:501 - fuse /dev/fuse rw",
			"46 29 8:17 / /run/media/me/USB\\040STICK rw,nosuid,nodev,relatime shared:600 - exfat /dev/sdb1 rw",
			"47 30 8:33 / /mnt/My\\040Disk rw,relatime shared:700 - ntfs3 /dev/sdc1 rw",
			// A running AppImage, and VeraCrypt's helper mount next to the decrypted volume it serves.
			"48 38 0:70 / /tmp/.mount_ObsidiXyZ12 ro,nosuid,nodev,relatime shared:800 - "
					+ "fuse.Obsidian-1.6.7.AppImage Obsidian-1.6.7.AppImage ro,user_id=1000,group_id=1000",
			"49 38 0:71 / /tmp/.veracrypt_aux_mnt1 rw,nosuid,nodev,relatime shared:801 - fuse.veracrypt veracrypt rw",
			"50 30 253:0 / /media/veracrypt1 rw,relatime shared:802 - ext4 /dev/mapper/veracrypt1 rw");

	@Test
	void keepsRealDisksInMountOrder() {
		assertEquals(List.of("/", "/boot/efi", "/repositories", "/run/media/me/USB STICK", "/mnt/My Disk",
				"/media/veracrypt1"), MountTable.parseLinuxMountInfo(UBUNTU_DESKTOP));
	}

	@Test
	void prefersSlashOverAnEarlierMountOfTheSameTree() {
		// Mount order is creation order, so an alias of the root filesystem can come first.
		List<String> lines = List.of("20 1 259:2 / /oldroot rw - ext4 /dev/nvme0n1p2 rw",
				"30 1 259:2 / / rw - ext4 /dev/nvme0n1p2 rw");
		assertEquals(List.of("/"), MountTable.parseLinuxMountInfo(lines));
	}

	@Test
	void prefersTheShallowerMountOfTheSameTree() {
		List<String> lines = List.of("30 1 259:2 / / rw - ext4 /dev/nvme0n1p2 rw",
				"40 30 8:1 / /srv/data/repos rw - ext4 /dev/sda1 rw",
				"41 30 8:1 / /repos rw - ext4 /dev/sda1 rw");
		assertEquals(List.of("/", "/repos"), MountTable.parseLinuxMountInfo(lines));
	}

	@Test
	void keepsBtrfsSubvolumesButNotDirectoryBindsWithinThem() {
		// Subvolumes share the superblock's device number but each has its own st_dev at runtime, so
		// scanning "/" never descends into /home or /var/lib/machines; each needs its own row.
		List<String> lines = List.of(
				"30 1 0:31 /@ / rw,relatime shared:1 - btrfs /dev/nvme0n1p2 rw,ssd,subvolid=256,subvol=/@",
				"31 30 0:31 /@home /home rw,relatime shared:2 - btrfs /dev/nvme0n1p2 rw,ssd,subvolid=257,subvol=/@home",
				"32 30 0:31 /@/var/lib/machines /var/lib/machines rw shared:3 - btrfs /dev/nvme0n1p2 "
						+ "rw,subvolid=262,subvol=/@/var/lib/machines",
				// A bind mount of a directory inside the @home subvolume: already visible via /home.
				"33 31 0:31 /@home/me/data /srv/data rw shared:2 - btrfs /dev/nvme0n1p2 rw,subvolid=257,subvol=/@home",
				// The top-level subvolume (id 5) shows the others only as directories it won't scan into.
				"34 30 0:31 / /mnt/btrfs-top rw shared:4 - btrfs /dev/nvme0n1p2 rw,subvolid=5,subvol=/");
		assertEquals(List.of("/", "/home", "/var/lib/machines", "/mnt/btrfs-top"),
				MountTable.parseLinuxMountInfo(lines));
	}

	@Test
	void fedoraAtomicListsSysrootInsteadOfItsBindMounts() {
		// composefs makes "/" an overlay; /etc and /var are bind mounts of directories in /sysroot.
		String btrfs = " - btrfs /dev/nvme0n1p3 rw,compress=zstd:1,ssd,";
		List<String> lines = List.of(
				"60 1 0:35 / / ro,relatime shared:1 - overlay composefs ro,lowerdir+=/run/ostree/.private/cfsroot-lower",
				"61 60 0:36 /root /sysroot rw,relatime shared:2" + btrfs + "subvolid=256,subvol=/root",
				"62 60 0:36 /root/ostree/deploy/fedora/deploy/0a1b.0/etc /etc rw,relatime shared:3" + btrfs
						+ "subvolid=256,subvol=/root",
				"63 60 0:36 /root/ostree/deploy/fedora/var /var rw,relatime shared:4" + btrfs + "subvolid=256,subvol=/root",
				"64 63 0:36 /home /var/home rw,relatime shared:5" + btrfs + "subvolid=257,subvol=/home",
				"65 60 259:2 / /boot rw,relatime shared:6 - ext4 /dev/nvme0n1p2 rw");
		assertEquals(List.of("/sysroot", "/var/home", "/boot"), MountTable.parseLinuxMountInfo(lines));
	}

	@Test
	void hidesWslPlumbingButNotWindowsDrives() {
		List<String> lines = List.of("80 70 8:48 / / rw,relatime master:1 - ext4 /dev/sdd rw,discard",
				"81 80 0:80 / /usr/lib/wsl/drivers ro,nosuid,nodev,noatime - 9p drivers ro,aname=drivers;fmask=222",
				"82 80 0:81 / /usr/lib/wsl/lib ro - overlay none rw,lowerdir=/gpu_lib_packaged:/gpu_lib_inbox",
				"83 80 8:48 / /mnt/wslg/distro rw,relatime master:1 - ext4 /dev/sdd rw",
				"84 80 0:82 / /mnt/wsl rw,relatime shared:1 - tmpfs none rw",
				"85 80 0:83 / /mnt/c rw,noatime - 9p C:\\134 rw,aname=drvfs;path=C:\\;uid=1000;gid=1000");
		assertEquals(List.of("/", "/mnt/c"), MountTable.parseLinuxMountInfo(lines));
	}

	@Test
	void filtersBeforeDeduplicating() {
		// Mounts in hidden locations must not knock out the visible mount of the same disk...
		List<String> lines = List.of("40 29 8:1 / /run/repos-helper rw - ext4 /dev/sda1 rw",
				"41 30 8:1 / /repositories rw - ext4 /dev/sda1 rw",
				// ...nor make a bind mount redundant when the whole disk is only mounted out of sight.
				"42 29 8:17 / /run/sdb-full rw - ext4 /dev/sdb1 rw",
				"43 30 8:17 /exports/a /srv/a rw - ext4 /dev/sdb1 rw",
				"44 30 8:17 /exports/a/sub /srv/a-sub rw - ext4 /dev/sdb1 rw");
		assertEquals(List.of("/repositories", "/srv/a"), MountTable.parseLinuxMountInfo(lines));
	}

	@Test
	void handlesSeveralOptionalFields() {
		List<String> lines = List.of(
				"50 30 8:49 / /data rw,relatime shared:9 master:3 propagate_from:2 unbindable - xfs /dev/sdd1 rw");
		assertEquals(List.of("/data"), MountTable.parseLinuxMountInfo(lines));
	}

	@Test
	void skipsMalformedLines() {
		List<String> lines = List.of("", "garbage", "1 2 3 4 5 6", "36 30 259:1 / /boot/efi rw - ",
				"37 30 8:1 / /repositories rw,relatime shared:104 - ext4 /dev/sda1 rw");
		assertEquals(List.of("/repositories"), MountTable.parseLinuxMountInfo(lines));
		assertNull(MountTable.parseLine("1 2 3 4 5 - ext4"));
	}

	@Test
	void readsTheBtrfsSubvolumeOption() {
		MountTable.MountInfo m = MountTable.parseLine(
				"31 30 0:31 /@home /home rw - btrfs /dev/nvme0n1p2 rw,ssd,subvolid=257,subvol=/@my\\040home");
		assertEquals("/@my home", m.subvolume());
		assertEquals("/@my home", m.treeRoot());
		assertNull(MountTable.parseLine("37 30 8:1 / /r rw - ext4 /dev/sda1 rw,subvol=/x").subvolume());
	}

	@Test
	void undecodableMountPointIsSkippedWithoutLosingTheRest(@TempDir Path dir) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.writeBytes("30 1 259:2 / / rw - ext4 /dev/nvme0n1p2 rw\n".getBytes(US_ASCII));
		bytes.writeBytes("31 30 8:17 / /mnt/Donn".getBytes(US_ASCII));
		bytes.write(0xE9); // Latin-1 'é', not valid UTF-8 on its own
		bytes.writeBytes("es rw - ext4 /dev/sdb1 rw\n".getBytes(US_ASCII));
		bytes.writeBytes("32 30 8:1 / /repositories rw - ext4 /dev/sda1 rw\n".getBytes(US_ASCII));
		Path file = Files.write(dir.resolve("mountinfo"), bytes.toByteArray());

		assertEquals(List.of("/", "/repositories"),
				MountTable.parseLinuxMountInfo(MountTable.readLines(file, UTF_8)));
		// Under a Latin-1 file-name encoding the same bytes are a perfectly good name.
		assertEquals(List.of("/", "/mnt/Donn\u00e9es", "/repositories"),
				MountTable.parseLinuxMountInfo(MountTable.readLines(file, ISO_8859_1)));
	}

	@Test
	void unescapesOctalSequences() {
		assertEquals("/mnt/My Disk", MountTable.unescape("/mnt/My\\040Disk"));
		assertEquals("/mnt/tab\there", MountTable.unescape("/mnt/tab\\011here"));
		assertEquals("/mnt/back\\slash", MountTable.unescape("/mnt/back\\134slash"));
		assertEquals("/mnt/not\\8escape", MountTable.unescape("/mnt/not\\8escape"));
		assertEquals("/mnt/trailing\\04", MountTable.unescape("/mnt/trailing\\04"));
	}

	@Test
	void hidesPlumbingAndHelperLocationsButNotDisks() {
		assertTrue(MountTable.isHiddenLocation("/proc"));
		assertTrue(MountTable.isHiddenLocation("/sys/fs/cgroup"));
		assertTrue(MountTable.isHiddenLocation("/run/user/1000/gvfs"));
		assertTrue(MountTable.isHiddenLocation("/snap/firefox/1"));
		assertTrue(MountTable.isHiddenLocation("/tmp/.mount_ObsidiXyZ12"));
		assertTrue(MountTable.isHiddenLocation("/home/.zfs/snapshot/daily"));
		assertTrue(MountTable.isHiddenLocation("/run/media/me/.hidden"));
		assertTrue(MountTable.isHiddenLocation("/usr/lib/wsl/drivers"));
		assertTrue(MountTable.isHiddenLocation("/mnt/wslg/distro"));
		assertFalse(MountTable.isHiddenLocation("/run/media/me/USB"));
		assertFalse(MountTable.isHiddenLocation("/mnt/c"));
		assertFalse(MountTable.isHiddenLocation("/mnt/wslgames"));
		assertFalse(MountTable.isHiddenLocation("/snapshots"));
		assertFalse(MountTable.isHiddenLocation("/devel"));
		assertFalse(MountTable.isHiddenLocation("/"));
	}

	@Test
	void macVolumesSkipsHousekeepingEntriesAndSortsLikeFinder(@TempDir Path dir) throws IOException {
		for (String name : List.of("usb", ".timemachine", "com.apple.TimeMachine.localsnapshots", "Macintosh HD",
				"Backup")) {
			Files.createDirectory(dir.resolve(name));
		}
		List<String> names = MountTable.macVolumes(dir).stream().map(p -> p.getFileName().toString()).toList();
		assertEquals(List.of("Backup", "Macintosh HD", "usb"), names);
	}

	@Test
	void macVolumesWithoutTheDirectoryIsEmpty(@TempDir Path dir) throws IOException {
		assertEquals(List.of(), MountTable.macVolumes(dir.resolve("missing")));
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void unixRootsStartWithSlashAndHaveNoDuplicates() {
		List<Path> roots = MountTable.unixRoots();
		assertEquals(Path.of("/"), roots.get(0));
		assertEquals(roots.size(), new HashSet<>(roots).size());
	}
}
