package com.lab.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder; // Added for URL encoding
import java.nio.charset.StandardCharsets; // Added for URL encoding
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.sql.Date; 
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class PatientHandler implements HttpHandler {

    // --- Token Slot Configuration ---
    private Map<String,Integer> testDurationMins = new HashMap<>();
    private Map<String, java.time.LocalTime> nextSlotTime = new HashMap<>();
    private Map<String,String> testVenues = new HashMap<>();
    private Map<String,Integer> testTypeCounters = new HashMap<>();

    public PatientHandler() {
        testDurationMins.put("Blood Test", 10);
        testDurationMins.put("Liver Function Test", 15);
        testDurationMins.put("ECG", 20);
        testDurationMins.put("MRI Scan", 30);
        
        java.time.LocalTime start = java.time.LocalTime.of(9,0);
        nextSlotTime.put("Blood Test", start);
        nextSlotTime.put("Liver Function Test", start);
        nextSlotTime.put("ECG", start);
        nextSlotTime.put("MRI Scan", start);
        
        testVenues.put("Blood Test", "Blood Analysis Room - Lab 1");
        testVenues.put("Liver Function Test", "Biochemistry Room - Lab 2");
        testVenues.put("ECG", "Cardiology Unit - Lab 3");
        testVenues.put("MRI Scan", "Imaging Department - Lab 4");
        
        testTypeCounters.put("Blood Test", 0);
        testTypeCounters.put("Liver Function Test", 0);
        testTypeCounters.put("ECG", 0);
        testTypeCounters.put("MRI Scan", 0);
    }

    // --- Main Handler Method ---
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            handleGet(exchange);
        } else if ("POST".equalsIgnoreCase(method)) {
            handlePost(exchange);
        }
    }

    // --- GET Request: Viewing Forms and Reports ---
    private void handleGet(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String pid = getPidFromQuery(query);

        if (pid == null || pid.isEmpty()) {
            Utils.sendResponse(exchange, 401, "Unauthorized: Missing Patient ID.");
            return;
        }

        if (query != null && query.contains("viewreports=true")) {
            // Show reports page
            String html = Utils.readFile("view_reports.html");
            html = html.replace("{{PID}}", Utils.escapeHtml(pid));
            
            StringBuilder rows = new StringBuilder();
            
            List<String[]> reports = Database.getReports(pid); 
            
            if (reports == null || reports.isEmpty()) {
                // FIX: Display "No reports found" when the list is empty
                rows.append("<tr><td colspan='4' style='text-align:center; padding: 20px;'>")
                    .append("No reports available yet. Check back later.</td></tr>");
            } else {
                int idx = 1;
                SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy hh:mm a"); 
                
                for (String[] report : reports) {
                    String fname = report[0];
                    String timestamp = report[1];
                    
                    // FIX: URL Encode the filename for safe link generation
                    String encodedFname = URLEncoder.encode(fname, StandardCharsets.UTF_8.toString());

                    String uploadedAt;
                    try {
                        uploadedAt = sdf.format(new java.util.Date(Long.parseLong(timestamp)));
                    } catch (NumberFormatException e) {
                        uploadedAt = "Invalid Date";
                        System.err.println("Invalid timestamp for report: " + fname);
                    }
                    
                    String reportName = fname.substring(fname.lastIndexOf('_') + 1);

                    rows.append("<tr>");
                    rows.append("<td>").append(idx++).append("</td>");
                    rows.append("<td>").append(Utils.escapeHtml(reportName)).append("</td>");
                    rows.append("<td class='timestamp'>").append(uploadedAt).append("</td>");
                    rows.append("<td>")
                        // Use the encoded filename in the links
                        .append("<a href='/uploads/").append(encodedFname).append("' target='_blank' class='view'>View</a> | ")
                        .append("<a href='/uploads/").append(encodedFname).append("' download class='view'>Download</a>")
                        .append("</td>");
                    rows.append("</tr>");
                }
            }
            
            html = html.replace("{{REPORT_ROWS}}", rows.toString());
            Utils.sendResponse(exchange, 200, html);
            
        } else {
            // Show token application form (default view)
            String html = Utils.readFile("patient_form.html");
            html = html.replace("{{PID}}", Utils.escapeHtml(pid));
            Utils.sendResponse(exchange, 200, html);
        }
    }

    // --- POST Request: Token Application ---
    private void handlePost(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-type");
        String boundary = "--" + contentType.split("boundary=")[1];
        InputStream input = exchange.getRequestBody();
        byte[] data = input.readAllBytes();
        String body = new String(data, "ISO-8859-1");

        Map<String,String> fields = new HashMap<>();
        String presFilename = null;

        // Multipart form parsing logic
        for (String part : body.split(boundary)) {
            if (part.contains("Content-Disposition")) {
                String name = part.split("name=\"")[1].split("\"")[0];
                if (part.contains("filename=\"")) {
                    String filename = part.split("filename=\"")[1].split("\"")[0];
                    int start = part.indexOf("\r\n\r\n")+4;
                    int end = part.lastIndexOf("\r\n");
                    
                    byte[] fileBytes = Arrays.copyOfRange(data, body.indexOf(part)+start, body.indexOf(part)+end);
                    
                    // Secure and unique filename generation (uses "pres" prefix for prescription)
                    presFilename = "pres" + System.currentTimeMillis() + "" + filename.replaceAll("[^a-zA-Z0-9._-]", "");
                    File file = new File("uploads", presFilename);
                    
                    try (FileOutputStream fos = new FileOutputStream(file)) { fos.write(fileBytes); }
                    
                    fields.put(name, presFilename);
                } else {
                    int start = part.indexOf("\r\n\r\n")+4;
                    int end = part.lastIndexOf("\r\n");
                    if (start>0 && end>start) {
                        fields.put(name, part.substring(start, end).trim());
                    }
                }
            }
        }

        String pid = fields.get("pid");
        String name = fields.get("name");
        String test = fields.get("test");
        String mobile = fields.get("mobile");
        String place = fields.get("place");
        String blood = fields.get("blood");
        String pres = presFilename;
        String bookingDateStr = fields.get("booking_date");
        
        java.sql.Date sqlDate = null;
        try {
            sqlDate = java.sql.Date.valueOf(bookingDateStr);
        } catch (IllegalArgumentException e) {
            Utils.sendResponse(exchange, 400, "Invalid date format.");
            return;
        }

        // 1. Duplicate Booking Check
        if (Database.checkDuplicateToken(name, mobile, blood, sqlDate)) {
            String alert = "<html><body style='font-family:Segoe UI; text-align:center; background:#f8d7da;'>"
                + "<h2 style='color:#721c24;'>Booking Failed</h2>"
                + "<p>A token with matching details has <b>already been registered</b> for this date.</p>"
                + "<a href='/patient?pid=" + Utils.escapeHtml(pid) + "'>Back to Patient Page</a>"
                + "</body></html>";
            Utils.sendResponse(exchange, 409, alert); 
            return;
        }

        // 2. Daily Limit Check (Max 20 tokens per test type per day)
        int currentCount = Database.countTokensByTestAndDate(test, sqlDate);
        if (currentCount >= 20) {
            String alert = "<html><body style='font-family:Segoe UI; text-align:center; background:#f8d7da;'>"
                + "<h2 style='color:#721c24;'>Booking Failed</h2>"
                + "<p>Sorry, all tokens for <b>" + Utils.escapeHtml(test) + "</b> are full for the selected date.</p>"
                + "<a href='/patient?pid=" + Utils.escapeHtml(pid) + "'>Back to Patient Page</a>"
                + "</body></html>";
            Utils.sendResponse(exchange, 429, alert); 
            return;
        }

        // 3. Token Number Assignment and Time Slot Calculation
        int count = testTypeCounters.getOrDefault(test, 0) + 1;
        testTypeCounters.put(test, count);
        String tokenNumber = test.replaceAll("\\s+","_").toUpperCase() + "-" + String.format("%02d", count);

        int duration = testDurationMins.getOrDefault(test, 15);
        LocalTime slot = nextSlotTime.getOrDefault(test, LocalTime.of(9,0));
        LocalTime closing = LocalTime.of(19,0); 

        if (slot.isAfter(closing)) {
            String alert = "<script>alert('Lab time is over for " + test + "');window.history.back();</script>";
            Utils.sendResponse(exchange, 200, alert);
            return;
        }

        String venue = testVenues.getOrDefault(test, "General Diagnostics Lab");
        String scheduledTime = slot.format(DateTimeFormatter.ofPattern("hh:mm a"));
        nextSlotTime.put(test, slot.plusMinutes(duration));

        // 4. Database Save
        String[] tokenData = new String[] {
            tokenNumber, pid, name, test, venue, scheduledTime, mobile, place, blood, pres
        };
        Database.addToken(tokenData, sqlDate); 

        // 5. Success Response
        String success = "<html><body style='font-family:Segoe UI;text-align:center;background:#e8f7e4;'>"
            + "<h2>Token Applied Successfully</h2>"
            + "<h3>Token Number: " + tokenNumber + "</h3>"
            + "<h3>Venue: " + venue + "</h3>"
            + "<h3>Scheduled Time: " + scheduledTime + "</h3>"
            + "<h3>Selected Date: " + Utils.escapeHtml(bookingDateStr) + "</h3>"
            + "<h3><img src='/uploads/" + pres + "' width='150' alt='Prescription'></h3>"
            + "<a href='/patient?pid=" + pid + "'>Back to Patient Page</a><br><br>"
            + "<a href='/' style='padding:10px 20px;background:black;color:white;text-decoration:none;'>Logout</a>"
            + "</body></html>";
        Utils.sendResponse(exchange, 200, success);
    }
    
    // --- Utility Method ---
    private String getPidFromQuery(String query) {
        if (query == null) return "";
        for (String param : query.split("&")) {
            if (param.startsWith("pid=")) {
                return param.split("=")[1];
            }
        }
        return "";
    }
}