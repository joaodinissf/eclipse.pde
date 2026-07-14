/*******************************************************************************
 * Copyright (c) 2025 Contributors to the Eclipse Foundation
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.pde.core.project.IBundleProjectDescription;
import org.eclipse.pde.core.project.IBundleProjectService;
import org.eclipse.pde.core.project.IRequiredBundleDescription;
import org.eclipse.pde.internal.core.ClasspathContainerState;
import org.eclipse.pde.internal.core.PDECore;
import org.eclipse.pde.ui.tests.runtime.TestUtils;
import org.eclipse.pde.ui.tests.util.ProjectUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;

/**
 * Tests the behaviour of PDE's asynchronous classpath container update job
 * (driven through {@link ClasspathContainerState}) with respect to
 * <em>skipping</em> unnecessary JDT classpath container applications.
 * <p>
 * The hot path exercised here is
 * {@link ClasspathContainerState#requestClasspathUpdate(IProject)}, which
 * PDE's {@code PluginModelManager} fires for every affected project on every
 * target-platform reload. When nothing relevant changed, recomputing and
 * reapplying an identical classpath container is pure churn: it replaces the
 * live JDT container instance and triggers classpath-change deltas for no
 * benefit. These tests pin two complementary behaviours:
 * <ol>
 * <li>a reload that does not change the computed classpath must reuse the
 * existing container instance ({@link #testNoChangeReloadReusesContainer()}),
 * and</li>
 * <li>a genuine classpath change must still apply a fresh container, so the
 * skip cannot over-suppress real updates
 * ({@link #testGenuineChangeAppliesNewContainer()}).</li>
 * </ol>
 */
public class ClasspathContainerUpdateJobTest {

	private boolean autoBuilding;

	@Before
	public void setUp() throws Exception {
		ProjectUtils.deleteAllWorkspaceProjects();
		IWorkspaceDescription description = ResourcesPlugin.getWorkspace().getDescription();
		autoBuilding = description.isAutoBuilding();
		description.setAutoBuilding(false);
		ResourcesPlugin.getWorkspace().setDescription(description);
	}

	@After
	public void tearDown() throws Exception {
		IWorkspaceDescription description = ResourcesPlugin.getWorkspace().getDescription();
		description.setAutoBuilding(autoBuilding);
		ResourcesPlugin.getWorkspace().setDescription(description);
		ProjectUtils.deleteAllWorkspaceProjects();
	}

	/**
	 * A reload of a project whose classpath did not change must not apply a new
	 * JDT classpath container.
	 * <p>
	 * On the current hot path the request carries no saved container state, so
	 * {@code isUpToDate} short-circuits to "needs update" and a fresh container
	 * is applied even when the computed entries are byte-identical. This test
	 * asserts the container <em>instance</em> is unchanged across a no-op
	 * reload — it therefore fails until the skip consults the persisted state.
	 */
	@Test
	public void testNoChangeReloadReusesContainer() throws Exception {
		createPluginProject("cpujt.dep", null);
		IProject stable = createPluginProject("cpujt.stable", "cpujt.dep");
		buildAndSettle("cpujt no-change setup");

		// Prime the persisted classpath state so the next request has an
		// on-disk snapshot to compare against.
		ClasspathContainerState.requestClasspathUpdate(stable);
		settle("cpujt.stable prime");

		IClasspathContainer before = getContainer(stable);
		assertNotNull("PDE classpath container must be initialized", before);
		assertThat(entryNames(before)).as("provider must be on the stable project's classpath")
				.contains("cpujt.dep");

		// The hot path: a bare request with no attached container state, exactly
		// as PluginModelManager fires on a target reload. Nothing changed.
		ClasspathContainerState.requestClasspathUpdate(stable);
		settle("cpujt.stable no-change reload");

		IClasspathContainer after = getContainer(stable);
		assertSame("A reload that does not change the classpath must reuse the existing container "
				+ "instead of applying a fresh JDT classpath container", before, after);
	}

	/**
	 * A genuine classpath change (here: the consumer starts requiring another
	 * bundle) must still apply a new container. Guards the no-change skip
	 * against over-suppressing real updates.
	 */
	@Test
	public void testGenuineChangeAppliesNewContainer() throws Exception {
		createPluginProject("cpujt.provider", null);
		IProject consumer = createPluginProject("cpujt.consumer", null);
		buildAndSettle("cpujt genuine-change setup");

		ClasspathContainerState.requestClasspathUpdate(consumer);
		settle("cpujt.consumer prime");

		IClasspathContainer before = getContainer(consumer);
		assertNotNull("PDE classpath container must be initialized", before);
		assertThat(entryNames(before)).as("provider must not yet be on the consumer classpath")
				.doesNotContain("cpujt.provider");

		// Genuine change: the consumer now requires the provider bundle.
		IBundleProjectService service = PDECore.getDefault().acquireService(IBundleProjectService.class);
		IBundleProjectDescription description = service.getDescription(consumer);
		description.setRequiredBundles(new IRequiredBundleDescription[] {
				service.newRequiredBundle("cpujt.provider", (VersionRange) null, false, false) });
		description.apply(null);
		buildAndSettle("cpujt.consumer manifest change");

		ClasspathContainerState.requestClasspathUpdate(consumer);
		settle("cpujt.consumer change reload");

		IClasspathContainer after = getContainer(consumer);
		assertThat(after).as("a genuine classpath change must apply a new container").isNotSameAs(before);
		assertThat(entryNames(after)).as("provider must now be on the consumer classpath")
				.contains("cpujt.provider");
	}

	private static IProject createPluginProject(String name, String requiredBundle) throws Exception {
		IBundleProjectService service = PDECore.getDefault().acquireService(IBundleProjectService.class);
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
		if (!project.exists()) {
			project.create(null);
		}
		project.open(null);
		IBundleProjectDescription description = service.getDescription(project);
		description.setSymbolicName(name);
		description.setBundleVersion(new Version("1.0.0"));
		if (requiredBundle != null) {
			description.setRequiredBundles(new IRequiredBundleDescription[] {
					service.newRequiredBundle(requiredBundle, (VersionRange) null, false, false) });
		}
		description.setNatureIds(new String[] { JavaCore.NATURE_ID, IBundleProjectDescription.PLUGIN_NATURE });
		description.apply(null);
		return project;
	}

	private static void buildAndSettle(String context) throws Exception {
		ResourcesPlugin.getWorkspace().build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
		settle(context);
	}

	private static void settle(String context) {
		TestUtils.waitForJobs(context, 200, 30_000);
	}

	private static IClasspathContainer getContainer(IProject project) throws Exception {
		IJavaProject javaProject = JavaCore.create(project);
		return JavaCore.getClasspathContainer(PDECore.REQUIRED_PLUGINS_CONTAINER_PATH, javaProject);
	}

	private static List<String> entryNames(IClasspathContainer container) {
		return Arrays.stream(container.getClasspathEntries()).map(e -> e.getPath().lastSegment()).toList();
	}
}
