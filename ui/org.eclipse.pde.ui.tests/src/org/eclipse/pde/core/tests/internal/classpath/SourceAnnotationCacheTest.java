/*******************************************************************************
 * Copyright (c) 2026 João Dinis Ferreira and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.pde.core.tests.internal.classpath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.IPath;
import org.eclipse.osgi.service.resolver.State;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetLocation;
import org.eclipse.pde.internal.core.ClasspathUtilCore;
import org.eclipse.pde.internal.core.ICoreConstants;
import org.eclipse.pde.internal.core.PDECore;
import org.eclipse.pde.internal.core.PDEPreferencesManager;
import org.eclipse.pde.internal.core.TargetPlatformHelper;
import org.eclipse.pde.ui.tests.util.TargetPlatformUtil;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;

/**
 * Source attachments of external plug-ins are cached per resolver state and
 * source locations preference.
 */
public class SourceAnnotationCacheTest {

	private static final String BUNDLE = "org.eclipse.pde.tests.sourceannotation";

	@ClassRule
	public static final TestRule RESTORE_TARGET = TargetPlatformUtil.RESTORE_CURRENT_TARGET_DEFINITION_AFTER;

	@ClassRule
	public static final TemporaryFolder TEMP = new TemporaryFolder();

	private static Path bundleDir;
	private static IPluginModelBase model;

	@BeforeClass
	public static void setUpTarget() throws Exception {
		Path targetDir = TEMP.newFolder("target").toPath();
		bundleDir = targetDir.resolve(BUNDLE + "_1.0.0");
		Path manifest = bundleDir.resolve("META-INF/MANIFEST.MF");
		Files.createDirectories(manifest.getParent());
		Files.writeString(manifest, """
				Manifest-Version: 1.0
				Bundle-ManifestVersion: 2
				Bundle-SymbolicName: %s
				Bundle-Version: 1.0.0
				Bundle-ClassPath: a.jar,b.jar,c.jar,d.jar
				""".formatted(BUNDLE), StandardCharsets.UTF_8);

		ITargetDefinition target = TargetPlatformUtil.TPS.newTarget();
		target.setTargetLocations(
				new ITargetLocation[] { TargetPlatformUtil.TPS.newDirectoryLocation(targetDir.toString()) });
		TargetPlatformUtil.loadAndSetTarget(target);

		model = PluginRegistry.findModel(BUNDLE);
		assertNotNull("external model not found", model);
		assertNull("model must be external", model.getUnderlyingResource());
	}

	@AfterClass
	public static void tearDown() {
		ClasspathUtilCore.resetSourceAnnotations();
	}

	@Before
	public void reset() {
		ClasspathUtilCore.resetSourceAnnotations();
	}

	private static IPath annotation(String library) {
		return ClasspathUtilCore.getSourceAnnotation(model, library, false);
	}

	private static void createSourceArchive(String name) throws Exception {
		Files.writeString(bundleDir.resolve(name), "", StandardCharsets.UTF_8);
	}

	@Test
	public void testAnnotationIsComputedOncePerScope() throws Exception {
		createSourceArchive("asrc.zip");
		IPath first = annotation("a.jar");
		assertEquals(bundleDir.resolve("asrc.zip").toString(), first.toOSString());
		assertSame(first, annotation("a.jar"));

		ClasspathUtilCore.resetSourceAnnotations();
		IPath recomputed = annotation("a.jar");
		assertNotSame(first, recomputed);
		assertEquals(first, recomputed);
	}

	@Test
	public void testMissingAnnotationIsCachedUntilReset() throws Exception {
		assertNull(annotation("b.jar"));
		createSourceArchive("bsrc.zip");
		assertNull(annotation("b.jar"));

		ClasspathUtilCore.resetSourceAnnotations();
		assertEquals(bundleDir.resolve("bsrc.zip").toString(), annotation("b.jar").toOSString());
	}

	@Test
	@SuppressWarnings("deprecation")
	public void testSourceLocationsPreferenceChangeStartsNewScope() throws Exception {
		createSourceArchive("csrc.zip");
		IPath first = annotation("c.jar");
		assertSame(first, annotation("c.jar"));

		PDEPreferencesManager preferences = PDECore.getDefault().getPreferencesManager();
		String original = preferences.getString(ICoreConstants.P_SOURCE_LOCATIONS);
		try {
			preferences.setValue(ICoreConstants.P_SOURCE_LOCATIONS, original + ";/does/not/exist,true");
			IPath recomputed = annotation("c.jar");
			assertNotSame(first, recomputed);
			assertEquals(first, recomputed);
		} finally {
			preferences.setValue(ICoreConstants.P_SOURCE_LOCATIONS, original);
		}
	}

	@Test
	public void testStateChangeStartsNewScope() throws Exception {
		createSourceArchive("dsrc.zip");
		IPath first = annotation("d.jar");
		assertSame(first, annotation("d.jar"));

		State state = TargetPlatformHelper.getState();
		long original = state.getTimeStamp();
		try {
			state.setTimeStamp(original + 1);
			IPath recomputed = annotation("d.jar");
			assertNotSame(first, recomputed);
			assertEquals(first, recomputed);
		} finally {
			state.setTimeStamp(original);
		}
	}
}
