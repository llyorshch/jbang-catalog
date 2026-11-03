///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DESCRIPTION Run standalone Maven plugin goals without installing Maven

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runs Maven plugin goals by downloading a verified Maven distribution on first use, caching it, and delegating all
 * arguments to its launcher.
 */
public class mvnrun {
	private static final String DEFAULT_MAVEN_VERSION = "3.9.16";
	private static final String DEFAULT_REPO_URL = "https://repo.maven.apache.org/maven2";
	private static final String VERSION_OPTION = "--maven-version";
	private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

	private static final String USAGE = String.join(System.lineSeparator(),
			"Usage: mvnrun [" + VERSION_OPTION + " <version>] <goal> [maven arguments...]",
			"",
			"Examples:",
			"  mvnrun help:evaluate -Dexpression=java.version -q -DforceStdout",
			"  mvnrun org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get -Dartifact=g:a:v",
			"",
			"Default Maven version: " + DEFAULT_MAVEN_VERSION,
			"Environment:",
			"  MVNRUN_REPO_URL  Maven repository to download Maven from (default: " + DEFAULT_REPO_URL + ")",
			"  JBANG_CACHE_DIR  Cache root (default: $JBANG_DIR/cache, or ~/.jbang/cache)");

	public static void main(String[] args) throws Exception {
		List<String> mavenArgs = new ArrayList<>(Arrays.asList(args));
		String version = DEFAULT_MAVEN_VERSION;

		if (!mavenArgs.isEmpty() && mavenArgs.get(0).equals(VERSION_OPTION)) {
			if (mavenArgs.size() < 2) {
				usageError(VERSION_OPTION + " requires a value");
			}
			version = mavenArgs.get(1);
			mavenArgs = mavenArgs.subList(2, mavenArgs.size());
		} else if (!mavenArgs.isEmpty() && mavenArgs.get(0).startsWith(VERSION_OPTION + "=")) {
			version = mavenArgs.get(0).substring(VERSION_OPTION.length() + 1);
			mavenArgs = mavenArgs.subList(1, mavenArgs.size());
		}

		if (mavenArgs.isEmpty()) {
			usageError("no goal specified");
		}
		if (mavenArgs.get(0).equals("--help") && args.length == 1) {
			System.out.println(USAGE);
			return;
		}
		if (!version.matches("[0-9][0-9A-Za-z.-]*")) {
			usageError("invalid Maven version: " + version);
		}

		int exitCode;
		try {
			Path mavenHome = ensureMaven(version);
			exitCode = runMaven(mavenHome, mavenArgs);
		} catch (IOException e) {
			System.err.println("[mvnrun] error: " + e.getMessage());
			exitCode = 1;
		}
		System.exit(exitCode);
	}

	private static void usageError(String message) {
		System.err.println("[mvnrun] error: " + message);
		System.err.println(USAGE);
		System.exit(2);
	}

	private static Path cacheRoot() {
		String cacheDir = System.getenv("JBANG_CACHE_DIR");
		if (cacheDir != null && !cacheDir.isEmpty()) {
			return Paths.get(cacheDir, "mvnrun");
		}
		String jbangDir = System.getenv("JBANG_DIR");
		if (jbangDir != null && !jbangDir.isEmpty()) {
			return Paths.get(jbangDir, "cache", "mvnrun");
		}
		return Paths.get(System.getProperty("user.home"), ".jbang", "cache", "mvnrun");
	}

	private static Path launcher(Path mavenHome) {
		return mavenHome.resolve("bin").resolve(WINDOWS ? "mvn.cmd" : "mvn");
	}

