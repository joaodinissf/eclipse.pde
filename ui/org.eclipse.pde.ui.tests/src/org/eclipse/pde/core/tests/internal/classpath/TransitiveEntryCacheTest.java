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
 *     Contributors to the Eclipse Foundation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.core.tests.internal.classpath;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.eclipse.pde.core.project.IBundleProjectDescription;
import org.eclipse.pde.core.project.IBundleProjectService;
import org.eclipse.pde.core.project.IRequiredBundleDescription;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetLocation;
import org.eclipse.pde.core.target.ITargetPlatformService;
import org.eclipse.pde.internal.core.ClasspathComputer;
import org.eclipse.pde.internal.core.PDECore;
import org.eclipse.pde.ui.tests.runtime.TestUtils;
import org.eclipse.pde.ui.tests.util.ProjectUtils;
import org.eclipse.pde.ui.tests.util.TargetPlatformUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;

/**
 * Guards for the per-bundle transitive classpath entry cache (Lever 3):
 * <ol>
 * <li>equivalence - entries served from the cache are element-wise equal
 * (path, access rules, source attachment, extra attributes) to freshly
 * constructed ones;</li>
 * <li>eviction - after a target/state change (State.getTimeStamp bump) the
 * cache does not serve stale entries: the recomputed transitive entry reflects
 * the change (here, the bundle's new install location).</li>
 * </ol>
 * Both are green with or without the cache; they fail if the cache serves
 * inconsistent or stale entries.
 */
public class TransitiveEntryCacheTest {

	private File targetDirA;
	private File targetDirB;
	private ITargetDefinition savedTarget;

	@BeforeEach
	public void setUp() throws Exception {
		savedTarget = TargetPlatformUtil.TPS.getWorkspaceTargetDefinition();
		IWorkspaceDescription desc = ResourcesPlugin.getWorkspace().getDescription();
		desc.setAutoBuilding(false);
		ResourcesPlugin.getWorkspace().setDescription(desc);
		ProjectUtils.deleteAllWorkspaceProjects();
		targetDirA = Files.createTempDirectory("pde_cache_a").toFile();
		targetDirB = Files.createTempDirectory("pde_cache_b").toFile();
	}

