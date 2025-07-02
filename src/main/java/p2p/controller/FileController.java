package p2p.controller;

import com.sun.net.httpserver.HttpsServer;
import p2p.service.FileSharer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileController {
    private final FileSharer fileSharer;
    private final HttpsServer server;
    private final String uploadDir;
    private final ExecutorService executorService;

    public FileController() throws IOException{
        this.fileSharer = new FileSharer();
        this.server = HttpsServer.create(new InetSocketAddress(port));
        this.uploadDir = System.getProperty("java.io.tmpdir") + File.separator + "p2p-uploads";
        this.executorService = Executors.newFixedThreadPool(10);

        File uploadDirFile = new File(uploadDir);
        if(!uploadDirFile.exists()){
            uploadDirFile.mkdirs();
        }

        server.createContext("/upload", new UploadHandler());
        server.createContext("/download", new DownloadHandler());
        server.createContext("/", new CORSHandler());
        server.setExecutor(executorService);
    }

    public void start(){
        server.start();
        System.out.println("API server started on port " + server.getAddress().getPort());
    }

    public void stop(){
        server.stop(0);
        executorService.shutdown();
        System.out.println("API server stopped");
    }

    private class CORSHandler implements HttpHandler{
        @Override
        public void handle(HttpExchange exchange) throws IOException{
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type");

            if(exchange.getRequestMethdo().equals("OPTIONS")){
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            String response = "NOT FOUND";
            exchange.sendResponseHeaders(404, response.getBytes().length);
            try (OutputStream oos = exchange.getResponseBody()){
                oos.write(response.getBytes());
            }
        }
    }

    private class UploadHandler implements HttpHandler{
        @Override
        public void handle(HttpExchange exchange) throws IOException{
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            if(!exchange.getRequestMethod().equalsIgnoreCase("POST")){
                String response = "Method not allowed";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                try (OutputStream oos = exchange.getResponseBody()){
                    oos.write(response.getBytes());
                }
                return;
            }

            Headers requestHeaders = exchange.getRequestHeaders();
            String contentType = requestHeaders.getFirst("Content-Type");
            if(contentType == null || !contentType.startsWith("multipart/form-data")){
                String response = "Bad Request: Content-Type must be multipart/form-data";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream oos = exchange.getResponseBody()){
                    oos.write(response.getBytes());
                }
                return;
            }

            try{
                String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                IOUtils.copy(exchange.getRequestBody(), baos);
                byte[] requestData = baos.toByteArray();

                Multiparser parse = new MultipartParser(requestData, boundary);

            } catch(IOException e){
                e.printStackTrace();
            }
        }
    }
}

private static class Multiparser {
    private final byte[] data;
    private final String boundary;

    public Multiparser(byte[] data, String boundary){
        this.data = data;
        this.boundary = boundary;
    }

    public ParseResult parse(){
        try{
            String dataAsString = new String(data);
            String filenameMarker = "filename=\"";
            int filenameStart = dataAsString.indexOf(filenameMarker);
            if (filenameStart == -1){
                return null;
            }
            int filenameEnd = dataAsString.indexOf("\"");
            String fileName = dataAsString.substring(filenameStart, filenameEnd);

            String contentTypeMarker = "Content-Type: ";
            int contentTypeStart = dataAsString.indexOf(contentTypeMarker, filenameEnd);
            Strint contentType = "application/octet-stream";
            if(contentTypeStart != -1){
                contentTypeStart += contentTypeMarker.length();
                int contentTypeEnd = dataAsString.indexOf("\r\n", contentTypeStart);
                contentType = dataAsString.substring(contentTypeStart, contentTypeEnd);
            }

            String headerEndMarker = "\r\n\r\n";
            int headerEnd = dataAsString.indexOf(headerEndMarker);
            if(headerEnd == -1){
                return null;
            }
            int contentStart = headerEnd + headerEndMarker.length();

            byte[] boundaryBytes = ("\r\n--" + boundary + "--").getBytes();
            int contentEnd = findSequence(data, boundaryBytes, contentStart);
            if (contentEnd == -1){
                boundaryBytes = ("\r\n--" + boundary).getBytes();
                contentEnd = findSequence(data, boundaryBytes, contentStart);
            }
            if (contentEnd == -1 || contentEnd <= contentStart){
                return null;
            }

            byte[] fileContent = new byte[contentEnd - contentStart];
            System.arraycopy(data, contentStart, fileContent, 0, fileContent.length);
            return new ParseResult(fileName, fileContent, contentType);
        } catch(Exception e){
            System.out.println("Error parsing multipart data: " + e.getMessage());
            return null;
        }
    }
}

public static class ParseResult {
    public final String fileName;
    public final byte[] fileContent;
    public final String contentType;

    public ParseResult(String fileName, byte[] fileContent, String contentType){
        this.fileName = fileName;
        this.fileContent = fileContent;
        this.contentType = contentType;
    }

    private static int findSequence(byte[] data, byte[] sequence, int startPos){
        outer:
            for(int i = startPos; startPos < data.length - sequence.length; i++){
                for(int j = 0; j < sequence.length; j++){
                    if(data[i + j] != sequence[j]){
                        continue outer;
                    }
                }
                return i;
            }
        return -1;
    }
}