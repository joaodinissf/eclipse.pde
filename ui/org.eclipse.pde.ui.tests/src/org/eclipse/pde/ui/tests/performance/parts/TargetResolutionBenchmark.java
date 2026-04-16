/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Joao Dinis Ferreira - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.ui.tests.performance.parts;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetLocation;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.eclipse.pde.internal.core.PDECore;
import org.eclipse.pde.internal.core.target.IUBundleContainer;
import org.eclipse.pde.internal.core.target.P2TargetUtils;
import org.eclipse.pde.ui.tests.target.AbstractTargetTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Benchmarks for Eclipse PDE target platform resolution pipeline.
 * <p>
 * Measures wall-clock time for each phase of target resolution to identify
 * bottlenecks and track improvements. Uses the bundled test P2 repository
 * (site.a.b) to avoid network variability.
 * <p>
 * Run these tests within Eclipse and grep the console output for
 * {@code [BENCH]} lines to extract timing data:
 *
 * <pre>
 * [BENCH] phase_name | iterations=N | total_ms=X | avg_ms=Y | min_ms=Z | max_ms=W
 * </pre>
 */
public class TargetResolutionBenchmark extends AbstractTargetTest {

	private static final int WARMUP_ITERATIONS = 3;
	private static final int BENCHMARK_ITERATIONS = 10;

	private ITargetPlatformService tps;
	private URI siteURI;

	@Before
	public void setUp() throws Exception {
		super.setUp();
		tps = PDECore.getDefault().acquireService(ITargetPlatformService.class);
		assertNotNull("ITargetPlatformService not available", tps);
		siteURI = getTestSiteURI();
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}

	// -------------------------------------------------------------------------
	// Benchmark: Full target resolution (directory-based, no P2)
	// -------------------------------------------------------------------------

	@Test
	public void benchmarkDirectoryResolution() throws Exception {
		Path testBundles = TargetPlatformPerfTest.extractTargetPerfTestPlugins();

		// Warmup
		for (int i = 0; i < WARMUP_ITERATIONS; i++) {
			ITargetDefinition target = tps.newTarget();
			target.setTargetLocations(new ITargetLocation[] { tps.newDirectoryLocation(testBundles.toString()) });
			target.resolve(new NullProgressMonitor());
		}

		// Benchmark
		long[] times = new long[BENCHMARK_ITERATIONS];
		for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
			ITargetDefinition target = tps.newTarget();
			target.setTargetLocations(new ITargetLocation[] { tps.newDirectoryLocation(testBundles.toString()) });
			long start = System.nanoTime();
			target.resolve(new NullProgressMonitor());
			times[i] = System.nanoTime() - start;
			assertTrue("Resolution should succeed", target.isResolved());
		}