	@AfterEach
	public void tearDown() throws Exception {
		ProjectUtils.deleteAllWorkspaceProjects();
		IWorkspaceDescription desc = ResourcesPlugin.getWorkspace().getDescription();
		desc.setAutoBuilding(true);
		ResourcesPlugin.getWorkspace().setDescription(desc);
		for (File dir : new File[] { targetDirA, targetDirB }) {
			if (dir != null && dir.exists()) {
				Files.walk(dir.toPath()).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		if (savedTarget != null && savedTarget != TargetPlatformUtil.TPS.getWorkspaceTargetDefinition()) {
			TargetPlatformUtil.loadAndSetTarget(savedTarget);
		}
	}

	@Test
	public void testCachedEntriesEqualFreshEntries() throws Exception {
		// C is a leaf; B requires C privately, so for a consumer requiring B the
		// bundle C is a transitive-forbidden dependency (the cached path).
		createGraph(targetDirA);
		loadTarget(targetDirA);
		IProject consumer = createConsumer("EqConsumer");
		buildAndSettle("cache equivalence setup");

		IPluginModelBase model = PluginRegistry.findModel(consumer);
		assertThat(model).as("PDE model for consumer").isNotNull();
		IClasspathEntry[] first = ClasspathComputer.computeClasspathEntries(model, consumer);
		// Second computation for the same bundle/state hits the cache for C.
		IClasspathEntry[] second = ClasspathComputer.computeClasspathEntries(model, consumer);

		assertThat(second).as("cached recompute must be element-wise identical and in the same order")
				.containsExactly(first);
		IClasspathEntry cFirst = entryFor(first, "C.jar");
		IClasspathEntry cSecond = entryFor(second, "C.jar");
		assertThat(cFirst).as("transitive bundle C must be on the classpath").isNotNull();
		assertThat(cSecond.getPath()).isEqualTo(cFirst.getPath());
		assertThat(cSecond.getAccessRules()).isEqualTo(cFirst.getAccessRules());
		assertThat(cSecond.getExtraAttributes()).isEqualTo(cFirst.getExtraAttributes());
		assertThat(cSecond.getSourceAttachmentPath()).isEqualTo(cFirst.getSourceAttachmentPath());
		assertThat(cSecond).isEqualTo(cFirst);
	}

	@Test
	public void testStateChangeEvictsStaleEntries() throws Exception {
		createGraph(targetDirA);
		loadTarget(targetDirA);
		IProject consumer = createConsumer("EvConsumer");
		buildAndSettle("cache eviction setup");

		IPluginModelBase model = PluginRegistry.findModel(consumer);
		IClasspathEntry[] before = ClasspathComputer.computeClasspathEntries(model, consumer);
		IClasspathEntry cBefore = entryFor(before, "C.jar");
		assertThat(cBefore).as("transitive bundle C must be on the classpath").isNotNull();
		assertThat(cBefore.getPath().toString()).contains(targetDirA.getName());

		// Same bundles, different location + fresh resolve => State.getTimeStamp
		// bumps. A cache that ignored the state change would serve C's stale
		// entry pointing into targetDirA.
		createGraph(targetDirB);
		loadTarget(targetDirB);
		buildAndSettle("cache eviction reload");

		IPluginModelBase model2 = PluginRegistry.findModel(consumer);
		IClasspathEntry[] after = ClasspathComputer.computeClasspathEntries(model2, consumer);
		IClasspathEntry cAfter = entryFor(after, "C.jar");
		assertThat(cAfter).as("transitive bundle C must still be on the classpath after reload").isNotNull();
		assertThat(cAfter.getPath().toString())
				.as("recomputed C entry must reflect the new target location, not the stale cached one")
				.contains(targetDirB.getName());
		assertThat(cAfter.getPath()).isNotEqualTo(cBefore.getPath());
	}

	private void createGraph(File dir) throws IOException {
		createBundle(dir, "C", "c", null); // leaf
		createBundle(dir, "B", "b", "C"); // private require on C
	}

	private void loadTarget(File dir) throws Exception {
		ITargetPlatformService tps = PDECore.getDefault().acquireService(ITargetPlatformService.class);
		ITargetDefinition target = tps.newTarget();
		target.setTargetLocations(new ITargetLocation[] { tps.newDirectoryLocation(dir.getAbsolutePath()) });
		TargetPlatformUtil.loadAndSetTarget(target);
	}

	private IProject createConsumer(String name) throws Exception {
		IBundleProjectService service = PDECore.getDefault().acquireService(IBundleProjectService.class);
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
		project.create(null);
		project.open(null);
		IBundleProjectDescription description = service.getDescription(project);
		description.setSymbolicName(name);
		description.setBundleVersion(new Version("1.0.0"));
		description.setRequiredBundles(new IRequiredBundleDescription[] {
				service.newRequiredBundle("B", (VersionRange) null, false, false) });
		description.setNatureIds(new String[] { JavaCore.NATURE_ID, IBundleProjectDescription.PLUGIN_NATURE });
		description.apply(null);
		return project;
	}

	private static void buildAndSettle(String context) throws Exception {
		ResourcesPlugin.getWorkspace().build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
		TestUtils.waitForJobs(context, 300, 60_000);
	}

	private static IClasspathEntry entryFor(IClasspathEntry[] entries, String lastSegment) {
		return Arrays.stream(entries).filter(e -> lastSegment.equals(e.getPath().lastSegment())).findFirst()
				.orElse(null);
	}

	private void createBundle(File dir, String name, String exports, String requires) throws IOException {
		File jarFile = new File(dir, name + ".jar");
		try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile))) {
			Manifest manifest = new Manifest();
			Attributes main = manifest.getMainAttributes();
			main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
			main.put(new Attributes.Name("Bundle-ManifestVersion"), "2");
			main.put(new Attributes.Name("Bundle-SymbolicName"), name);
			main.put(new Attributes.Name("Bundle-Version"), "1.0.0");
			if (exports != null) {
				main.put(new Attributes.Name("Export-Package"), exports);
			}
			if (requires != null) {
				main.put(new Attributes.Name("Require-Bundle"), requires);
			}
			ZipEntry entry = new ZipEntry("META-INF/MANIFEST.MF");
			jos.putNextEntry(entry);
			manifest.write(jos);
			jos.closeEntry();
		}
	}
}
