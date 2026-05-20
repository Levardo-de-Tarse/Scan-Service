package com.example.scannerservice.service;

import com.example.scannerservice.model.ScanRequest;
import com.example.scannerservice.model.ScanResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class ScanService {

    /** Between driver id and device name in GET /devices when {@code scanner.driver=auto}. Unlikely in vendor strings. */
    static final String DRIVER_DEVICE_SEP = "||";

    @Value("${scanner.command:naps2}")
    private String scannerCommand;

    @Value("${scanner.output.dir:./scans}")
    private String defaultOutputDir;

    /**
     * Driver for --listdevices and for --noprofile scans: {@code twain}, {@code wia}, {@code escl}, …
     * Use {@code auto} to try {@link #scannerAutoDrivers} and pick a device without fixed driver.
     */
    @Value("${scanner.driver:auto}")
    private String scannerDriver;

    /** Comma-separated order for {@code scanner.driver=auto} (e.g. {@code twain,wia,escl}). */
    @Value("${scanner.auto-drivers:twain,wia,escl}")
    private String scannerAutoDrivers;

    /**
     * Cache duration for device enumeration (GET /devices and auto driver matching). 0 = always refresh
     * (slower if the browser polls often). Parallel subprocess calls either way when cache misses.
     */
    @Value("${scanner.device-list-cache-seconds:15}")
    private long deviceListCacheSeconds;

    /** Resolved executable (e.g. full path to NAPS2.Console.exe on Windows). */
    private String effectiveScannerCommand;

    /** True when this NAPS2 build supports --listdevices / --driver / --device (7.3+, including 8.x). */
    private boolean extendedTwainCli;

    private String defaultScannerDevice;

    /** Cached merged device list (API) and per-driver names for auto resolution. */
    private volatile DeviceCacheHolder deviceListCacheHolder;

    private record DeviceCache(List<String> mergedForApi, Map<String, List<String>> byDriver) {}

    private record DeviceCacheHolder(DeviceCache cache, long createdAtMs) {}

    private boolean isAutoDriver() {
        if (scannerDriver == null || scannerDriver.isBlank()) {
            return true;
        }
        return "auto".equalsIgnoreCase(scannerDriver.trim());
    }

    /**
     * Fixed NAPS2 driver id, or {@code null} when {@link #isAutoDriver()}.
     */
    private String fixedDriverOrNull() {
        if (isAutoDriver()) {
            return null;
        }
        return scannerDriver.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> autoDriverOrder() {
        List<String> out = new ArrayList<>();
        for (String part : scannerAutoDrivers.split(",")) {
            String d = part.trim().toLowerCase(Locale.ROOT);
            if (!d.isEmpty()) {
                out.add(d);
            }
        }
        if (out.isEmpty()) {
            out.add("twain");
        }
        return out;
    }

    @PostConstruct
    void initNaps2Cli() {
        effectiveScannerCommand = resolveEffectiveScannerCommand(scannerCommand);
        extendedTwainCli = detectExtendedTwainCli();
        String driverInfo = isAutoDriver() ? "auto " + autoDriverOrder() : String.valueOf(fixedDriverOrNull());
        log.info("NAPS2 executable: {}, driver: {}, extended CLI (--listdevices): {}",
                effectiveScannerCommand, driverInfo, extendedTwainCli);
    }

    /**
     * Resolves the NAPS2 console path: explicit {@code scanner.command}, then a copy shipped next to
     * the app ({@code naps2-bin/NAPS2.Console.exe}, e.g. jpackage layout), then usual Windows installs / PATH token.
     */
    private String resolveEffectiveScannerCommand(String configured) {
        if (configured != null && !configured.isBlank() && !isDefaultNaps2Token(configured)) {
            return configured.trim();
        }
        Optional<Path> bundled = findBundledNaps2Console();
        if (bundled.isPresent()) {
            return bundled.get().toString();
        }
        return resolveScannerExecutable(
                configured == null || configured.isBlank() ? "naps2" : configured.trim());
    }

    private static boolean isDefaultNaps2Token(String command) {
        String t = command.trim();
        return t.equalsIgnoreCase("naps2")
                || t.equalsIgnoreCase("naps2.console")
                || t.equalsIgnoreCase("naps2.console.exe");
    }

    /**
     * Looks for {@code naps2-bin/NAPS2.Console.exe} next to the Spring Boot jar (jpackage: {@code ../naps2-bin/}
     * from {@code app/scanner-service.jar}, or same directory as a standalone {@code .jar}).
     */
    private static Optional<Path> findBundledNaps2Console() {
        try {
            ApplicationHome home = new ApplicationHome(ScanService.class);
            if (home.getSource() == null) {
                return Optional.empty();
            }
            Path jarPath = home.getSource().toPath().normalize();
            Path parent = jarPath.getParent();
            if (parent == null) {
                return Optional.empty();
            }
            Path installRoot;
            if ("app".equalsIgnoreCase(parent.getFileName().toString())) {
                installRoot = parent.getParent();
            } else {
                installRoot = parent;
            }
            if (installRoot == null) {
                return Optional.empty();
            }
            Path candidate = installRoot.resolve("naps2-bin").resolve("NAPS2.Console.exe");
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        } catch (Exception ignored) {
            // not packaged / unexpected layout
        }
        return Optional.empty();
    }

    /**
     * If the user keeps the default {@code naps2} token, try the usual Windows install path
     * so ProcessBuilder does not rely on PATH.
     */
    private static String resolveScannerExecutable(String command) {
        if (command == null || command.isBlank()) {
            return command;
        }
        String trimmed = command.trim();
        if (!trimmed.equalsIgnoreCase("naps2") && !trimmed.equalsIgnoreCase("naps2.console")
                && !trimmed.equalsIgnoreCase("naps2.console.exe")) {
            return trimmed;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            List<Path> candidates = new ArrayList<>();
            candidates.add(Paths.get("C:\\Program Files\\NAPS2\\NAPS2.Console.exe"));
            candidates.add(Paths.get("C:\\Program Files (x86)\\NAPS2\\NAPS2.Console.exe"));
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                candidates.add(Paths.get(localAppData, "Programs", "NAPS2", "NAPS2.Console.exe"));
            }
            for (Path p : candidates) {
                if (Files.isRegularFile(p)) {
                    return p.toString();
                }
            }
        }
        return trimmed;
    }

    private boolean detectExtendedTwainCli() {
        try {
            ProcessBuilder pb = new ProcessBuilder(effectiveScannerCommand, "--help");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String combined = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            return combined.toLowerCase(Locale.ROOT).contains("listdevices");
        } catch (Exception e) {
            log.warn("Could not probe NAPS2 --help (is scanner.command correct?): {}", e.getMessage());
            return false;
        }
    }

    public ScanResponse scan(ScanRequest request) throws IOException, InterruptedException {
        String outputDir = request.getDirectory() != null ? request.getDirectory() : defaultOutputDir;
        String filename = request.getFilename() != null ? request.getFilename()
                : "scan_" + System.currentTimeMillis() + ".pdf";

        Path outputPath = Paths.get(outputDir, filename);
        Files.createDirectories(outputPath.getParent());

        String chosen = request.getScannerDevice() != null ? request.getScannerDevice() : defaultScannerDevice;
        String profile = request.getProfile();
        boolean implicitSource = (profile == null || profile.isBlank())
                && (chosen == null || chosen.isBlank());

        List<String> command = buildScanCommand(outputPath, chosen, profile);

        log.info("Executing: {}", String.join(" ", command));

        ProcessBuilder scanPb = new ProcessBuilder(command);
        scanPb.redirectErrorStream(true);
        Process process = scanPb.start();
        String combinedOutput = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            log.error("NAPS2 output: {}", combinedOutput);
            String tip = "";
            if (extendedTwainCli && implicitSource) {
                tip = " Укажите в JSON поле scannerDevice (часть имени из GET /api/scan/devices) "
                        + "или profile (точное имя профиля из окна NAPS2). Пустой {} без них часто даёт ошибку NAPS2.";
            }
            throw new RuntimeException("Scanning failed. Error: " + combinedOutput + tip);
        }

        byte[] fileContent = Files.readAllBytes(outputPath);

        ScanResponse response = new ScanResponse();
        response.setFilePath(outputPath.toString());
        response.setFileContent(fileContent);
        response.setScannerDevice(request.getScannerDevice() != null ? request.getScannerDevice() : defaultScannerDevice);
        response.setStatus("Success");

        return response;
    }

    private List<String> buildScanCommand(Path outputPath, String chosen, String profile) {
        List<String> command = new ArrayList<>();
        command.add(effectiveScannerCommand);
        command.add("-o");
        command.add(outputPath.toString());
        command.add("-v");
        command.add("-f");

        if (profile != null && !profile.isBlank()) {
            command.add("-p");
            command.add(profile.trim());
            return command;
        }

        if (extendedTwainCli) {
            if (chosen != null && !chosen.isBlank()) {
                DriverDevice dd = resolveDriverForDevice(chosen.trim());
                command.add("--noprofile");
                command.add("--driver");
                command.add(dd.driver());
                command.add("--device");
                command.add(dd.deviceArg());
            }
        } else {
            // NAPS2 7.1.x and similar: TWAIN/driver is taken from the GUI profile; CLI only selects profile by name.
            if (chosen != null && !chosen.isBlank()) {
                command.add("-p");
                command.add(stripAutoDriverPrefixForLegacyProfile(chosen.trim()));
            }
        }
        return command;
    }

    /**
     * For 7.1, {@code chosen} may be {@code twain||My profile}; use only the profile name part.
     */
    private static String stripAutoDriverPrefixForLegacyProfile(String chosen) {
        int i = chosen.indexOf(DRIVER_DEVICE_SEP);
        if (i > 0) {
            return chosen.substring(i + DRIVER_DEVICE_SEP.length()).trim();
        }
        return chosen;
    }

    private record DriverDevice(String driver, String deviceArg) {}

    /**
     * Resolves NAPS2 {@code --driver} and the string for {@code --device}.
     * <ul>
     *   <li>Fixed {@code scanner.driver}: uses that driver; {@code chosen} is the device/partial name.</li>
     *   <li>{@code auto}: if {@code chosen} is {@code driver||device}, splits; else finds first driver in
     *       {@link #autoDriverOrder()} whose device list matches {@code chosen} (substring match).</li>
     * </ul>
     */
    private DriverDevice resolveDriverForDevice(String chosen) {
        if (!isAutoDriver()) {
            String d = fixedDriverOrNull();
            if (d == null) {
                d = "twain";
            }
            return new DriverDevice(d, chosen);
        }
        int sep = chosen.indexOf(DRIVER_DEVICE_SEP);
        if (sep > 0) {
            String d = chosen.substring(0, sep).trim().toLowerCase(Locale.ROOT);
            String dev = chosen.substring(sep + DRIVER_DEVICE_SEP.length()).trim();
            return new DriverDevice(d, dev);
        }
        String partial = chosen;
        Map<String, List<String>> byDriver = getOrRefreshDeviceCache().byDriver();
        for (String d : autoDriverOrder()) {
            List<String> listedNames = byDriver.getOrDefault(d, List.of());
            for (String listed : listedNames) {
                if (deviceListingMatches(partial, listed)) {
                    return new DriverDevice(d, partial);
                }
            }
        }
        String fallback = autoDriverOrder().get(0);
        log.warn("No device list match for '{}' under auto drivers {}; using driver {}", partial,
                autoDriverOrder(), fallback);
        return new DriverDevice(fallback, partial);
    }

    private static boolean deviceListingMatches(String partial, String listed) {
        if (partial.isEmpty() || listed.isEmpty()) {
            return false;
        }
        String p = partial.toLowerCase(Locale.ROOT);
        String l = listed.toLowerCase(Locale.ROOT);
        return l.contains(p) || p.contains(l) || l.equals(p);
    }

    private List<String> parseListDevicesOutput(String output) {
        List<String> devices = new ArrayList<>();
        if (output == null || output.isEmpty()) {
            return devices;
        }
        if (!output.contains(": ")) {
            for (String line : output.split("\\R")) {
                String d = line.trim();
                if (!d.isEmpty() && !d.startsWith("NAPS2")) {
                    devices.add(d);
                }
            }
        } else {
            for (String line : output.split("\\R")) {
                if (line.contains(": \"")) {
                    devices.add(line.split(": \"")[1].replace("\"", ""));
                }
            }
        }
        return devices;
    }

    private List<String> listDevicesForDriver(String driver) throws IOException, InterruptedException {
        ProcessBuilder listPb = new ProcessBuilder(
                effectiveScannerCommand,
                "--listdevices",
                "--driver",
                driver
        );
        listPb.redirectErrorStream(true);
        Process process = listPb.start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        int code = process.waitFor();
        log.debug("NAPS2 --listdevices --driver {}: exit {}, output: {}", driver, code, output);
        if (code != 0) {
            return List.of();
        }
        return parseListDevicesOutput(output);
    }

    private List<String> listDevicesForDriverSafe(String driver) {
        try {
            return new ArrayList<>(listDevicesForDriver(driver));
        } catch (IOException e) {
            log.warn("--listdevices I/O failed for driver {}: {}", driver, e.getMessage());
            return new ArrayList<>();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        }
    }

    private DeviceCache buildDeviceCacheFresh() {
        if (!isAutoDriver()) {
            String d = fixedDriverOrNull();
            if (d == null) {
                d = "twain";
            }
            try {
                List<String> names = new ArrayList<>(listDevicesForDriver(d));
                Map<String, List<String>> map = new LinkedHashMap<>();
                map.put(d, names);
                return new DeviceCache(names, map);
            } catch (IOException e) {
                log.warn("--listdevices failed for driver {}: {}", d, e.getMessage());
                return new DeviceCache(List.of(), Map.of());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new DeviceCache(List.of(), Map.of());
            }
        }
        List<String> order = autoDriverOrder();
        Map<String, CompletableFuture<List<String>>> futures = new LinkedHashMap<>();
        for (String d : order) {
            final String driverId = d;
            futures.put(d, CompletableFuture.supplyAsync(() -> listDevicesForDriverSafe(driverId)));
        }
        Map<String, List<String>> byDriver = new LinkedHashMap<>();
        List<String> merged = new ArrayList<>();
        for (String d : order) {
            List<String> names = futures.get(d).join();
            byDriver.put(d, names);
            for (String n : names) {
                merged.add(d + DRIVER_DEVICE_SEP + n);
            }
        }
        return new DeviceCache(merged, byDriver);
    }

    private DeviceCache getOrRefreshDeviceCache() {
        if (deviceListCacheSeconds <= 0) {
            return buildDeviceCacheFresh();
        }
        long ttlMs = deviceListCacheSeconds * 1000L;
        long now = System.currentTimeMillis();
        DeviceCacheHolder h = deviceListCacheHolder;
        if (deviceListCacheSeconds > 0 && h != null && (now - h.createdAtMs) < ttlMs) {
            return h.cache();
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            h = deviceListCacheHolder;
            if (deviceListCacheSeconds > 0 && h != null && (now - h.createdAtMs) < ttlMs) {
                return h.cache();
            }
            DeviceCache fresh = buildDeviceCacheFresh();
            deviceListCacheHolder = new DeviceCacheHolder(fresh, System.currentTimeMillis());
            return fresh;
        }
    }

    public List<String> listDevices() {
        if (!extendedTwainCli) {
            log.warn("This NAPS2 build has no --listdevices. Use a GUI profile (-p / scannerDevice on 7.1) or upgrade to NAPS2 7.3+.");
            return List.of();
        }
        return new ArrayList<>(getOrRefreshDeviceCache().mergedForApi());
    }

    public void setDefaultDevice(String deviceName) {
        this.defaultScannerDevice = deviceName;
    }

    public String getDefaultDevice() {
        return this.defaultScannerDevice;
    }

    public byte[] getFileContent(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new FileNotFoundException("File not found: " + filePath);
        }
        return Files.readAllBytes(path);
    }
}
