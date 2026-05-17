package streaming;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;

public class StreamingClientGUI {
    
    // logger για καταγραφή μηνυμάτων
    private static final java.util.logging.Logger logger = 
        java.util.logging.Logger.getLogger(StreamingClientGUI.class.getName());
    
    // στοιχεία γραφικού περιβάλλοντος
    private JFrame frame;
    private JTextField speedField;
    private JComboBox<String> formatBox;
    private JComboBox<String> protocolBox;
    private DefaultListModel<String> listModel;
    private JList<String> videoList;
    private JTextArea logArea;

    // στοιχεία σύνδεσης με τον server
    private static final String SERVER_IP = "127.0.0.1";
    private static final int SERVER_PORT = 12345;

    public StreamingClientGUI() {
        // ορισμός truststore για ssl/tls επικοινωνία
        System.setProperty("javax.net.ssl.trustStore", "streaming.keystore");
        System.setProperty("javax.net.ssl.trustStorePassword", "streaming123");
        
        // δημιουργία κύριου παραθύρου
        frame = new JFrame("streaming client");
        frame.setSize(500, 500);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        // πάνω panel με επιλογές χρήστη
        JPanel topPanel = new JPanel(new GridLayout(3, 2));
        topPanel.add(new JLabel("speed (mbps):"));
        speedField = new JTextField("2.0");
        topPanel.add(speedField);

        topPanel.add(new JLabel("format:"));
        formatBox = new JComboBox<>(new String[]{"mp4", "mkv", "avi"});
        topPanel.add(formatBox);

        JButton fetchButton = new JButton("fetch videos");
        topPanel.add(fetchButton);

        JButton streamButton = new JButton("start streaming");
        topPanel.add(streamButton);

        frame.add(topPanel, BorderLayout.NORTH);

        // κεντρικό panel με λίστα βίντεο
        listModel = new DefaultListModel<>();
        videoList = new JList<>(listModel);
        frame.add(new JScrollPane(videoList), BorderLayout.CENTER);

        // κάτω panel με επιλογή πρωτοκόλλου και log
        JPanel bottomPanel = new JPanel(new BorderLayout());
        protocolBox = new JComboBox<>(new String[]{"TCP", "UDP", "RTP/UDP"});
        bottomPanel.add(protocolBox, BorderLayout.NORTH);

        logArea = new JTextArea();
        logArea.setEditable(false);
        bottomPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);

        frame.add(bottomPanel, BorderLayout.SOUTH);

        // σύνδεση κουμπιών με μεθόδους
        fetchButton.addActionListener(this::fetchVideos);
        streamButton.addActionListener(this::startStreaming);

        frame.setVisible(true);
    }

    // δημιουργία ssl socket για ασφαλή επικοινωνία με τον server
    private SSLSocket createSSLSocket() throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        return (SSLSocket) factory.createSocket(SERVER_IP, SERVER_PORT);
    }

    // αίτημα λίστας βίντεο από τον server
    private void fetchVideos(ActionEvent e) {
        listModel.clear();

        try (SSLSocket socket = createSSLSocket();
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            String speed = speedField.getText();
            String format = (String) formatBox.getSelectedItem();

            // αποστολή ταχύτητας και format στον server
            String request = "SPEED=" + speed + ";FORMAT=" + format;
            out.println(request);

            log("sent request: " + request);

            // λήψη λίστας βίντεο
            String line;
            while (!(line = in.readLine()).equals("END")) {
                listModel.addElement(line);
            }

            log("video list received.");

        } catch (Exception ex) {
            log("error: " + ex.getMessage());
        }
    }

    // flag για αποφυγή πολλαπλών ταυτόχρονων streams
    private boolean isStreaming = false;

    // έναρξη streaming για το επιλεγμένο βίντεο
    private void startStreaming(ActionEvent e) {
        String selectedVideo = videoList.getSelectedValue();

        if (selectedVideo == null) {
            log("please select a video.");
            return;
        }

        // αποφυγή διπλής εκκίνησης
        if (isStreaming) {
            log("already streaming, please wait.");
            return;
        }

        isStreaming = true;
        
        // εκτέλεση σε ξεχωριστό thread για να μην μπλοκάρει το gui
        new Thread(() -> {
            try (SSLSocket socket = createSSLSocket();
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                String speed = speedField.getText();
                String format = (String) formatBox.getSelectedItem();
                String protocol = (String) protocolBox.getSelectedItem();

                // αποστολή αρχικού αιτήματος
                String request = "SPEED=" + speed + ";FORMAT=" + format;
                out.println(request);

                // αγνοούμε τη λίστα βίντεο σε αυτή τη σύνδεση
                String line;
                while (!(line = in.readLine()).equals("END")) { }

                // αποστολή επιλογής βίντεο και πρωτοκόλλου
                String selection = "SELECT=" + selectedVideo + ";PROTOCOL=" + protocol;
                out.println(selection);

                // λήψη επιβεβαίωσης και θύρας streaming
                String response = in.readLine();
                log("server: " + response);

                int port = extractPort(response);
                if (port == -1) {
                    log("could not get stream port from server.");
                    return;
                }

                // εκκίνηση ffplay ανάλογα με το πρωτόκολλο
                ProcessBuilder pb;
                if (protocol.equals("TCP")) {
                    // αναμονή για να ανοίξει ο server το tcp socket
                    Thread.sleep(1000);
                    pb = new ProcessBuilder(
                            "ffplay",
                            "-fflags", "nobuffer",
                            "-f", "mpegts",
                            "tcp://127.0.0.1:" + port
                    );
                } else if (protocol.equals("UDP")) {
                    Thread.sleep(1000);
                    pb = new ProcessBuilder(
                            "ffplay",
                            "-fflags", "nobuffer",
                            "-f", "mpegts",
                            "udp://127.0.0.1:" + port
                    );
                } else {
                    // rtp/udp - χρησιμοποιεί mpeg2video οπότε δεν χρειάζεται sdp
                    Thread.sleep(1000);
                    pb = new ProcessBuilder(
                            "ffplay",
                            "rtp://127.0.0.1:" + port
                    );
                }

                pb.redirectErrorStream(true);
                Process process = pb.start();

                // καταγραφή εξόδου ffplay σε ξεχωριστό thread
                BufferedReader ffplayReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream())
                );

                new Thread(() -> {
                    try {
                        String ffplayLine;
                        while ((ffplayLine = ffplayReader.readLine()) != null) {
                            log("[ffplay] " + ffplayLine);
                        }
                    } catch (IOException ex2) {
                        log("error reading ffplay output: " + ex2.getMessage());
                    }
                }).start();

            } catch (Exception ex) {
                log("error: " + ex.getMessage());
            }
            isStreaming = false;
        }).start();
    }
    
    // εξαγωγή θύρας streaming από την απάντηση του server
    private int extractPort(String response) {
        String[] parts = response.split(";");
        for (String part : parts) {
            if (part.startsWith("PORT=")) {
                return Integer.parseInt(part.substring(5));
            }
        }
        return -1;
    }
    
    // καταγραφή μηνύματος στο gui και στον logger
    private void log(String message) {
        logArea.append(message + "\n");
        logger.info(message);
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(StreamingClientGUI::new);
    }
}
