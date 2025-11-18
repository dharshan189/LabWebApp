
package com.lab.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileServerHandler implements HttpHandler {

    // Define the uploads directory path robustly to establish a security boundary.
    private static final Path UPLOADS_PATH = Paths.get("uploads").toAbsolutePath().normalize();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        
        // 1. Path Extraction and Initial Security
        String path = exchange.getRequestURI().getPath().replace("/uploads/", "");

        // Basic path traversal and empty path check
        if (path.contains("..") || path.startsWith("/") || path.isEmpty()) {
            Utils.sendResponse(exchange, 400, "Bad Request: Invalid path characters.");
            return;
        }
        
        // 2. Resolve the requested file path
        // Resolve the file against the current working directory and normalize it.
        Path requestedFilePath = Paths.get("uploads", path).toAbsolutePath().normalize();
        File file = requestedFilePath.toFile();

        // 3. Strict Canonical Path Security Check (Path Traversal Prevention)
        // Ensure the resolved file path BEGINS with the absolute path of the safe uploads directory.
        if (!Files.exists(requestedFilePath) || !requestedFilePath.startsWith(UPLOADS_PATH)) {
            Utils.sendResponse(exchange, 404, "File Not Found (or access denied).");
            return;
        }

        try {
            // 4. Determine Content Type
            String contentType = Files.probeContentType(requestedFilePath);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }
            
            // 5. Set Headers and Send Status
            exchange.getResponseHeaders().add("Content-Type", contentType);
            // Send the file size in the header
            exchange.sendResponseHeaders(200, file.length()); 
            
            // 6. Stream File Content
            // Use try-with-resources to ensure the output stream is closed.
            try (OutputStream os = exchange.getResponseBody()) {
                Files.copy(requestedFilePath, os);
            }
            
        } catch (IOException e) {
            // An IOException here (after headers are sent) often results in "Connection Reset" client-side.
            // We log the error and allow the handler to finish.
            System.err.println("Error serving file " + path + ": " + e.getMessage());
        }
    }
}
