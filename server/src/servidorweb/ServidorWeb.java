package servidorweb;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServidorWeb {

    // Configuración del Pool
    private static final int HILOS_MAXIMOS = 4; // Límite pequeño para probar rápido
    private int puerto;
    private ThreadPoolExecutor pool;
    public static final String CARPETA_WEB = "www";

    public ServidorWeb(int puerto) {
        this.puerto = puerto;
        // Creamos el pool de conexiones (el grupo de trabajadores)
        this.pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(HILOS_MAXIMOS);

        // Crear carpeta base automáticamente si no existe
        File directory = new File(CARPETA_WEB);
        if (!directory.exists()) {
            directory.mkdir();
            System.out.println(">> Carpeta 'www' creada. (Aún está vacía).");
        }
    }

    public void iniciar() {
        try (ServerSocket serverSocket = new ServerSocket(puerto)) {
            System.out.println("------------------------------------------------");
            System.out.println("Servidor iniciado en puerto: " + puerto);
            System.out.println("Para probar, ve a: http://localhost:" + puerto + "/index.html");
            System.out.println("------------------------------------------------");

            while (true) {
                Socket cliente = serverSocket.accept();

                // --- LÓGICA DE BALANCEO (El Portero) ---
                int hilosActivos = pool.getActiveCount();

                // Si el antro está lleno (mitad de capacidad) y NO somos el servidor de respaldo (8081)
                if (hilosActivos >= (HILOS_MAXIMOS / 2) && puerto != 8081) {
                    System.out.println(">> ¡SERVIDOR LLENO! (" + hilosActivos + " activos). Redirigiendo al 8081...");
                    redirigirPeticion(cliente);
                } else {
                    // Si hay espacio, pásale el cliente a un trabajador
                    pool.execute(new ManejadorCliente(cliente));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void redirigirPeticion(Socket socket) {
        try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            // Respuesta HTTP 302 para redireccionar
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

    // --- CLASE DEL TRABAJADOR (Maneja cada conexión) ---
    private static class ManejadorCliente implements Runnable {
        private Socket socket;

        public ManejadorCliente(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            // RETRASO ARTIFICIAL: 5 segundos para que te dé tiempo de llenar el servidor
            try { Thread.sleep(5000); } catch (InterruptedException e) {}

            try (
                    BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
                    BufferedReader reader = new BufferedReader(new InputStreamReader(bis));
                    OutputStream out = socket.getOutputStream()
            ) {
                // 1. Leer qué pide el cliente
                String requestLine = reader.readLine();
                if (requestLine == null) return;

                System.out.println("[" + Thread.currentThread().getName() + "] Atendiendo: " + requestLine);
                StringTokenizer tokenizer = new StringTokenizer(requestLine);
                String method = tokenizer.nextToken();
                String fileName = tokenizer.nextToken().substring(1); // Quitar la barra inicial

                if (fileName.isEmpty()) fileName = "index.html";

                // 2. Leer Encabezados (Para cumplir requisito)
                String headerLine;
                int contentLength = 0;
                while (!(headerLine = reader.readLine()).isEmpty()) {
                    if (headerLine.startsWith("Content-Length:")) {
                        contentLength = Integer.parseInt(headerLine.split(":")[1].trim());
                    }
                    // Aquí podrías imprimir Cookies o User-Agent si quieres
                }

                // 3. Decidir qué hacer según el método
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
                        String error = "HTTP/1.1 405 Method Not Allowed\r\n\r\nMetodo no permitido";
                        out.write(error.getBytes());
                }

            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try { socket.close(); } catch (IOException e) {}
            }
        }

        // --- MÉTODOS DE AYUDA ---
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
                String error = "HTTP/1.1 404 Not Found\r\nContent-Type: text/html\r\n\r\n<html><h1>Error 404: Archivo no encontrado</h1></html>";
                out.write(error.getBytes());
            }
        }

        private void leerCuerpoYResponder(BufferedReader reader, int length, OutputStream out) throws IOException {
            char[] body = new char[length];
            reader.read(body, 0, length);
            String response = "HTTP/1.1 200 OK\r\n\r\n<html><h1>POST Recibido</h1><p>" + new String(body) + "</p></html>";
            out.write(response.getBytes());
        }

        private void guardarArchivo(String fileName, BufferedReader reader, int length, OutputStream out) throws IOException {
            char[] body = new char[length];
            reader.read(body, 0, length);
            try (FileWriter fw = new FileWriter(CARPETA_WEB + File.separator + fileName)) { fw.write(body); }
            String response = "HTTP/1.1 201 Created\r\n\r\n<html><h1>Archivo creado con PUT</h1></html>";
            out.write(response.getBytes());
        }

        private void borrarArchivo(String fileName, OutputStream out) throws IOException {
            File file = new File(CARPETA_WEB + File.separator + fileName);
            if (file.exists() && file.delete()) {
                String response = "HTTP/1.1 200 OK\r\n\r\n<html><h1>Archivo eliminado</h1></html>";
                out.write(response.getBytes());
            } else {
                String error = "HTTP/1.1 404 Not Found\r\n\r\n<html><h1>No se pudo eliminar</h1></html>";
                out.write(error.getBytes());
            }
        }

        private String getMimeType(String fileName) {
            if (fileName.endsWith(".html")) return "text/html";
            if (fileName.endsWith(".jpg")) return "image/jpeg";
            return "text/plain";
        }
    }

    // --- PUNTO DE ARRANQUE ---
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("¿Puerto? (8000 para Principal / 8081 para Respaldo): ");
        int puerto = scanner.nextInt();
        new ServidorWeb(puerto).iniciar();
    }
}