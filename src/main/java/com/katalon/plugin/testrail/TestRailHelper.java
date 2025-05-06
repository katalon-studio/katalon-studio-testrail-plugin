package com.katalon.plugin.testrail;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.katalon.platform.api.exception.CryptoException;
import com.katalon.platform.api.exception.ResourceException;
import com.katalon.platform.api.preference.PluginPreference;
import java.nio.file.*;
import java.util.zip.*;
import java.io.IOException;
import java.io.UncheckedIOException;

public class TestRailHelper {

	public static String parseId(String text, String patternString) {
		String[] splitText = text.split("/");
		String name = splitText[splitText.length - 1];

		Pattern pattern = Pattern.compile(patternString);
		Matcher matcher = pattern.matcher(name);
		if (matcher.find()) {
			return matcher.group(1);
		} else {
			System.out.println("Not found ID in " + text);
			return "";
		}
	}

	public static void doEncryptionMigrated(PluginPreference preferences) throws CryptoException, ResourceException {
		// Detect that the password in the previous version is encrypted based on the
		// property
		// "IS_ENCRYPTION_MIGRATED". Do encrypt password and reset value of
		// "IS_ENCRYPTION_MIGRATED" if not encrypted.
		boolean isEncryptionMigrated = preferences.getBoolean(TestRailConstants.IS_ENCRYPTION_MIGRATED, false);
		if (!isEncryptionMigrated) {
			String rawPass = preferences.getString(TestRailConstants.PREF_TESTRAIL_PASSWORD, "");
			preferences.setString(TestRailConstants.PREF_TESTRAIL_PASSWORD, rawPass, true);
			preferences.setBoolean(TestRailConstants.IS_ENCRYPTION_MIGRATED, true);
			preferences.save();
		}
	}

	/*
	 * Author: Mohit Kumar
	 * Zip the current report folder for testrun upload
	 */

	public static String zipFolderPath(String inputPath) throws IOException {
		Path sourceFolderPath = Paths.get(inputPath);
		Path zipPath = Paths.get(inputPath + ".zip");

		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
			Files.walk(sourceFolderPath).forEach(path -> {
				try {
					String zipEntryName = sourceFolderPath.relativize(path).toString().replace("\\", "/");
					if (Files.isDirectory(path)) {
						// Add directory entry to the zip file with a trailing slash
						if (!zipEntryName.isEmpty()) {
							ZipEntry zipEntry = new ZipEntry(zipEntryName + "/");
							zos.putNextEntry(zipEntry);
							zos.closeEntry();
						}
					} else {
						ZipEntry zipEntry = new ZipEntry(zipEntryName);
						zos.putNextEntry(zipEntry);
						Files.copy(path, zos);
						zos.closeEntry();
					}
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}

		return zipPath.toString();
	}

}
