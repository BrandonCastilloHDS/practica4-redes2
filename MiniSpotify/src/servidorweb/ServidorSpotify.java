package servidorweb; // Coincide con tu carpeta

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public class ServidorSpotify {
    private int puerto;
    private ThreadPoolExecutor pool;
    public static final String CARPETA_WEB = "www";

    public ServidorSpotify(int puerto) {
        this.puerto = puerto;
        this.pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
        File dir = new File(CARPETA_WEB);
        if (!dir.exists()) dir.mkdir();
    }

    public void iniciar() {
        try (ServerSocket serverSocket = new ServerSocket(puerto)) {
            System.out.println("Servidor Spotify iniciado en puerto: " + puerto);
            while (true) {
                Socket cliente = serverSocket.accept();
                pool.execute(new ManejadorRecurso(cliente, puerto));
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private static class ManejadorRecurso implements Runnable {
        private Socket socket;
        private int puertoActual;

        public ManejadorRecurso(Socket s, int p) { this.socket = s; this.puertoActual = p; }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 OutputStream out = socket.getOutputStream()) {

                String linea = in.readLine();
                if (linea == null) return;

                String[] partes = linea.split(" ");
                if (partes.length < 2) return;
                String path = partes[1].substring(1).toLowerCase();

                // Lógica de Redirección en puerto 8000
                if (puertoActual == 8000) {
                    if (path.contains("rock")) {
                        System.out.println("[8000] -> Redirigiendo Rock al 8081");
                        enviarRedireccion(out, "http://localhost:8081/" + path);
                        return;
                    } else if (path.contains("pop")) {
                        System.out.println("[8000] -> Redirigiendo Pop al 8082");
                        enviarRedireccion(out, "http://localhost:8082/" + path);
                        return;
                    }
                }

                enviarArchivo(path, out);
            } catch (Exception e) { e.printStackTrace(); }
            finally { try { socket.close(); } catch (IOException e) {} }
        }

        private void enviarRedireccion(OutputStream out, String url) throws IOException {
            String res = "HTTP/1.1 302 Found\r\nLocation: " + url + "\r\nConnection: close\r\n\r\n";
            out.write(res.getBytes());
            out.flush();
        }

        private void enviarArchivo(String path, OutputStream out) throws IOException {
            File file = new File(CARPETA_WEB + File.separator + path);
            if (file.exists() && !file.isDirectory()) {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nConnection: close\r\n\r\n".getBytes());
                Files.copy(file.toPath(), out);
            } else {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/html\r\n\r\n<h1>404 No encontrado</h1>".getBytes());
            }
            out.flush();
        }
    }

    public static void main(String[] args) {
        // Iniciamos los 3 nodos del sistema
        new Thread(() -> new ServidorSpotify(8000).iniciar()).start(); // Router
        new Thread(() -> new ServidorSpotify(8081).iniciar()).start(); // Rock
        new Thread(() -> new ServidorSpotify(8082).iniciar()).start(); // Pop
    }
}