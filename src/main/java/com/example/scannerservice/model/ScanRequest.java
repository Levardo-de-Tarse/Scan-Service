package com.example.scannerservice.model;

import lombok.Data;

@Data
public class ScanRequest {
    private String filename;
    private String directory;
    /** Partial device match with --noprofile (NAPS2 7.3+ / 8.x); or full {@code driver||name} from GET /devices when using auto driver. Ignored when {@code profile} is set. */
    private String scannerDevice;
    /** NAPS2 GUI profile name ({@code -p}). TWAIN/WIA come from the profile. Supported on all NAPS2 versions with profiles. */
    private String profile;
}