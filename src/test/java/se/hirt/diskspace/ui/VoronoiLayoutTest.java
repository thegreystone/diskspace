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
package se.hirt.diskspace.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for VoronoiLayout convergence using the complete Macintosh HD directory listing from a real scan
 * (531.1 GB, /Volume/Data).
 * <p>
 * The full table — including Hidden, all nine 0-byte entries, and a free-space estimate — matches exactly what the app
 * feeds to the layout engine. Zero-byte entries are filtered out before calling {@link VoronoiLayout#compute} because
 * the app does the same in {@code VoronoiVisualization.buildTopLevelTreemapItems}.
 * <p>
 * The required assertion: every directory with a non-zero byte count that appears in the top 7 of the table must
 * produce a cell with area ≥ {@link #MIN_VISIBLE_AREA}.
 */
class VoronoiLayoutTest {

	/** Cell area floor below which a directory is considered invisible in the visualization. */
	private static final double MIN_VISIBLE_AREA = 50.0;

	private static final long MB = 1_000_000L;
	private static final long KB = 1_000L;

	/**
	 * Full table from the screenshot, top-to-bottom, including all zero-byte entries and Hidden. Free space is
	 * approximated from the volume capacity (531.1 GB) minus the sum of all scanned directories.
	 */
	private static final Object[][] TABLE = {
			// name,                          bytes
			{"Users", 356_100 * MB}, {"Library", 53_200 * MB}, {"Applications", 48_100 * MB}, {"System", 37_800 * MB},
			{"private", 6_800 * MB}, {"opt", 5_500 * MB}, {"usr", 685 * MB}, {".PreviousSystemInformation", 4 * MB},
			{"MobileSoftwareUpdate", 130 * KB}, {"sw", 0L}, {".Spotlight-V100", 0L}, {"mnt", 0L}, {".fseventsd", 0L},
			{".DocumentRevisions-V100", 0L}, {"Volumes", 0L}, {".TemporaryItems", 0L}, {"cores", 0L}, {"pkg", 0L},
			{"Hidden", 23_100 * MB}, {"Free", 16_000 * MB}, // 531.1 GB capacity - ~515 GB used
	};

	/** Names of the top-7 directories that must be visible after layout. */
	private static final String[] MUST_BE_VISIBLE = {"Users", "Library", "Applications", "System", "private", "opt",
			"usr"};

	@Test
	void top7DirectoriesMustHaveVisibleCells() {
		double cx = 450, cy = 450, radius = 430;
		List<VoronoiLayout.Pt> bounds = VoronoiLayout.approximateDisk(cx, cy, radius, 64);

		// Mirror what VoronoiVisualization does: exclude zero-byte entries, then sort
		// descending by bytes (computeLayout sorts before calling VoronoiLayout.compute).
		List<Object[]> filtered = new ArrayList<>();
		for (Object[] row : TABLE) {
			if ((long) row[1] > 0)
				filtered.add(row);
		}
		filtered.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));

		List<String> names = new ArrayList<>();
		List<Double> weightList = new ArrayList<>();
		for (Object[] row : filtered) {
			names.add((String) row[0]);
			weightList.add((double) (long) row[1]);
		}

		double[] weights = new double[weightList.size()];
		for (int i = 0; i < weights.length; i++)
			weights[i] = weightList.get(i);

		List<VoronoiLayout.Cell> cells = VoronoiLayout.compute(bounds, weights);
		assertEquals(weights.length, cells.size(), "cell count must match filtered input count");

		double diskArea = VoronoiLayout.polygonArea(bounds);
		double totalWeight = 0;
		for (double w : weights)
			totalWeight += w;

		// Build a name→area map for the assertion report.
		StringBuilder report = new StringBuilder("Voronoi cell areas (zero-byte entries excluded):\n");
		java.util.Map<String, Double> areaByName = new java.util.HashMap<>();
		for (int i = 0; i < cells.size(); i++) {
			double area = VoronoiLayout.polygonArea(cells.get(i).polygon());
			double target = diskArea * weights[i] / totalWeight;
			double ratio = target > 0 ? area / target : 0;
			String name = names.get(i);
			areaByName.put(name, area);
			report.append(String.format("  %-35s  bytes=%s  area=%8.1f  target=%8.1f  ratio=%.2f%n", name,
					humanSize((long) weights[i]), area, target, ratio));
		}

		boolean allVisible = true;
		for (String name : MUST_BE_VISIBLE) {
			double area = areaByName.getOrDefault(name, 0.0);
			if (area < MIN_VISIBLE_AREA) {
				allVisible = false;
				report.append("  FAIL: ").append(name).append(" area=").append(area).append(" < ")
						.append(MIN_VISIBLE_AREA).append("\n");
			}
		}

		assertTrue(allVisible, "All top-7 directories must have cell area >= " + MIN_VISIBLE_AREA + " px²\n" + report);
	}

	@Test
	void cellAreasSumToApproximatelyDiskArea() {
		double cx = 450, cy = 450, radius = 430;
		List<VoronoiLayout.Pt> bounds = VoronoiLayout.approximateDisk(cx, cy, radius, 64);

		List<Object[]> filteredSum = new ArrayList<>();
		for (Object[] row : TABLE) {
			if ((long) row[1] > 0)
				filteredSum.add(row);
		}
		filteredSum.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));

		double[] weights = new double[filteredSum.size()];
		for (int i = 0; i < weights.length; i++)
			weights[i] = (long) filteredSum.get(i)[1];

		List<VoronoiLayout.Cell> cells = VoronoiLayout.compute(bounds, weights);
		double diskArea = VoronoiLayout.polygonArea(bounds);
		double cellSum = 0;
		for (VoronoiLayout.Cell c : cells)
			cellSum += VoronoiLayout.polygonArea(c.polygon());

		assertEquals(diskArea, cellSum, diskArea * 0.01, "Sum of cell areas must be within 1% of the total disk area");
	}

	/**
	 * Verifies relative size accuracy for the full table.
	 * <p>
	 * Items with at least 1 MB of data must have cells within 50% of their proportional target (ratio in [0.5, 1.5]).
	 * Smaller items (MobileSoftwareUpdate at 130 KB, .PreviousSystemInformation at 4 MB) are excluded from the accuracy
	 * check — they sit below or near the minimum-floor threshold and are deliberately rendered at a boosted minimum
	 * size so they remain visible; their exact proportionality is sacrificed for stability.
	 */
	@Test
	void relativeSizesMustBeApproximatelyCorrect() {
		double cx = 450, cy = 450, radius = 430;
		List<VoronoiLayout.Pt> bounds = VoronoiLayout.approximateDisk(cx, cy, radius, 64);

		List<Object[]> filtered2 = new ArrayList<>();
		for (Object[] row : TABLE) {
			if ((long) row[1] > 0)
				filtered2.add(row);
		}
		filtered2.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));

		List<String> names = new ArrayList<>();
		List<Long> bytesList = new ArrayList<>();
		List<Double> weightList = new ArrayList<>();
		for (Object[] row : filtered2) {
			names.add((String) row[0]);
			bytesList.add((long) row[1]);
			weightList.add((double) (long) row[1]);
		}
		double[] weights = new double[weightList.size()];
		for (int i = 0; i < weights.length; i++)
			weights[i] = weightList.get(i);

		List<VoronoiLayout.Cell> cells = VoronoiLayout.compute(bounds, weights);
		double diskArea = VoronoiLayout.polygonArea(bounds);
		double totalWeight = 0;
		for (double w : weights)
			totalWeight += w;
		double equalShare = diskArea / weights.length;
		double floorTarget = equalShare * 0.05;

		StringBuilder report = new StringBuilder("Relative size accuracy (full table, zeros excluded):\n");
		List<String> badRatios = new ArrayList<>();

		for (int i = 0; i < cells.size(); i++) {
			double area = VoronoiLayout.polygonArea(cells.get(i).polygon());
			double target = diskArea * weights[i] / totalWeight;
			double ratio = target > 0 ? area / target : 0;
			boolean floored = target < floorTarget;
			report.append(String.format("  %-35s  %s  target=%8.1f  area=%8.1f  ratio=%.2f%s%n", names.get(i),
					humanSize(bytesList.get(i)), target, area, ratio, floored ? "  [floor]" : ""));
			// Accuracy assertion: items >= 1 MB that are NOT floor-boosted must be close.
			if (!floored && bytesList.get(i) >= MB && (ratio < 0.5 || ratio > 1.5)) {
				badRatios.add(
						String.format("%s ratio=%.2f (target=%.1f area=%.1f)", names.get(i), ratio, target, area));
			}
		}

		System.out.println(report);
		assertTrue(badRatios.isEmpty(),
				"Items >= 1 MB not affected by the floor must have ratio in [0.5, 1.5]:\n" + String.join("\n",
						badRatios) + "\n" + report);
	}

	@Test
	void zeroByteDirsMustNotAppearInLayout() {
		double cx = 450, cy = 450, radius = 430;
		List<VoronoiLayout.Pt> bounds = VoronoiLayout.approximateDisk(cx, cy, radius, 64);

		int totalCount = TABLE.length;
		int zeroBytesCount = 0;
		List<Double> weightList = new ArrayList<>();
		for (Object[] row : TABLE) {
			long bytes = (long) row[1];
			if (bytes == 0)
				zeroBytesCount++;
			else
				weightList.add((double) bytes);
		}

		double[] weights = new double[weightList.size()];
		for (int i = 0; i < weights.length; i++)
			weights[i] = weightList.get(i);

		List<VoronoiLayout.Cell> cells = VoronoiLayout.compute(bounds, weights);

		assertEquals(totalCount - zeroBytesCount, cells.size(),
				"Layout must contain exactly the non-zero-byte entries");
	}

	private static String humanSize(long bytes) {
		if (bytes >= 1_000_000_000L)
			return String.format("%.1f GB", bytes / 1e9);
		if (bytes >= 1_000_000L)
			return String.format("%.0f MB", bytes / 1e6);
		if (bytes >= 1_000L)
			return String.format("%.0f KB", bytes / 1e3);
		return bytes + " B";
	}
}
