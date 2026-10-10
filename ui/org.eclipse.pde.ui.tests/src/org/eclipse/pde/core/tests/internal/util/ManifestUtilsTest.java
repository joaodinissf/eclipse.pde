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
package org.eclipse.pde.core.tests.internal.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.pde.internal.core.util.ManifestUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.osgi.framework.Constants;

public class ManifestUtilsTest {

	private static final FileTime MTIME = FileTime.fromMillis(1_700_000_000_000L);

	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private static String manifest(String symbolicName) {
		return "Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: " + symbolicName + "\n";
	}

	/** An archive with one stored entry; entries of equal length give archives of equal length. */
	private static byte[] archive(String entryName, String content) throws IOException {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			ZipEntry entry = new ZipEntry(entryName);
			entry.setMethod(ZipEntry.STORED);
			entry.setSize(bytes.length);
			CRC32 crc = new CRC32();
			crc.update(bytes);
			entry.setCrc(crc.getValue());
			entry.setTime(MTIME.toMillis());
			zip.putNextEntry(entry);
			zip.write(bytes);
			zip.closeEntry();
		}
		return out.toByteArray();
	}

	private static void write(Path file, byte[] content) throws IOException {
		Files.write(file, content);
		Files.setLastModifiedTime(file, MTIME);
	}

	private static String symbolicName(File location) throws CoreException {
		return ManifestUtils.loadManifest(location).get(Constants.BUNDLE_SYMBOLICNAME);
	}

	@Test
	public void testArchiveManifestIsReadOnceWhileUnchanged() throws Exception {
		Path jar = temp.getRoot().toPath().resolve("a.jar");
		byte[] first = archive(JarFile.MANIFEST_NAME, manifest("bundle.one"));
		byte[] second = archive(JarFile.MANIFEST_NAME, manifest("bundle.two"));
		assertEquals(first.length, second.length);

		write(jar, first);
		assertEquals("bundle.one", symbolicName(jar.toFile()));

		// same length and modification time: the cached manifest is used
		write(jar, second);
		assertEquals("bundle.one", symbolicName(jar.toFile()));

		// a new modification time invalidates it
		Files.setLastModifiedTime(jar, FileTime.fromMillis(MTIME.toMillis() + 2000));
		assertEquals("bundle.two", symbolicName(jar.toFile()));
	}

	@Test
	public void testReturnedMapsAreIndependent() throws Exception {
		Path jar = temp.getRoot().toPath().resolve("b.jar");
		write(jar, archive(JarFile.MANIFEST_NAME, manifest("bundle.b")));
		Map<String, String> first = ManifestUtils.loadManifest(jar.toFile());
		first.put("X-Added", "true");
		first.remove(Constants.BUNDLE_SYMBOLICNAME);

		Map<String, String> second = ManifestUtils.loadManifest(jar.toFile());
		assertEquals("bundle.b", second.get(Constants.BUNDLE_SYMBOLICNAME));
		assertNull(second.get("X-Added"));
	}

	@Test
	public void testArchiveWithoutManifestIsRejectedEveryTime() throws Exception {
		Path jar = temp.getRoot().toPath().resolve("c.jar");
		write(jar, archive("other.txt", "x"));
		assertThrows(CoreException.class, () -> ManifestUtils.loadManifest(jar.toFile()));
		assertThrows(CoreException.class, () -> ManifestUtils.loadManifest(jar.toFile()));
	}

	@Test
	public void testUnreadableArchiveIsNotCached() throws Exception {
		Path jar = temp.getRoot().toPath().resolve("d.jar");
		byte[] valid = archive(JarFile.MANIFEST_NAME, manifest("bundle.d"));

		write(jar, new byte[valid.length]);
		assertThrows(CoreException.class, () -> ManifestUtils.loadManifest(jar.toFile()));

		// same length and modification time as the unreadable content
		write(jar, valid);
		assertEquals("bundle.d", symbolicName(jar.toFile()));
	}

	@Test
	public void testDirectoryManifestIsReadEveryTime() throws Exception {
		File dir = temp.newFolder("bundle");
		Path manifestFile = dir.toPath().resolve(JarFile.MANIFEST_NAME);
		Files.createDirectories(manifestFile.getParent());

		Files.writeString(manifestFile, manifest("bundle.one"), StandardCharsets.UTF_8);
		Files.setLastModifiedTime(manifestFile, MTIME);
		assertEquals("bundle.one", symbolicName(dir));

		// a same-length edit within the file system's timestamp granularity must still be seen
		Files.writeString(manifestFile, manifest("bundle.two"), StandardCharsets.UTF_8);
		Files.setLastModifiedTime(manifestFile, MTIME);
		assertEquals("bundle.two", symbolicName(dir));
	}
}
