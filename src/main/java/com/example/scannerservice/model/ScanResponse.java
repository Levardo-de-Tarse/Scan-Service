package com.example.scannerservice.model;

import lombok.Data;

@Data
public class ScanResponse {
    private String filePath;
    private byte[] fileContent;
    private String scannerDevice;
    private String status;
}