	private static Path ensureMaven(String version) throws IOException {
		Path root = cacheRoot();
		String dirName = "apache-maven-" + version;
		Path mavenHome = root.resolve(dirName);
		if (Files.isRegularFile(launcher(mavenHome))) {
			return mavenHome;
		}

		Files.createDirectories(root);
		// Install into a private directory, then publish it with one atomic rename so that
		// concurrent runs and interrupted downloads can never expose a partial installation.
		Path work = Files.createTempDirectory(root, ".install-");
		try {
			String repo = System.getenv("MVNRUN_REPO_URL");
			repo = repo == null || repo.isEmpty() ? DEFAULT_REPO_URL : repo.replaceAll("/+$", "");
			String baseUrl = repo + "/org/apache/maven/apache-maven/" + version + "/" + dirName + "-bin.zip";

			HttpClient http = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.build();

			System.err.println("[mvnrun] Downloading Maven " + version + " from " + baseUrl);
			String expected = fetchChecksum(http, baseUrl + ".sha512");
			Path zip = work.resolve("maven.zip");
			String actual = download(http, baseUrl, zip);
			if (!expected.equalsIgnoreCase(actual)) {
				throw new IOException("SHA-512 mismatch for " + baseUrl + ": expected " + expected + " but got " + actual);
			}

			Path extracted = work.resolve("extracted");
			unzip(zip, extracted);
			Path source = extracted.resolve(dirName);
			if (!Files.isRegularFile(launcher(source))) {
				throw new IOException("Downloaded archive does not contain " + dirName + "/bin launcher");
			}
			makeLaunchersExecutable(source.resolve("bin"));
			publish(source, mavenHome);
			return mavenHome;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while downloading Maven", e);
		} finally {
			deleteRecursively(work);
		}
	}

	private static void publish(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			// Another run may have published the same version first, which is fine. Otherwise it is a real failure.
			if (!Files.isRegularFile(launcher(target))) {
				throw e;
			}
		}
	}

	private static String fetchChecksum(HttpClient http, String url) throws IOException, InterruptedException {
		HttpResponse<String> response = http.send(request(url), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("Could not download " + url + " (HTTP " + response.statusCode() + ")");
		}
		String checksum = response.body().trim().split("\\s+")[0];
		if (!checksum.matches("[0-9a-fA-F]{128}")) {
			throw new IOException("Invalid SHA-512 checksum at " + url);
		}
		return checksum;
	}

	private static String download(HttpClient http, String url, Path target) throws IOException, InterruptedException {
		HttpResponse<InputStream> response = http.send(request(url), HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream in = response.body()) {
			if (response.statusCode() != 200) {
				throw new IOException("Could not download " + url + " (HTTP " + response.statusCode() + ")");
			}
			MessageDigest digest = sha512();
			try (OutputStream out = new DigestOutputStream(Files.newOutputStream(target), digest)) {
				in.transferTo(out);
			}
			StringBuilder hex = new StringBuilder();
			for (byte b : digest.digest()) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		}
	}

	private static HttpRequest request(String url) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).build();
	}

	private static MessageDigest sha512() {
		try {
			return MessageDigest.getInstance("SHA-512");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void unzip(Path zip, Path target) throws IOException {
		Path root = target.toAbsolutePath().normalize();
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				Path destination = root.resolve(entry.getName()).normalize();
				if (!destination.startsWith(root)) {
					throw new IOException("Archive entry escapes target directory: " + entry.getName());
				}
				if (entry.isDirectory()) {
					Files.createDirectories(destination);
				} else {
					Files.createDirectories(destination.getParent());
					Files.copy(in, destination);
				}
			}
		}
	}

	private static void makeLaunchersExecutable(Path bin) throws IOException {
		// ZipInputStream does not carry Unix permissions.
		try (Stream<Path> files = Files.list(bin)) {
			files.filter(Files::isRegularFile).forEach(file -> file.toFile().setExecutable(true));
		}
	}

	private static int runMaven(Path mavenHome, List<String> args) throws IOException {
		List<String> command = new ArrayList<>();
		command.add(launcher(mavenHome).toString());
		command.addAll(args);

		ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
		// A JBang-managed JDK may not be on PATH, so point Maven at the JVM running this script.
		String javaHome = builder.environment().get("JAVA_HOME");
		if (javaHome == null || javaHome.isEmpty()) {
			builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
		}

		Process process = builder.start();
		try {
			return process.waitFor();
		} catch (InterruptedException e) {
			process.destroy();
			Thread.currentThread().interrupt();
			return 130;
		}
	}

	private static void deleteRecursively(Path dir) {
		try (Stream<Path> paths = Files.walk(dir)) {
			paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		} catch (IOException e) {
			System.err.println("[mvnrun] warning: could not remove " + dir + ": " + e.getMessage());
		}
	}
}