		reportTiming("directory_resolution_1000_bundles", times);
	}

	// -------------------------------------------------------------------------
	// Benchmark: Full P2 target resolution (local repo)
	// -------------------------------------------------------------------------

	@Test
	public void benchmarkP2Resolution() throws Exception {
		IInstallableUnit[] allUnits = getAllUnitsFromSite();
		assertTrue("Test site should have IUs", allUnits.length > 0);

		// Warmup
		for (int i = 0; i < WARMUP_ITERATIONS; i++) {
			resolveP2Target(allUnits);
		}

		// Benchmark
		long[] times = new long[BENCHMARK_ITERATIONS];
		for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
			long start = System.nanoTime();
			resolveP2Target(allUnits);
			times[i] = System.nanoTime() - start;
		}

		reportTiming("p2_resolution_local_repo", times);
	}

	// -------------------------------------------------------------------------
	// Benchmark: Metadata repository loading (the serial bottleneck)
	// -------------------------------------------------------------------------

	@Test
	public void benchmarkMetadataRepoLoading() throws Exception {
		IMetadataRepositoryManager manager = P2TargetUtils.getRepoManager();
		assertNotNull("Metadata repo manager not available", manager);

		// Warmup
		for (int i = 0; i < WARMUP_ITERATIONS; i++) {
			manager.loadRepository(siteURI, new NullProgressMonitor());
		}

		// Benchmark single repo load
		long[] times = new long[BENCHMARK_ITERATIONS];
		for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
			// Force refresh to avoid cached result
			manager.removeRepository(siteURI);
			long start = System.nanoTime();
			manager.loadRepository(siteURI, new NullProgressMonitor());
			times[i] = System.nanoTime() - start;
		}

		reportTiming("metadata_repo_load_single", times);
	}

	// -------------------------------------------------------------------------
	// Benchmark: Multiple repo loads (simulates serial bottleneck)
	// This measures what parallel loading would improve.
	// -------------------------------------------------------------------------

	@Test
	public void benchmarkMultipleRepoLoadsSerial() throws Exception {
		// Create N copies of the test repo to simulate multiple repos
		int repoCount = 5;
		List<URI> repoURIs = createMultipleLocalRepos(repoCount);

		IMetadataRepositoryManager manager = P2TargetUtils.getRepoManager();

		// Warmup
		for (int w = 0; w < WARMUP_ITERATIONS; w++) {
			for (URI uri : repoURIs) {
				manager.loadRepository(uri, new NullProgressMonitor());
			}
		}

		// Benchmark serial loading of N repos
		long[] times = new long[BENCHMARK_ITERATIONS];
		for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
			for (URI uri : repoURIs) {
				manager.removeRepository(uri);
			}
			long start = System.nanoTime();
			for (URI uri : repoURIs) {
				manager.loadRepository(uri, new NullProgressMonitor());
			}
			times[i] = System.nanoTime() - start;
		}

		reportTiming("metadata_repo_load_serial_" + repoCount + "_repos", times);
	}

	// -------------------------------------------------------------------------
	// Benchmark: isResolved() check (was O(n), now O(1) with cache)
	// -------------------------------------------------------------------------

	@Test
	public void benchmarkIsResolved() throws Exception {
		Path testBundles = TargetPlatformPerfTest.extractTargetPerfTestPlugins();

		ITargetDefinition target = tps.newTarget();
		target.setTargetLocations(new ITargetLocation[] { tps.newDirectoryLocation(testBundles.toString()) });
		target.resolve(new NullProgressMonitor());
		assertTrue("Resolution should succeed", target.isResolved());

		int iterations = 100_000;
		// Warmup
		for (int i = 0; i < 10_000; i++) {
			target.isResolved();
		}

		// Benchmark
		long start = System.nanoTime();
		for (int i = 0; i < iterations; i++) {
			target.isResolved();
		}
		long elapsed = System.nanoTime() - start;

		System.out.printf("[BENCH] is_resolved_check | iterations=%d | total_ms=%.2f | avg_ns=%.0f%n",
				iterations,
				elapsed / 1_000_000.0,
				(double) elapsed / iterations);
	}

	// -------------------------------------------------------------------------
	// Benchmark: P2 resolution with multiple IU containers
	// Measures the overhead of the synchronizer grouping in resolve()
	// -------------------------------------------------------------------------

	@Test
	public void benchmarkMultipleIUContainers() throws Exception {
		IMetadataRepository repo = getRepository(siteURI);
		IInstallableUnit bundleA1 = getUnit("bundle.a1", repo);
		IInstallableUnit bundleB1 = getUnit("bundle.b1", repo);
		assertNotNull(bundleA1);
		assertNotNull(bundleB1);

		// Warmup
		for (int i = 0; i < WARMUP_ITERATIONS; i++) {
			ITargetDefinition target = tps.newTarget();
			ITargetLocation loc1 = tps.newIULocation(
					new IInstallableUnit[] { bundleA1 }, new URI[] { siteURI },
					IUBundleContainer.INCLUDE_REQUIRED);
			ITargetLocation loc2 = tps.newIULocation(
					new IInstallableUnit[] { bundleB1 }, new URI[] { siteURI },
					IUBundleContainer.INCLUDE_REQUIRED);
			target.setTargetLocations(new ITargetLocation[] { loc1, loc2 });
			target.resolve(new NullProgressMonitor());
		}

		// Benchmark
		long[] times = new long[BENCHMARK_ITERATIONS];
		for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
			ITargetDefinition target = tps.newTarget();
			ITargetLocation loc1 = tps.newIULocation(
					new IInstallableUnit[] { bundleA1 }, new URI[] { siteURI },
					IUBundleContainer.INCLUDE_REQUIRED);
			ITargetLocation loc2 = tps.newIULocation(
					new IInstallableUnit[] { bundleB1 }, new URI[] { siteURI },
					IUBundleContainer.INCLUDE_REQUIRED);
			target.setTargetLocations(new ITargetLocation[] { loc1, loc2 });

			long start = System.nanoTime();
			target.resolve(new NullProgressMonitor());
			times[i] = System.nanoTime() - start;
			assertTrue("Resolution should succeed", target.isResolved());
		}

		reportTiming("p2_resolution_2_iu_containers", times);
	}

	// -------------------------------------------------------------------------
	// Benchmark: Mixed target (directory + P2)
	// -------------------------------------------------------------------------

	@Test
	public void benchmarkMixedResolution() throws Exception {
		Path testBundles = TargetPlatformPerfTest.extractTargetPerfTestPlugins();
		IInstallableUnit[] allUnits = getAllUnitsFromSite();

		// Warmup
		for (int i = 0; i < WARMUP_ITERATIONS; i++) {
			ITargetDefinition target = tps.newTarget();
			ITargetLocation dirLoc = tps.newDirectoryLocation(testBundles.toString());
			ITargetLocation iuLoc = tps.newIULocation(allUnits, new URI[] { siteURI },
					IUBundleContainer.INCLUDE_REQUIRED);
			target.setTargetLocations(new ITargetLocation[] { dirLoc, iuLoc });
			target.resolve(new NullProgressMonitor());
		}

		// Benchmark
		long[] times = new long[BENCHMARK_ITERATIONS];
		for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
			ITargetDefinition target = tps.newTarget();
			ITargetLocation dirLoc = tps.newDirectoryLocation(testBundles.toString());
			ITargetLocation iuLoc = tps.newIULocation(allUnits, new URI[] { siteURI },
					IUBundleContainer.INCLUDE_REQUIRED);
			target.setTargetLocations(new ITargetLocation[] { dirLoc, iuLoc });

			long start = System.nanoTime();
			target.resolve(new NullProgressMonitor());
			times[i] = System.nanoTime() - start;
			assertTrue("Resolution should succeed", target.isResolved());
		}

		reportTiming("mixed_resolution_dir_plus_p2", times);
	}

	// =========================================================================
	// Helper methods
	// =========================================================================

	private static URI getTestSiteURI() throws Exception {
		return org.eclipse.pde.ui.tests.target.IUBundleContainerTests.getURI("/tests/sites/site.a.b");
	}

	private IMetadataRepository getRepository(URI uri) throws Exception {
		IMetadataRepositoryManager manager = P2TargetUtils.getRepoManager();
		assertNotNull("Missing metadata repository manager", manager);
		return manager.loadRepository(uri, null);
	}

	private IInstallableUnit getUnit(String id, IMetadataRepository repository) {
		return repository.query(QueryUtil.createIUQuery(id), null)
				.toUnmodifiableSet().stream().findFirst().orElse(null);
	}

	private IInstallableUnit[] getAllUnitsFromSite() throws Exception {
		IMetadataRepository repo = getRepository(siteURI);
		return repo.query(QueryUtil.createIUGroupQuery(), null)
				.toArray(IInstallableUnit.class);
	}

	private void resolveP2Target(IInstallableUnit[] units) throws Exception {
		ITargetDefinition target = tps.newTarget();
		ITargetLocation location = tps.newIULocation(units, new URI[] { siteURI },
				IUBundleContainer.INCLUDE_REQUIRED);
		target.setTargetLocations(new ITargetLocation[] { location });
		target.resolve(new NullProgressMonitor());
	}

	/**
	 * Creates N copies of the test P2 repo in temp directories by symlinking.
	 * This simulates having multiple distinct P2 repos for serial-vs-parallel
	 * loading benchmarks.
	 */
	private List<URI> createMultipleLocalRepos(int count) throws Exception {
		Path sourceSite = Path.of(siteURI);
		List<URI> uris = new ArrayList<>();
		uris.add(siteURI);
		for (int i = 1; i < count; i++) {
			Path copy = Files.createTempDirectory("p2-bench-repo-" + i);
			copy.toFile().deleteOnExit();
			// Copy the repo structure
			for (String name : new String[] { "content.jar", "artifacts.jar" }) {
				Path src = sourceSite.resolve(name);
				if (Files.exists(src)) {
					Files.copy(src, copy.resolve(name));
				}
			}
			Path pluginsDir = copy.resolve("plugins");
			Files.createDirectories(pluginsDir);
			Path sourcePlugins = sourceSite.resolve("plugins");
			if (Files.isDirectory(sourcePlugins)) {
				try (var stream = Files.list(sourcePlugins)) {
					stream.forEach(p -> {
						try {
							Files.copy(p, pluginsDir.resolve(p.getFileName()));
						} catch (Exception e) {
							throw new RuntimeException(e);
						}
					});
				}
			}
			Path featuresDir = copy.resolve("features");
			Files.createDirectories(featuresDir);
			Path sourceFeatures = sourceSite.resolve("features");
			if (Files.isDirectory(sourceFeatures)) {
				try (var stream = Files.list(sourceFeatures)) {
					stream.forEach(p -> {
						try {
							Files.copy(p, featuresDir.resolve(p.getFileName()));
						} catch (Exception e) {
							throw new RuntimeException(e);
						}
					});
				}
			}
			uris.add(copy.toUri());
		}
		return uris;
	}

	// =========================================================================
	// Reporting
	// =========================================================================

	private static void reportTiming(String phaseName, long[] timesNanos) {
		long total = 0, min = Long.MAX_VALUE, max = Long.MIN_VALUE;
		for (long t : timesNanos) {
			total += t;
			min = Math.min(min, t);
			max = Math.max(max, t);
		}
		double totalMs = total / 1_000_000.0;
		double avgMs = totalMs / timesNanos.length;
		double minMs = min / 1_000_000.0;
		double maxMs = max / 1_000_000.0;

		// Compute median
		long[] sorted = timesNanos.clone();
		java.util.Arrays.sort(sorted);
		double medianMs;
		int n = sorted.length;
		if (n % 2 == 0) {
			medianMs = (sorted[n / 2 - 1] + sorted[n / 2]) / 2_000_000.0;
		} else {
			medianMs = sorted[n / 2] / 1_000_000.0;
		}

		System.out.printf(
				"[BENCH] %-45s | iterations=%d | avg_ms=%8.2f | median_ms=%8.2f | min_ms=%8.2f | max_ms=%8.2f | total_ms=%8.2f%n",
				phaseName, timesNanos.length, avgMs, medianMs, minMs, maxMs, totalMs);
	}
}
