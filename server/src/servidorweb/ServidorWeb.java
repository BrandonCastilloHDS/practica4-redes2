package servidorweb;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.Desktop;

public class ServidorWeb {

    private int puerto;
    private int limiteHilos;
    private ThreadPoolExecutor pool;
    public static final String CARPETA_WEB = "www";

    // Constructor
    public ServidorWeb(int puerto, int limiteHilos) {
        this.puerto = puerto;
        this.limiteHilos = limiteHilos;
        this.pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(limiteHilos);


        File directory = new File(CARPETA_WEB);
        if (!directory.exists()) {
            directory.mkdir();
        }
    }

    // Método para iniciar el servidor
    public void iniciar() {
        try (ServerSocket serverSocket = new ServerSocket(puerto)) {
            if (puerto == 8000) {
                System.out.println("=================================================");
                System.out.println(" SERVIDOR PRINCIPAL (8000) INICIADO");
                System.out.println(" Pool Configurado: " + limiteHilos + " hilos.");
                System.out.println(" Regla: Al llegar a " + (limiteHilos/2) + " activos -> Redirige al 8081.");
                System.out.println("=================================================");
            } else {
                System.out.println(">> [SISTEMA] Servidor de Respaldo (8081) listo.");
            }

            while (true) {
                Socket cliente = serverSocket.accept();
                int hilosActivos = pool.getActiveCount();

                // Para redirigir cuando se sobre pase la mitad de los hilos
                if (hilosActivos >= (limiteHilos / 2) && puerto != 8081) {
                    System.out.println(">> [ALERTA 8000] Saturado (" + hilosActivos + " activos). ¡Redirigiendo al 8081!");
                    redirigirPeticion(cliente, "http://localhost:8081/index.html");
                } else {
                    pool.execute(new ManejadorCliente(cliente));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Redirección
    private void redirigirPeticion(Socket socket, String ubicacion) {
        try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            String respuesta = "HTTP/1.1 302 Found\r\n" +
                    "Location: " + ubicacion + "\r\n" +
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

    // --- CLASE INTERNA: MANEJADOR DEL CLIENTE ---
    private static class ManejadorCliente implements Runnable {
        private Socket socket;

        public ManejadorCliente(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {

            try { Thread.sleep(500); } catch (InterruptedException e) {}

            try (
                    BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
                    BufferedReader reader = new BufferedReader(new InputStreamReader(bis));
                    OutputStream out = socket.getOutputStream()
            ) {
                String requestLine = reader.readLine();
                if (requestLine == null) return;


                System.out.println("\n--- [NUEVA SOLICITUD] ---");
                // 1. RECURSO / DIRECCIÓN
                System.out.println("RECURSO SOLICITADO: " + requestLine);

                System.out.println("--- CABECERAS (HEADERS) ---");
                String headerLine;
                while (!(headerLine = reader.readLine()).isEmpty()) {
                    // Imprimimos TODO lo que llega. Esto mostrará:
                    // Host, Connection, User-Agent, Accept, Accept-Encoding,
                    // Accept-Language, Upgrade-Insecure-Requests, Cookie, etc.
                    System.out.println(headerLine);
                }
                System.out.println("---------------------------");
                // ---------------------------------------------------------

                StringTokenizer tokenizer = new StringTokenizer(requestLine);
                String method = tokenizer.nextToken();
                String path = tokenizer.nextToken().substring(1);
                if (path.isEmpty()) path = "index.html";

                // --- 2. RUTAS PARA CÓDIGOS DE ESTADO Y TIPOS MIME ---

                if (path.equals("redirect")) {
                    enviarRespuesta(out, "302 Found", "text/html", "<h1>Redirigiendo...</h1>", "Location: /\r\n");

                } else if (path.equals("cliente")) {
                    enviarRespuesta(out, "400 Bad Request", "text/html", "<h1>400 Peticion Incorrecta</h1>", null);

                } else if (path.equals("servidor")) {
                    enviarRespuesta(out, "500 Internal Server Error", "text/html", "<h1>500 Error Interno</h1>", null);

                } else if (path.equals("info")) {
                    enviarRespuesta(out, "200 OK", "application/json", "{ \"mensaje\": \"Hola JSON\" }", null);

                } else if (method.equals("PUT")) {

                    StringBuilder body = new StringBuilder();
                    while(reader.ready()) {
                        body.append((char) reader.read());
                    }

                    // Guardamos ese texto en un archivo dentro de la carpeta 'www'
                    File nuevoArchivo = new File(CARPETA_WEB + File.separator + path);
                    try (FileWriter writer = new FileWriter(nuevoArchivo)) {
                        writer.write(body.toString());
                    }

                    System.out.println(">> Archivo GUARDADO: " + nuevoArchivo.getAbsolutePath());
                    enviarRespuesta(out, "201 Created", "text/plain", "Archivo " + path + " creado con exito.", null);
                    
                } else if (method.equals("DELETE")) {

                    File archivoABorrar = new File(CARPETA_WEB + File.separator + path);

                    if (archivoABorrar.exists() && archivoABorrar.isFile()) {
                        boolean borrado = archivoABorrar.delete(); // <--- LA ORDEN CLAVE

                        if (borrado) {
                            System.out.println(">> Archivo BORRADO: " + path);
                            enviarRespuesta(out, "200 OK", "text/plain", "Archivo " + path + " eliminado correctamente.", null);
                        } else {
                            System.out.println(">> Error al intentar borrar: " + path);
                            enviarRespuesta(out, "500 Internal Server Error", "text/plain", "No se pudo borrar el archivo.", null);
                        }
                    } else {
                        enviarRespuesta(out, "404 Not Found", "text/plain", "El archivo no existe, no se puede borrar.", null);
                    }
                    
                } else {
                    enviarArchivo(path, out);
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try { socket.close(); } catch (IOException e) {}
            }
        }

        private void enviarRespuesta(OutputStream out, String status, String mimeType, String content, String extraHeaders) throws IOException {
            String headers = "HTTP/1.1 " + status + "\r\n" +
                    "Content-Type: " + mimeType + "\r\n" +
                    "Content-Length: " + content.length() + "\r\n";
            if (extraHeaders != null) headers += extraHeaders;
            headers += "\r\n";
            out.write(headers.getBytes());
            out.write(content.getBytes());
            out.flush();
        }

        private void enviarArchivo(String fileName, OutputStream out) throws IOException {
            File file = new File(CARPETA_WEB + File.separator + fileName);

            if (file.exists()) {

                // AQUÍ SE DEFINEN LOS TIPOS MIME
                // -----------------------------------------------------------
                String mimeType = "text/plain"; // Por defecto, texto plano

                if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
                    mimeType = "text/html";
                } else if (fileName.endsWith(".css")) {
                    mimeType = "text/css";
                } else if (fileName.endsWith(".js")) {
                    mimeType = "application/javascript";
                } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
                    mimeType = "image/jpeg";
                } else if (fileName.endsWith(".png")) {
                    mimeType = "image/png";
                } else if (fileName.endsWith(".json")) {
                    mimeType = "application/json";
                } else if (fileName.endsWith(".pdf")) {
                    mimeType = "application/pdf";
                }
                // -----------------------------------------------------------

                // Enviamos una Cookie + El tipo MIME correcto
                String header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: " + mimeType + "\r\n" +
                        "Set-Cookie: mi_cookie_servidor=valor123; Path=/\r\n" +
                        "Content-Length: " + file.length() + "\r\n\r\n";

                out.write(header.getBytes());

                // Enviamos el archivo byte por byte (importante para imágenes)
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
            } else {
                String msg = "<h1>404 Recurso no encontrado</h1>";
                enviarRespuesta(out, "404 Not Found", "text/html", msg, null);
            }
        }
    }

    // --- MAIN PRINCIPAL ---
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        int poolSize = 5;

        try {
            System.out.print("Ingrese el numero de pools (hilos): ");
            poolSize = scanner.nextInt();
        } catch (Exception e) {
            System.out.println("Entrada invalida, usando 5 por defecto.");
        }

        final int finalPoolSize = poolSize;

        new Thread(() -> new ServidorWeb(8081, finalPoolSize).iniciar()).start();
        new Thread(() -> new ServidorWeb(8000, finalPoolSize).iniciar()).start();

        System.out.println("Servidores iniciados...");

        // ABRIR NAVEGADOR
        try {
            Thread.sleep(1000);
            System.out.println(">> Abriendo navegador en http://localhost:8000/index.html ...");

            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI("http://localhost:8000/index.html"));
            } else {
                Runtime.getRuntime().exec("rundll32 url.dll,FileProtocolHandler http://localhost:8000/index.html");
            }
        } catch (Exception e) {
            System.out.println("Abre manualmente: http://localhost:8000/index.html");
        }
    }
}