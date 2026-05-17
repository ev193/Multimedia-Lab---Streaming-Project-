package streaming;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

public class StreamingServer {
    
    // logger για καταγραφή μηνυμάτων
    private static final java.util.logging.Logger logger = 
        java.util.logging.Logger.getLogger(StreamingServer.class.getName());
    
    // η πόρτα που ακούει ο server
    private static final int PORT = 12345;

    public static void main(String[] args) {
        logger.info("server is starting...");
        
        // δημιουργία των αρχείων που λείπουν στον φάκελο videos
        generateMissingFiles();
        
        // ορισμός του keystore για ssl/tls κρυπτογράφηση
        System.setProperty("javax.net.ssl.keyStore", "streaming.keystore");
        System.setProperty("javax.net.ssl.keyStorePassword", "streaming123");

        // δημιουργία ssl server socket
        SSLServerSocketFactory factory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
        try (SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(PORT)) {
            logger.info("server is listening on port " + PORT);
            
            // ατέρμονας βρόχος αναμονής clients
            while (true) {
                Socket clientSocket = serverSocket.accept();
                logger.info(() -> "a client connected: " + clientSocket.getInetAddress());
                
                // κάθε client εξυπηρετείται σε ξεχωριστό thread
                new Thread(() -> handleClient(clientSocket)).start();
            }

        } catch (IOException e) {
            logger.info(() -> "server error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // μέθοδος εξυπηρέτησης client
    private static void handleClient(Socket clientSocket) {
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
        ) {
            // διαβάζω το αίτημα του client
            String request = in.readLine();
            logger.info(() -> "request from client: " + request);
            
            // εξαγωγή ταχύτητας και format από το αίτημα
            double speed = extractSpeed(request);
            String format = extractFormat(request);

            logger.info(() -> "client speed: " + speed + " mbps");
            logger.info(() -> "client format: " + format);

            // βρίσκω τα διαθέσιμα βίντεο με βάση την ταχύτητα και το format
            List<String> availableVideos = getAvailableVideos(speed, format);

            // στέλνω τη λίστα στον client
            for (String video : availableVideos) {
                out.println(video);
            }
            out.println("END");
            logger.info("video list sent to client.");

            // διαβάζω την επιλογή του client
            String selectionMessage = in.readLine();
            logger.info(() -> "selection from client: " + selectionMessage);
            
            // έλεγχος αν ο client αποσυνδέθηκε πριν στείλει επιλογή
            if (selectionMessage == null) {
                logger.info("client closed connection before sending selection.");
                return;
            }
            
            // εξαγωγή επιλεγμένου βίντεο και πρωτοκόλλου
            String selectedVideo = extractSelectedVideo(selectionMessage);
            String protocol = extractProtocol(selectionMessage);

            if (selectedVideo != null && protocol != null) {
                int streamPort = 5200;
                
                // αποστολή επιβεβαίωσης με τη θύρα streaming
                String response = "you selected video: " + selectedVideo
                        + " with protocol: " + protocol
                        + ";PORT=" + streamPort;
                out.println(response);
                logger.info("confirmation sent to client.");
                
                // έναρξη streaming
                startStreamingSimulation(selectedVideo, protocol, streamPort);
            } else {
                out.println("invalid selection.");
            }

        } catch (IOException e) {
            logger.info(() -> "error while communicating with client: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // κλείσιμο σύνδεσης με client
            try {
                clientSocket.close();
                logger.info(() -> "client " + clientSocket.getInetAddress() + " disconnected.");
            } catch (IOException e) {
                logger.info(() -> "error closing client socket: " + e.getMessage());
            }
        }
    }

    // εξαγωγή ταχύτητας από το αίτημα
    private static double extractSpeed(String request) {
        String[] parts = request.split(";");
        for (String part : parts) {
            if (part.startsWith("SPEED=")) {
                return Double.parseDouble(part.substring(6));
            }
        }
        return 0.0;
    }

    // εξαγωγή format από το αίτημα
    private static String extractFormat(String request) {
        String[] parts = request.split(";");
        for (String part : parts) {
            if (part.startsWith("FORMAT=")) {
                return part.substring(7);
            }
        }
        return "mp4";
    }

    // εξαγωγή επιλεγμένου βίντεο από το μήνυμα επιλογής
    private static String extractSelectedVideo(String message) {
        String[] parts = message.split(";");
        for (String part : parts) {
            if (part.startsWith("SELECT=")) {
                return part.substring(7);
            }
        }
        return null;
    }

    // εξαγωγή πρωτοκόλλου από το μήνυμα επιλογής
    private static String extractProtocol(String message) {
        String[] parts = message.split(";");
        for (String part : parts) {
            if (part.startsWith("PROTOCOL=")) {
                return part.substring(9);
            }
        }
        return null;
    }

    // επιστρέφει λίστα με τα κατάλληλα βίντεο για την ταχύτητα και το format του client
    private static List<String> getAvailableVideos(double speed, String format) {
        List<String> videos = new ArrayList<>();
        File folder = new File("videos");

        if (!folder.exists() || !folder.isDirectory()) {
            logger.info("videos folder not found.");
            return videos;
        }

        File[] files = folder.listFiles();
        if (files == null) return videos;

        // μέγιστη ανάλυση που υποστηρίζει η ταχύτητα του client
        int maxResolution = getMaxResolution(speed);

        for (File file : files) {
            String fileName = file.getName();
            
            // φιλτράρισμα με βάση το format
            if (!fileName.endsWith("." + format)) continue;
            
            int resolution = extractResolution(fileName);
            
            // φιλτράρισμα με βάση την ανάλυση
            if (resolution <= maxResolution) {
                videos.add(fileName);
            }
        }

        return videos;
    }
    
    // αντιστοίχιση ταχύτητας με μέγιστη ανάλυση βάσει youtube bitrate table
    private static int getMaxResolution(double speed) {
        if (speed >= 6.0) return 1080;
        else if (speed >= 4.0) return 720;
        else if (speed >= 2.0) return 480;
        else if (speed >= 1.0) return 360;
        else if (speed >= 0.5) return 240;
        else return 0;
    }

    // εξαγωγή ανάλυσης από το όνομα αρχείου
    private static int extractResolution(String fileName) {
        if (fileName.contains("240p")) return 240;
        else if (fileName.contains("360p")) return 360;
        else if (fileName.contains("480p")) return 480;
        else if (fileName.contains("720p")) return 720;
        else if (fileName.contains("1080p")) return 1080;
        return 9999;
    }
    
    // μετατροπή βίντεο σε διαφορετική ανάλυση με χρήση ffmpeg
    private static void convertVideo(String inputPath, String outputPath, int targetHeight) {
        try {
            // ορισμός διαστάσεων για κάθε ανάλυση
            String scaleOption;
            if (targetHeight == 240) scaleOption = "426:240";
            else if (targetHeight == 360) scaleOption = "640:360";
            else if (targetHeight == 480) scaleOption = "854:480";
            else if (targetHeight == 720) scaleOption = "1280:720";
            else if (targetHeight == 1080) scaleOption = "1920:1080";
            else {
                logger.info(() -> "unsupported resolution: " + targetHeight);
                return;
            }

            // εκτέλεση ffmpeg για μετατροπή
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-loglevel", "error",
                "-i", inputPath,
                "-vf", "scale=" + scaleOption,
                "-c:v", "libx264", "-c:a", "aac",
                outputPath
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // καταγραφή εξόδου ffmpeg
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                final String currentLine = line;
                logger.info(() -> "[ffmpeg] " + currentLine);
            }

            int exitCode = process.waitFor();
            logger.info(() -> "ffmpeg finished with exit code: " + exitCode);
            logger.info(() -> "created file: " + outputPath);

        } catch (Exception e) {
            logger.info(() -> "error converting video: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ελέγχει και δημιουργεί τα αρχεία που λείπουν στον φάκελο videos
    private static void generateMissingFiles() {
        File folder = new File("videos");
        if (!folder.exists() || !folder.isDirectory()) {
            logger.info("videos folder not found.");
            return;
        }

        File[] files = folder.listFiles();
        if (files == null) return;

        // εύρεση μοναδικών ονομάτων ταινιών
        List<String> movieNames = new ArrayList<>();
        for (File file : files) {
            String fileName = file.getName();
            int dashIndex = fileName.lastIndexOf("-");
            if (dashIndex == -1) continue;
            String movieName = fileName.substring(0, dashIndex);
            if (!movieNames.contains(movieName)) {
                movieNames.add(movieName);
            }
        }

        // δημιουργία αρχείων για κάθε ταινία
        for (String movieName : movieNames) {
            generateFilesForMovie(movieName, files);
        }
    }
    
    // δημιουργία όλων των εκδόσεων (format + ανάλυση) για μία ταινία
    private static void generateFilesForMovie(String movieName, File[] files) {
        String[] formats = {"mp4", "avi", "mkv"};
        int[] resolutions = {240, 360, 480, 720, 1080};

        // εύρεση του αρχείου με τη μέγιστη ανάλυση για να χρησιμοποιηθεί ως πηγή
        int maxExistingResolution = 0;
        String bestSourceFile = null;

        for (File file : files) {
            String fileName = file.getName();
            if (!fileName.startsWith(movieName + "-")) continue;
            int resolution = extractResolution(fileName);
            if (resolution > maxExistingResolution) {
                maxExistingResolution = resolution;
                bestSourceFile = fileName;
            }
        }

        if (bestSourceFile == null) return;

        // δημιουργία αρχείων για κάθε συνδυασμό ανάλυσης και format
        for (int resolution : resolutions) {
            // δεν δημιουργούμε αρχεία με μεγαλύτερη ανάλυση από αυτή που υπάρχει
            if (resolution > maxExistingResolution) continue;
            for (String format : formats) {
                String targetFileName = movieName + "-" + resolution + "p." + format;
                File targetFile = new File("videos/" + targetFileName);
                
                // αν το αρχείο υπάρχει ήδη, το παραλείπουμε
                if (targetFile.exists()) continue;
                
                logger.info(() -> "creating missing file: " + targetFileName);
                convertVideo("videos/" + bestSourceFile, "videos/" + targetFileName, resolution);
            }
        }
    }
    
    // έναρξη streaming ανάλογα με το επιλεγμένο πρωτόκολλο
    private static void startStreamingSimulation(String selectedVideo, String protocol, int clientPort) {
        logger.info(() -> "preparing streaming for file: " + selectedVideo);
        int streamPort = 5000 + (clientPort % 1000);

        // καταγραφή χρόνου έναρξης για στατιστικά
        long startTime = System.currentTimeMillis();

        logger.info(() -> "client selected video: " + selectedVideo);
        logger.info(() -> "protocol used: " + protocol);
        logger.info(() -> "streaming port: " + streamPort);

        switch (protocol) {
            case "TCP":
                // το adaptive streaming τρέχει σε ξεχωριστό thread
                new Thread(() -> startAdaptiveStreaming(selectedVideo, 2.0, streamPort)).start();
                break;
            case "UDP":
                startUdpStreaming(selectedVideo, streamPort);
                break;
            case "RTP/UDP":
                startRtpStreaming(selectedVideo, streamPort);
                break;
            default:
                logger.info("unknown protocol selected.");
        }
        
        // καταγραφή διάρκειας streaming
        long duration = System.currentTimeMillis() - startTime;
        logger.info(() -> "streaming duration: " + duration + " ms");
    }

    // μετάδοση βίντεο μέσω udp
    private static void startUdpStreaming(String selectedVideo, int port) {
        logger.info(() -> "starting streaming using udp on port " + port);
        String inputPath = "videos/" + selectedVideo;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-re", "-i", inputPath, "-f", "mpegts", "udp://127.0.0.1:" + port
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                final String currentLine = line;
                logger.info(() -> "[udp stream] " + currentLine);
            }
            process.waitFor();
            logger.info(() -> "udp streaming finished for: " + selectedVideo);

        } catch (Exception e) {
            logger.info(() -> "udp streaming error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // μετάδοση βίντεο μέσω rtp/udp
    // χρησιμοποιούμε mpeg2video γιατί το ffplay το αναγνωρίζει χωρίς sdp file
    private static void startRtpStreaming(String selectedVideo, int port) {
        logger.info(() -> "starting streaming using rtp over udp on port " + port);
        String inputPath = "videos/" + selectedVideo;

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-re", "-i", inputPath,
                "-vcodec", "mpeg2video",
                "-f", "rtp",
                "rtp://127.0.0.1:" + port
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                final String currentLine = line;
                logger.info(() -> "[rtp stream] " + currentLine);
            }
            process.waitFor();
            logger.info(() -> "rtp streaming finished for: " + selectedVideo);

        } catch (Exception e) {
            logger.info(() -> "rtp streaming error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // adaptive streaming μέσω tcp - αλλάζει ανάλυση ανάλογα με την ταχύτητα δικτύου
    private static void startAdaptiveStreaming(String selectedVideo, double speed, int port) {
        logger.info(() -> "starting adaptive streaming on port " + port);
        
        // εξαγωγή ονόματος ταινίας χωρίς την ανάλυση
        String baseName = selectedVideo.substring(0, selectedVideo.lastIndexOf("-"));

        try {
            // μετάδοση 5 chunks των 10 δευτερολέπτων
            for (int i = 0; i < 5; i++) {
                final int chunk = i + 1;
                logger.info(() -> "--- chunk " + chunk + " ---");

                // επιλογή κατάλληλης ανάλυσης για την τρέχουσα ταχύτητα
                int resolution = getMaxResolution(speed);
                String fileName = baseName + "-" + resolution + "p.mp4";
                String inputPath = "videos/" + fileName;

                File file = new File(inputPath);
                if (!file.exists()) {
                    logger.info(() -> "file not found: " + fileName + ", skipping...");
                    continue;
                }

                final double currentSpeed = speed;
                logger.info(() -> "streaming: " + fileName + " (speed: " + currentSpeed + " mbps)");

                // εκτέλεση ffmpeg για μετάδοση chunk 10 δευτερολέπτων μέσω tcp
                ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-re", "-i", inputPath,
                    "-t", "10", "-f", "mpegts",
                    "tcp://127.0.0.1:" + port + "?listen=1"
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    final String currentLine = line;
                    logger.info(() -> "[adaptive stream] " + currentLine);
                }
                process.waitFor();

                // προσομοίωση αλλαγής ταχύτητας δικτύου
                speed = simulateSpeedChange(speed);
            }

            logger.info("adaptive streaming finished.");

        } catch (Exception e) {
            logger.info(() -> "adaptive streaming error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // προσομοίωση τυχαίας αλλαγής ταχύτητας δικτύου μεταξύ 0.5 και 6.0 mbps
    private static double simulateSpeedChange(double speed) {
        double newSpeed = Math.max(0.5, Math.min(6.0, speed + (Math.random() - 0.5) * 1.0));
        logger.info(() -> "new simulated speed: " + newSpeed + " mbps");
        return newSpeed;
    }
}