package servidorweb;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServidorWeb {

    private int puerto;
    private int limiteHilos;
    private ThreadPoolExecutor pool;
    public static final String CARPETA_WEB = "www";

    // Constructor modificado para recibir el tamaño del pool
    public ServidorWeb(int puerto, int limiteHilos) {
        this.puerto = puerto;
        this.limiteHilos = limiteHilos;
        // Creamos el pool con el tamaño que eligió el usuario
        this.pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(limiteHilos);

        // Crear carpeta base automáticamente
        File directory = new File(CARPETA_WEB);
        if (!directory.exists()) {
            directory.mkdir();
            System.out.println(">> Carpeta 'www' creada.");
        }
    }

    public void iniciar() {
        try (ServerSocket serverSocket = new ServerSocket(puerto)) {
            System.out.println("------------------------------------------------");
            System.out.println("Servidor iniciado en puerto: " + puerto);
            System.out.println("Tamaño del Pool: " + limiteHilos + " hilos.");
            System.out.println("Punto de saturación (Redirección): " + (limiteHilos / 2) + " hilos activos.");
            System.out.println("Sitio local: http://localhost:" + puerto + "/index.html");
            System.out.println("------------------------------------------------");

            while (true) {
                Socket cliente = serverSocket.accept();

                // --- LÓGICA DE BALANCEO (El Portero) ---
                int hilosActivos = pool.getActiveCount();

                // Si superamos la mitad y NO somos el servidor de respaldo (8081)
                if (hilosActivos >= (limiteHilos / 2) && puerto != 8081) {
                    System.out.println(">> [ALERTA] Servidor Saturado (" + hilosActivos + " activos). Redirigiendo al 8081...");
                    redirigirPeticion(cliente);
                } else {
                    pool.execute(new ManejadorCliente(cliente));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void redirigirPeticion(Socket socket) {
        try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            // Respuesta HTTP 302 Found (Redirección temporal)
            String respuesta = "HTTP/1.1 302 Found\r\n" +
                    "Location: http://localhost:8081/index.html\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            out.print(respuesta);
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try { socket.close(); } catch (IOException e) {}
        }
    }

    // --- CLASE DEL TRABAJADOR ---
    private static class ManejadorCliente implements Runnable {
        private Socket socket;

        public ManejadorCliente(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            // RETRASO ARTIFICIAL (Solo para poder probar la saturación manualmente)
            try { Thread.sleep(5000); } catch (InterruptedException e) {}

            try (
                    BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
                    BufferedReader reader = new BufferedReader(new InputStreamReader(bis));
                    OutputStream out = socket.getOutputStream()
            ) {
                String requestLine = reader.readLine();
                if (requestLine == null) return;

                System.out.println("[" + Thread.currentThread().getName() + "] Petición: " + requestLine);
                StringTokenizer tokenizer = new StringTokenizer(requestLine);
                String method = tokenizer.nextToken();
                String fileName = tokenizer.nextToken().substring(1);

                if (fileName.isEmpty()) fileName = "index.html";

                // --- 2. LEER ENCABEZADOS (Requisito WhatsApp) ---
                String headerLine;
                int contentLength = 0;
                while (!(headerLine = reader.readLine()).isEmpty()) {
                    if (headerLine.startsWith("Content-Length:")) {
                        contentLength = Integer.parseInt(headerLine.split(":")[1].trim());
                    }
                    // Demostramos que leemos los headers importantes
                    else if (headerLine.startsWith("User-Agent:")) {
                        System.out.println("    -> Navegador detectado: " + headerLine.substring(11).trim());
                    }
                    else if (headerLine.startsWith("Cookie:")) {
                        System.out.println("    -> Cookies: " + headerLine.substring(7).trim());
                    }
                }

                // --- 3. MÉTODOS HTTP ---
                switch (method) {
                    case "GET":
                        enviarArchivo(fileName, out);
                        break;
                    case "POST":
                        leerCuerpoYResponder(reader, contentLength, out);
                        break;
                    case "PUT":
                        guardarArchivo(fileName, reader, contentLength, out);
                        break;
                    case "DELETE":
                        borrarArchivo(fileName, out);
                        break;
                    default:
                        enviarRespuesta(out, "405 Method Not Allowed", "<h1>405 Metodo no permitido</h1>");
                }

            } catch (Exception e) {
                // Manejo de error 500 (Requisito WhatsApp)
                e.printStackTrace();
                try {
                    enviarRespuesta(socket.getOutputStream(), "500 Internal Server Error", "<h1>500 Error Interno</h1>");
                } catch (IOException ex) {}
            } finally {
                try { socket.close(); } catch (IOException e) {}
            }
        }

        // --- MÉTODOS AUXILIARES ---
        private void enviarArchivo(String fileName, OutputStream out) throws IOException {
            File file = new File(CARPETA_WEB + File.separator + fileName);
            if (file.exists() && !file.isDirectory()) {
                String mimeType = getMimeType(fileName);
                byte[] fileBytes = new byte[(int) file.length()];
                try (FileInputStream fis = new FileInputStream(file)) { fis.read(fileBytes); }

                String header = "HTTP/1.1 200 OK\r\nContent-Type: " + mimeType + "\r\nContent-Length: " + file.length() + "\r\n\r\n";
                out.write(header.getBytes());
                out.write(fileBytes);
            } else {
                enviarRespuesta(out, "404 Not Found", "<h1>404 Archivo no encontrado</h1>");
            }
        }

        private void leerCuerpoYResponder(BufferedReader reader, int length, OutputStream out) throws IOException {
            char[] body = new char[length];
            reader.read(body, 0, length);
            enviarRespuesta(out, "200 OK", "<h1>POST Recibido</h1><p>" + new String(body) + "</p>");
        }

        private void guardarArchivo(String fileName, BufferedReader reader, int length, OutputStream out) throws IOException {
            char[] body = new char[length];
            reader.read(body, 0, length);
            try (FileWriter fw = new FileWriter(CARPETA_WEB + File.separator + fileName)) { fw.write(body); }
            enviarRespuesta(out, "201 Created", "<h1>Archivo creado con PUT</h1>");
        }

        private void borrarArchivo(String fileName, OutputStream out) throws IOException {
            File file = new File(CARPETA_WEB + File.separator + fileName);
            if (file.exists() && file.delete()) {
                enviarRespuesta(out, "200 OK", "<h1>Archivo eliminado</h1>");
            } else {
                enviarRespuesta(out, "404 Not Found", "<h1>No se pudo eliminar</h1>");
            }
        }

        private void enviarRespuesta(OutputStream out, String status, String htmlBody) throws IOException {
            String response = "HTTP/1.1 " + status + "\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: " + htmlBody.length() + "\r\n" +
                    "\r\n" + htmlBody;
            out.write(response.getBytes());
        }

        // --- REQUISITO: AL MENOS 4 TIPOS MIME ---
        private String getMimeType(String fileName) {
            if (fileName.endsWith(".html")) return "text/html";
            if (fileName.endsWith(".css")) return "text/css";       // Agregado
            if (fileName.endsWith(".js")) return "application/javascript"; // Agregado
            if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return "image/jpeg";
            if (fileName.endsWith(".pdf")) return "application/pdf"; // Agregado
            return "text/plain";
        }
    }

    // --- MAIN ---
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("--- CONFIGURACION ---");
        System.out.print("1. ¿Puerto? (8000 Principal / 8081 Respaldo): ");
        int puerto = scanner.nextInt();

        System.out.print("2. ¿Tamaño del Pool de Hilos? (Ej: 4): ");
        int hilos = scanner.nextInt();

        new ServidorWeb(puerto, hilos).iniciar();
    }
}