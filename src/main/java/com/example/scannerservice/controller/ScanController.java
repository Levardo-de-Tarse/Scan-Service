package com.example.scannerservice.controller;

import com.example.scannerservice.model.ScanRequest;
import com.example.scannerservice.model.ScanResponse;
import com.example.scannerservice.service.ScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/scan")
@RequiredArgsConstructor
public class ScanController {

    private final ScanService scanService;

    @PostMapping
    public ResponseEntity<ScanResponse> scanDocument(@RequestBody ScanRequest request) {
        try {
            ScanResponse response = scanService.scan(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            ScanResponse errorResponse = new ScanResponse();
            errorResponse.setStatus("Error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    @GetMapping("/devices")
    public ResponseEntity<List<String>> listDevices() {
        try {
            List<String> devices = scanService.listDevices();
            return ResponseEntity.ok(devices);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/default-device")
    public ResponseEntity<String> setDefaultDevice(@RequestParam String deviceName) {
        scanService.setDefaultDevice(deviceName);
        return ResponseEntity.ok("Default scanner set to: " + deviceName);
    }

    @GetMapping("/default-device")
    public ResponseEntity<String> getDefaultDevice() {
        String device = scanService.getDefaultDevice();
        return ResponseEntity.ok(device != null ? device : "No default scanner selected");
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> downloadScan(@RequestParam String filePath) throws IOException {
        byte[] fileContent = scanService.getFileContent(filePath);
        String filename = Path.of(filePath).getFileName().toString();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(fileContent);
    }
}