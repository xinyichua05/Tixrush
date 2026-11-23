package com.tixrush.common;

import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Checks an uploaded picture (event pictures and venue seating plans share this).
 *
 * We never trust the file name or the type the browser claims ("photo.png" can be anything).
 * Instead we look at the first bytes of the file: every JPEG, PNG and WebP file starts with
 * its own fixed "magic number". Anything else is refused.
 */
public final class ImageFiles {

    /** The browser shrinks pictures before uploading (~100-400 KB), so 2 MB is plenty. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    /** A checked picture: its real type (e.g. "image/webp") and its bytes. */
    public record Checked(String contentType, byte[] data) {
    }

    private ImageFiles() {
    }

    /** Reads and checks the upload: 400 if empty or not a JPG/PNG/WebP, 413 if over 2 MB. */
    public static Checked check(MultipartFile file) {
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (data.length == 0) {
            throw new BadRequestException("That file is empty. Please choose a picture.");
        }
        if (data.length > MAX_BYTES) {
            // Normally the server's upload limit (application.yml) stops it first; same 413 either way.
            throw new MaxUploadSizeExceededException(MAX_BYTES);
        }
        String type = detectType(data);
        if (type == null) {
            throw new BadRequestException("Please choose a JPG, PNG or WebP picture.");
        }
        return new Checked(type, data);
    }

    /** Which picture format is this, judging by its first bytes? null = not one we accept. */
    public static String detectType(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }
}
